package io.github.ablearthy.tdjson4s

import cats.effect.{IO, Resource, ResourceIO, Ref, Deferred}
import cats.effect.std.Random

import io.github.ablearthy.tl.codecs.TLFunction
import io.github.ablearthy.tl.types.{Update, AuthorizationState, decoders}
import io.github.ablearthy.tl.functions
import io.github.ablearthy.tl.functions.encoders._
import io.github.ablearthy.tl.types.decoders._

import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.*
import io.circe.syntax._

import fs2.concurrent.Topic
import fs2.{Stream, Pull}

class TDClient private (
    private val internal_client: TDJsonIO[IO],
    private val clientId: Int,
    private val timeout: Double,
    private val queryMap: Ref[IO, Map[Int, Deferred[IO, Json]]],
    private val randomGen: Random[IO],
    private val topic: Topic[IO, Json],
    private val loggedInSignal: Deferred[IO, Unit],
    private val closedSignal: Deferred[IO, Unit]
):

  def queryAsync[In: Encoder.AsObject, Out](
      query: In
  )(using In <:< TLFunction[Out], Decoder[Out]): IO[Out] =
    for {
      randomId <- randomGen.nextInt
      sig <- Deferred[IO, Json]
      _ <- queryMap.update(_ + (randomId -> sig))
      _ <- internal_client.td_send(
        clientId,
        (("@extra" -> randomId.asJson) +: query.asJsonObject).asJson.noSpaces
      )
      v <- sig.get
      _ <- queryMap.update(_ - randomId)
      value <- IO(Decoder[Out].decodeJson(v)).rethrow
    } yield value

  private def mainLoopStream: Stream[IO, Json] =
    Stream
      .repeatEval(internal_client.td_receive(timeout))
      .filter(!_.isEmpty)
      .map(parse)
      .interruptWhen(closedSignal.get.attempt)
      .rethrow
      .filter(
        _.hcursor
          .downField("@client_id")
          .as[Int]
          .map(_ == clientId)
          .getOrElse(false)
      )
      .through(topic.publish)

  def updateStream: Stream[IO, Update] =
    topic.subscribeUnbounded
      .filter(!_.asObject.map(_.contains("@extra")).getOrElse(true))
      .map(decoders.updateDecoder.decodeJson)
      .rethrow

  private def authStream(authCallback: AuthCallback[IO]): Stream[IO, Unit] =
    updateStream
      .collect { case x: Update.UpdateAuthorizationState =>
        x.authorization_state
      }
      .evalMap { auth_state =>
        auth_state match
          case _: AuthorizationState.AuthorizationStateWaitTdlibParameters =>
            authCallback.provideTdlibParameters.flatMap(queryAsync)
          case _: AuthorizationState.AuthorizationStateWaitPhoneNumber =>
            authCallback.providePhoneNumber.flatMap(queryAsync)
          case _: AuthorizationState.AuthorizationStateWaitEmailAddress =>
            authCallback.provideEmailAddress
              .map(functions.SetAuthenticationEmailAddressParams.apply)
              .flatMap(queryAsync)
          case _: AuthorizationState.AuthorizationStateWaitEmailCode =>
            authCallback.provideEmailCode
              .map(functions.CheckAuthenticationEmailCodeParams.apply)
              .flatMap(queryAsync)
          case _: AuthorizationState.AuthorizationStateWaitCode =>
            authCallback.provideCode
              .map(functions.CheckAuthenticationCodeParams.apply)
              .flatMap(queryAsync)
          case x: AuthorizationState.AuthorizationStateWaitPassword => // TODO: use password hint
            authCallback.providePassword(x.password_hint)
              .map(functions.CheckAuthenticationPasswordParams.apply)
              .flatMap(queryAsync)
          case _: AuthorizationState.AuthorizationStateReady =>
            loggedInSignal.complete(())
          case _: AuthorizationState.AuthorizationStateClosed =>
            closedSignal.complete(())
          case x => IO.println(s"Got update: ${x}") // TODO: remove it
      }
      .as(())

  private def queryStream: Stream[IO, Unit] =
    topic.subscribeUnbounded
      .filter(_.asObject.map(_.contains("@extra")).getOrElse(false))
      .evalMap { j =>
        j.hcursor
          .downField("@extra")
          .as[Int]
          .map { extraId =>
            for {
              m <- queryMap.get
              _ <- m.get(extraId) match
                case Some(sig) => sig.complete(j).as(())
                case None      => IO.unit
            } yield ()
          }
          .getOrElse(IO.unit)
      }

  private def closeMainLoop: IO[Unit] = closedSignal.complete(()).as(())

object TDClient:
  def default(
      authCallback: AuthCallback[IO],
      timeout: Double = 10.0
  ): IO[ResourceIO[TDClient]] =
    def createClientIO(internal_client: TDJsonIO[IO]): IO[TDClient] = for {
      clientId <- internal_client.td_create_client_id
      queryMap <- Ref[IO].of(Map.empty[Int, Deferred[IO, Json]])
      randomGen <- Random.scalaUtilRandom[IO]
      topic <- Topic[IO, Json]
      loggedInSignal <- Deferred[IO, Unit]
      closedSignal <- Deferred[IO, Unit]
    } yield TDClient(
      internal_client = internal_client,
      clientId = clientId,
      timeout = timeout,
      queryMap = queryMap,
      randomGen = randomGen,
      topic = topic,
      loggedInSignal = loggedInSignal,
      closedSignal = closedSignal
    )
    for {
      internal_client <- IO(TDJsonIO.default[IO])
      client <- createClientIO(internal_client)
    } yield {
      client.mainLoopStream
        .concurrently(client.queryStream)
        .concurrently(client.authStream(authCallback))
        .compile
        .drain
        .background
        .flatMap { _ => Resource.eval(client.loggedInSignal.get) }
        .map { _ => client }
    }
