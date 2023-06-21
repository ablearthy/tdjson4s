package io.github.ablearthy.tdjson4s

import cats.syntax.all._

import io.github.ablearthy.tl.codecs.TLFunction
import io.github.ablearthy.tl.types.{Update, AuthorizationState, decoders}
import io.github.ablearthy.tl.types.decoders._
import io.github.ablearthy.tl.types

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax._

import fs2.{Stream, RaiseThrowable}
import io.circe.{Json, JsonObject}

trait TDClient[F[_]: RaiseThrowable]:
  def send(query: JsonObject): F[Int]
  def mainStream: Stream[F, JsonObject]
  def queryAsync[In: Encoder.AsObject, Out](
      query: In
  )(using In <:< TLFunction[Out], Decoder[Out]): F[Either[types.Error, Out]]

  final def updateStream: Stream[F, Update] =
    mainStream
      .filter(!_.contains("@extra"))
      .map(o => decoders.updateDecoder.decodeJson(o.asJson))
      .rethrow

  private final def responseStream: Stream[F, JsonObject] =
    mainStream
      .filter(_.contains("@extra"))

  final def send[In: Encoder.AsObject](query: In): F[Int] =
    send(query.asJsonObject)
