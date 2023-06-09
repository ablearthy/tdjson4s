package io.github.ablearthy.tdjson4s

import cats.effect.{IO, Ref, Resource, ResourceIO}
import fs2.concurrent.Topic
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse

class TDClientManager(
    private val lastExtraId: Ref[IO, Int],
    private val lastClientId: Ref[IO, Int],
    private val topic: Topic[IO, Json]
) {
  private val client: TDJsonIO[IO] = TDJsonIO.default

  def spawnClient: ResourceIO[TDClient] = ???

}

object TDClientManager {

  private def makeTDClientManager: IO[TDClientManager] =
    for {
      lastExtraId <- Ref[IO].of(0)
      lastClientId <- Ref[IO].of(1)
      topic <- Topic[IO, Json]
    } yield TDClientManager(
      lastExtraId = lastExtraId,
      lastClientId = lastClientId,
      topic = topic
    )
    
  def default: ResourceIO[TDClientManager] =
    for {
      manager <- Resource.eval(makeTDClientManager)
      _ <- Stream
        .repeatEval(manager.client.td_receive(100))
        .filter(!_.isEmpty)
        .map(parse)
        .rethrow
        .through(manager.topic.publish)
        .compile
        .drain
        .background
    } yield manager
}
