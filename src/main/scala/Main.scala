import io.github.ablearthy.tdjson.{TDJson, LogMessageHandler}
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import io.circe.parser._
import cats.effect.std.Random

import concurrent.duration.DurationInt


import cats.effect._
import cats.syntax.all._

class TDJsonIO private (private val tdjson: TDJson) {
  def td_create_client_id: IO[Int] = IO.blocking(tdjson.td_create_client_id)
  def td_receive(timeout: Double): IO[String] = IO.blocking(tdjson.td_receive(timeout))
  def td_send(clientId: Int, request: String): IO[Unit] = IO.blocking(tdjson.td_send(clientId, request))
  def td_execute(request: String): IO[String] = IO.blocking(tdjson.td_execute(request))
  def td_set_log_message_callback(maxVerbosityLevel: Int, handler: LogMessageHandler): IO[Unit] =
    IO.blocking(tdjson.td_set_log_message_callback(maxVerbosityLevel, handler))
}

object TDJsonIO {
  def apply: TDJsonIO = new TDJsonIO(new TDJson())
}

type RequestId = Int

class TelegramClient private (
  private val tdjson: TDJsonIO, 
  val clientId: Int,
  private val deferredSubscribers: Ref[IO, Map[RequestId, Deferred[IO, String]]],
  private val rand: Random[IO],
  private val updateHandler: (TelegramClient, Json) => IO[Unit],
  val timeout: Double = 10.0
) {
  sendRaw("getOption", JsonObject("name" -> "version".asJson))


  private def sendRaw(message: String): IO[Unit] = tdjson.td_send(clientId, message)
  private def sendRaw(raw: JsonObject): IO[Unit] = sendRaw(raw.asJson.noSpaces)
  private def sendRaw(method: String, raw: JsonObject): IO[Unit] =
    sendRaw(("@type" -> method.asJson) +: raw)

  private def sendRaw(method: String, extra: RequestId, raw: JsonObject): IO[Unit] =
    sendRaw(("@type" -> method.asJson) +: ("@extra" -> extra.asJson) +: raw)

  private def registerDeferred(id: RequestId, d: Deferred[IO, String]): IO[Unit] =
    deferredSubscribers.update(_ + (id -> d))

  def send(method: String, raw: JsonObject): IO[String] =
    for
      id <- rand.nextInt
      d <- Deferred[IO, String]
      _ <- registerDeferred(id, d)
      m <- deferredSubscribers.get
      _ <- sendRaw(method, id, raw)
      resp <- d.get
    yield resp
  
  private def handle: IO[Unit] =
    def internalHandle(result: Json, requestId: RequestId): IO[Unit] =
      deferredSubscribers.get.flatMap { m =>
        m.get(requestId) match
          case None => IO.pure(())
          case Some(deferred) => deferred.complete(result.noSpaces) >> deferredSubscribers.update(_ - requestId)
      }

    tdjson.td_receive(timeout).flatMap { s => 
      if s.isEmpty then
        IO.pure(())
      else
        val res = parse(s).getOrElse(Json.Null)
        val maybeExtra = res.hcursor.downField("@extra").as[RequestId].toOption
        maybeExtra match
          case None => updateHandler(this, res).start.as(())
          case Some(id) => internalHandle(res, id)
    }
}

object TelegramClient {
  def create(updateHandler: (TelegramClient, Json) => IO[Unit]): IO[ResourceIO[TelegramClient]] =
    def createClient: IO[TelegramClient] = 
      val tdjson = TDJsonIO.apply
      for {
        clientId <- tdjson.td_create_client_id
        _ <- tdjson.td_execute("""{"@type": "setLogVerbosityLevel", "new_verbosity_level": 1}""")
        deferredSubscribers <- Ref[IO].of(Map.empty[RequestId, Deferred[IO, String]])
        rand <- Random.scalaUtilRandom[IO]
      } yield new TelegramClient(tdjson, clientId, deferredSubscribers, rand, updateHandler)
    createClient.map {client => 
      client.handle.foreverM.background.map(_ => client)
    }
}

object Main extends IOApp.Simple {
  def run: IO[Unit] =
    TelegramClient.create(updateHandler).flatMap {
      _.use { client =>
        for
          resp <- client.send("getOption", JsonObject.singleton("name", "version".asJson))
          _ <- IO.println(resp)
          _ <- IO.sleep(300.seconds)
        yield ()
      }
    }

  def updateHandler(client: TelegramClient, json: Json): IO[Unit] =
    def auth(authState: String): IO[Unit] =
      authState match
        case "authorizationStateClosed" => IO.pure(())
        case "authorizationStateWaitTdlibParameters" =>
          client.send("setTdlibParameters", JsonObject(
            "database_directory" -> ".".asJson,
            "use_message_database" -> true.asJson,
            "use_secret_chats" -> false.asJson,
            "api_id" -> 94575.asJson,
            "api_hash" -> "a3406de8d171bb422bb6ddf3bbd800e2".asJson,
            "system_language_code" -> "en".asJson,
            "device_model" -> "Automation".asJson,
            "application_version" -> "0.0.1".asJson,
            "enable_storage_optimizer" -> true.asJson
          )).as(())
        case "authorizationStateWaitPhoneNumber" =>
          for
            _ <- IO.print("Enter the phone number: ")
            phoneNumber <- IO.readLine
            _ <- client.send("setAuthenticationPhoneNumber", JsonObject(
              "phone_number" -> phoneNumber.asJson
            ))
          yield ()
        case "authorizationStateWaitEmailAddress" =>
          for
            _ <- IO.print("Enter the email address: ")
            emailAddress <- IO.readLine
            _ <- client.send("setAuthenticationEmailAddress", JsonObject(
              "email_address" -> emailAddress.asJson
            ))
          yield ()
        case "authorizationStateWaitEmailCode" =>
          for
            _ <- IO.print("Enter the email auth code: ")
            code <- IO.readLine
            _ <- client.send("emailAddressAuthenticationCode", JsonObject(
              "code" -> code.asJson
            ))
          yield ()          
        case "authorizationStateWaitCode" =>
          for
            _ <- IO.print("Enter the auth code: ")
            code <- IO.readLine
            _ <- client.send("checkAuthenticationCode", JsonObject(
              "code" -> code.asJson
            ))
          yield ()   
        case "authorizationStateWaitRegistration" =>
          for
            _ <- IO.print("Enter your first name: ")
            firstName <- IO.readLine
            _ <- IO.print("Enter your last name: ")
            lastName <- IO.readLine
            _ <- client.send("registerUser", JsonObject(
              "first_name" -> firstName.asJson,
              "last_name" -> lastName.asJson
            ))
          yield () 
        case "authorizationStateWaitPassword" => 
          for
            _ <- IO.print("Enter the password: ")
            password <- IO.readLine
            _ <- client.send("checkAuthenticationPassword", JsonObject(
              "password" -> password.asJson
            ))
          yield ()   
        
    def handle(updateType: String, json: Json): IO[Unit] =
      updateType match
        case "updateAuthorizationState" =>
          json.hcursor.downField("authorization_state").downField("@type").as[String] match
            case Left(_) => IO.println(s"expected field `authorization_state`: $json")
            case Right(state) => IO.println(s"auth: $state") >> auth(state)
          
        case _: String => IO.println(s"update: $updateType")

    json.hcursor.downField("@type").as[String] match
      case Left(_) => IO.pure(())
      case Right(updateType) => handle(updateType, json)
}