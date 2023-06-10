import io.github.ablearthy.tl.{types, functions}
import io.github.ablearthy.tl.functions.encoders._
import io.github.ablearthy.tl.types.decoders._

import io.github.ablearthy.tdjson4s.{TDClientManager, TDClient}

import cats.effect._
import cats.syntax.all._


object Main extends IOApp.Simple {

  def runClient(client: TDClient[IO]): IO[Unit] =
    for {
      me <- client
        .queryAsync(functions.GetMeParams())
        .map(_.toOption)
        .map(_.get)
      _ <- client.updateStream
        .collect { case x: types.Update.UpdateNewMessage => x }
        .map(_.message)
        .filter(msg => msg.sender_id match
          case types.MessageSender.MessageSenderChat(_) => false
          case types.MessageSender.MessageSenderUser(u) => u != me.id)
        .evalTap(x => IO.println(s"New message: $x"))
        .evalTap(msg =>
          client.queryAsync(
            functions.SendMessageParams(
              chat_id = msg.chat_id,
              message_thread_id = msg.message_thread_id,
              reply_to_message_id = msg.id,
              input_message_content = {
                msg.content match
                  case types.MessageContent.MessageText(text, webpage) =>
                    types.InputMessageContent.InputMessageText(
                      text,
                      disable_web_page_preview = false,
                      clear_draft = true
                    )
                  case _ =>
                    types.InputMessageContent.InputMessageText(
                      types
                        .FormattedText("unsupported content", Vector.empty),
                      disable_web_page_preview = false,
                      clear_draft = true
                    )
              },
              reply_markup = msg.reply_markup
            )
          )
        )
        .compile
        .drain
    } yield ()

  override def run: IO[Unit] =
    for {
      cm <- TDClientManager.default
      _ <- cm.execute(functions.SetLogVerbosityLevelParams(0))
      _ <- cm
        .spawnClient(
          "mybot",
          123123,
          "<API HASH>",
          botToken = Some("<BOT TOKEN>")
        )
        .use(runClient)
    } yield ()
}
