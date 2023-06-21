package io.github.ablearthy.tdjson4s.util

import fs2.{Stream, Pipe}

import io.github.ablearthy.tl.{types, functions}
import io.github.ablearthy.tdjson4s.TDClient
import io.github.ablearthy.tl.types.decoders._
import io.github.ablearthy.tl.functions.encoders._

import cats.Functor
import cats.syntax.all._

object StreamUtil:
  final def incoming[F[_]](using
      client: TDClient[F]
  )(using Functor[F]): Pipe[F, types.Message, types.Message] =
    in =>
      for {
        me <- Stream.eval(
          client.queryAsync(functions.GetMeParams()).map(_.toOption.get)
        )
        ret <- in.filter(_.sender_id match
          case types.MessageSender.MessageSenderChat(_) => true
          case types.MessageSender.MessageSenderUser(u) => u != me.id
        )
      } yield ret

  final def outcoming[F[_]](using
      client: TDClient[F]
  )(using Functor[F]): Pipe[F, types.Message, types.Message] =
    in =>
      for {
        me <- Stream.eval(
          client.queryAsync(functions.GetMeParams()).map(_.toOption.get)
        )
        ret <- in.filter(_.sender_id match
          case types.MessageSender.MessageSenderChat(_) => false
          case types.MessageSender.MessageSenderUser(u) => u == me.id
        )
      } yield ret
