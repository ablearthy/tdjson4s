package io.github.ablearthy.tdjson4s

import io.github.ablearthy.tdjson.{TDJson, LogMessageHandler}
import cats.effect.{IO, Sync}

trait TDJsonIO[F[_]]:
  def td_create_client_id: F[Int]
  def td_receive(timeout: Double): F[String]
  def td_send(clientId: Int, request: String): F[Unit]
  def td_execute(request: String): F[String]
  def td_set_log_message_callback(
      maxVerbosityLevel: Int,
      handler: LogMessageHandler
  ): F[Unit]

object TDJsonIO:
  def default[F[_]: Sync]: TDJsonIO[F] =
    val tdjson = new TDJson()
    new TDJsonIO[F]:
      def td_create_client_id: F[Int] =
        Sync[F].blocking(tdjson.td_create_client_id)
      def td_receive(timeout: Double): F[String] =
        Sync[F].blocking(tdjson.td_receive(timeout))
      def td_send(clientId: Int, request: String): F[Unit] =
        Sync[F].blocking(tdjson.td_send(clientId, request))
      def td_execute(request: String): F[String] =
        Sync[F].blocking(tdjson.td_execute(request))
      def td_set_log_message_callback(
          maxVerbosityLevel: Int,
          handler: LogMessageHandler
      ): F[Unit] =
        Sync[F].blocking(
          tdjson.td_set_log_message_callback(maxVerbosityLevel, handler)
        )
