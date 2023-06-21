package io.github.ablearthy.tdjson4s

import io.github.ablearthy.tdjson.LogMessageHandler
import io.github.ablearthy.tl.types.{Update, AuthorizationState, decoders}
import io.github.ablearthy.tl.{types, functions}
import io.github.ablearthy.tl.aliases.Bytes
import io.github.ablearthy.tl.functions.encoders._
import io.github.ablearthy.tl.types.decoders._
import io.github.ablearthy.tl.codecs.TLFunction

import cats.effect.{IO, Ref, Resource, ResourceIO, Deferred}

import fs2.concurrent.{Topic, SignallingRef}
import fs2.Stream

import io.circe.{Json, JsonObject, Encoder, Decoder}
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.Encoder.AsObject

class TDClientManager(
    private val lastExtraId: Ref[IO, Int],
    private val topic: Topic[IO, JsonObject],
    private val client: TDJsonIO[IO],
    private val handlersMap: Ref[IO, Map[Int, Deferred[IO, JsonObject]]]
) {
  final def setLogMessageCallback(
      maxVerbosityLevel: Int,
      handler: LogMessageHandler
  ): IO[Unit] = client.td_set_log_message_callback(maxVerbosityLevel, handler)

  final def execute[In: Encoder.AsObject, Out](
      query: In
  )(using In <:< TLFunction[Out], Decoder[Out]): IO[Either[types.Error, Out]] =
    client
      .td_execute(query.asJson.noSpaces)
      .map(decode[JsonObject])
      .rethrow
      .map { o =>
        if o("@type").flatMap(_.asString).map(_ == "error").getOrElse(false)
        then decoders.errorDecoder.decodeJson(o.asJson).map(Left.apply)
        else Decoder[Out].decodeJson(o.asJson).map(Right.apply)
      }
      .rethrow

  def setLogVerbosityLevel(maxVerbosityLevel: Int): IO[Unit] = client
    .td_execute(
      s"""{"@type": "setLogVerbosityLevel", "new_verbosity_level": $maxVerbosityLevel}"""
    )
    .as(())

  def spawnClient(
      name: String,
      apiId: Int,
      apiHash: String,
      botToken: Option[String] = None,
      langCode: Option[String] = None,
      testMode: Boolean = false,
      deviceModel: Option[String] = None,
      appVersion: Option[String] = None,
      useFileDatabase: Boolean = false,
      useMessageDatabase: Boolean = true,
      useSecretChats: Boolean = false,
      useChatInfoDatabase: Boolean = true,
      enableStorageOptimizer: Boolean = true,
      ignoreFileNames: Boolean = false
  ): ResourceIO[TDClient[IO]] =
    val cb = AuthCallback.default[IO](
      functions.SetTdlibParametersParams(
        use_test_dc = testMode,
        database_directory = name,
        files_directory = "",
        database_encryption_key = Bytes(Array.empty),
        use_file_database = useFileDatabase,
        use_chat_info_database = useChatInfoDatabase,
        use_message_database = useMessageDatabase,
        use_secret_chats = useSecretChats,
        api_id = apiId,
        api_hash = apiHash,
        system_language_code = langCode.getOrElse("en"),
        device_model = deviceModel.getOrElse(
          s"Scala ${scala.util.Properties.versionNumberString}"
        ),
        system_version = appVersion.getOrElse(s"${scala.util.Properties.osName}"),
        application_version = appVersion.getOrElse("1.0.0"),
        enable_storage_optimizer = enableStorageOptimizer,
        ignore_file_names = ignoreFileNames
      )
    )
    spawnClient(new AuthCallback[IO] {

      override def onWaitTdlibParameters
          : IO[functions.SetTdlibParametersParams] =
        cb.onWaitTdlibParameters

      override def onWaitEmailAddress: IO[String] = cb.onWaitEmailAddress

      override def onWaitPhoneNumber: IO[
        functions.CheckAuthenticationBotTokenParams |
          functions.SetAuthenticationPhoneNumberParams
      ] =
        botToken match
          case Some(token) =>
            IO.pure(functions.CheckAuthenticationBotTokenParams(token))
          case None => cb.onWaitPhoneNumber

      override def onWaitOtherDeviceConfirmation(link: String): IO[Unit] =
        cb.onWaitOtherDeviceConfirmation(link)

      override def onWaitRegistration(
          tos: types.TermsOfService
      ): IO[functions.RegisterUserParams] = cb.onWaitRegistration(tos)

      override def onWaitPassword(hint: String): IO[String] =
        cb.onWaitPassword(hint)

      override def onWaitEmailCode: IO[types.EmailAddressAuthentication] =
        cb.onWaitEmailCode

      override def onWaitCode(info: types.AuthenticationCodeInfo): IO[String] =
        cb.onWaitCode(info)

      override def onReady: IO[Unit] = cb.onReady
    })

  def spawnClient(
      cb: AuthCallback[IO]
  ): ResourceIO[TDClient[IO]] =
    def retryOnError[O](c: TDClient[IO], state: AuthorizationState)(
        response: Either[types.Error, O]
    ): IO[Unit] =
      response match
        case Left(err) =>
          IO.println(s"Retrying since got an error: $err") >> doAuth(c)(state)
        case _ => IO.unit

    def doAuth(c: TDClient[IO])(state: AuthorizationState): IO[Unit] =
      state match
        case AuthorizationState.AuthorizationStateWaitTdlibParameters() =>
          cb.onWaitTdlibParameters
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case AuthorizationState.AuthorizationStateWaitPhoneNumber() =>
          cb.onWaitPhoneNumber
            .flatMap { o =>
              o match
                case x: functions.CheckAuthenticationBotTokenParams =>
                  c.queryAsync(x)
                case x: functions.SetAuthenticationPhoneNumberParams =>
                  c.queryAsync(x)
            }
            .map(retryOnError(c, state))
        case AuthorizationState.AuthorizationStateWaitOtherDeviceConfirmation(
              link
            ) =>
          cb.onWaitOtherDeviceConfirmation(link).as(())
        case _: AuthorizationState.AuthorizationStateWaitEmailAddress =>
          cb.onWaitEmailAddress
            .map(functions.SetAuthenticationEmailAddressParams.apply)
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case _: AuthorizationState.AuthorizationStateWaitEmailCode =>
          cb.onWaitEmailCode
            .map(functions.CheckAuthenticationEmailCodeParams.apply)
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case s: AuthorizationState.AuthorizationStateWaitCode =>
          cb.onWaitCode(s.code_info)
            .map(functions.CheckAuthenticationCodeParams.apply)
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case s: AuthorizationState.AuthorizationStateWaitRegistration =>
          cb.onWaitRegistration(s.terms_of_service)
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case s: AuthorizationState.AuthorizationStateWaitPassword =>
          cb.onWaitPassword(s.password_hint)
            .map(functions.CheckAuthenticationPasswordParams.apply)
            .flatMap(c.queryAsync)
            .map(retryOnError(c, state))
        case _: AuthorizationState.AuthorizationStateReady =>
          IO.println("Logged in!")
        case _ => IO.unit

    Resource
      .make {
        for {
          clientId <- client.td_create_client_id
          c = new TDClient[IO] {

            override def queryAsync[In: Encoder.AsObject, Out](query: In)(using
                In <:< TLFunction[Out],
                Decoder[Out]
            ): IO[Either[types.Error, Out]] = for {
              sig <- Deferred[IO, JsonObject]
              extraId <- lastExtraId.getAndUpdate(_ + 1)
              _ <- handlersMap.update(_ + (extraId -> sig))
              _ <- client.td_send(
                clientId,
                Json
                  .fromJsonObject(
                    query.asJsonObject.add("@extra", Json.fromInt(extraId))
                  )
                  .noSpaces
              )
              o <- sig.get.map { o =>
                if o("@type")
                    .flatMap(_.asString)
                    .map(_ == "error")
                    .getOrElse(false)
                then decoders.errorDecoder.decodeJson(o.asJson).map(Left.apply)
                else Decoder[Out].decodeJson(o.asJson).map(Right.apply)
              }.rethrow
            } yield o

            override def send(query: JsonObject): IO[Int] =
              for {
                extraId <- lastExtraId.getAndUpdate(_ + 1)
                _ <- client.td_send(
                  clientId,
                  Json
                    .fromJsonObject(query.add("@extra", Json.fromInt(extraId)))
                    .noSpaces
                )
              } yield extraId

            override def mainStream: Stream[IO, JsonObject] =
              topic.subscribeUnbounded
                .filter(o =>
                  o("@client_id")
                    .flatMap(_.asNumber)
                    .flatMap(_.toInt)
                    .map(_ == clientId)
                    .getOrElse(false)
                )

          }
          _ <- c.updateStream
            .collect { case x: Update.UpdateAuthorizationState =>
              x.authorization_state
            }
            .evalTap(doAuth(c))
            .collectFirst {
              case x: AuthorizationState.AuthorizationStateReady => x
            }
            .concurrently(
              Stream.eval(c.send(functions.GetOptionParams("version")))
            )
            .compile
            .drain
        } yield (clientId, c)
      } { case (clientId, _) =>
        client.td_send(
          clientId,
          Json
            .fromJsonObject(JsonObject("@type" -> Json.fromString("close")))
            .noSpaces
        )
      }
      .map { case (_, c) => c }

}

object TDClientManager {

  def default: IO[TDClientManager] =
    def sendToHandler(
        handlersMapRef: Ref[IO, Map[Int, Deferred[IO, JsonObject]]]
    )(o: JsonObject): IO[Unit] =
      val maybeExtraId = o("@extra").flatMap(_.asNumber).flatMap(_.toInt)
      maybeExtraId match
        case Some(extraId) =>
          for {
            handlersMap <- handlersMapRef.get
            _ <- handlersMap.get(extraId) match
              case Some(sig) =>
                sig.complete(o) >> handlersMapRef.update(_ - extraId)
              case None => IO.unit
          } yield IO.unit
        case None => IO.unit
    val client = TDJsonIO.default[IO]
    for {
      lastExtraId <- Ref[IO].of(0)
      handlersMap <- Ref[IO].of(Map.empty[Int, Deferred[IO, JsonObject]])
      topic <- Topic[IO, JsonObject]
      closeSignal <- Deferred[IO, Either[Throwable, Unit]]
      _ <- Stream
        .repeatEval(client.td_receive(500))
        .map(decode[JsonObject])
        .rethrow
        .evalTap(sendToHandler(handlersMap))
        .through(topic.publish)
        .compile
        .drain
        .start
    } yield TDClientManager(
      lastExtraId = lastExtraId,
      topic = topic,
      client = client,
      handlersMap = handlersMap
    )

}
