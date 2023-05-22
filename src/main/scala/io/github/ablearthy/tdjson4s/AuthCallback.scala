package io.github.ablearthy.tdjson4s

import io.github.ablearthy.tl.functions.{
  SetTdlibParametersParams,
  SetAuthenticationPhoneNumberParams
}

import io.github.ablearthy.tl.types.EmailAddressAuthentication

import cats.effect.IO
import io.github.ablearthy.tl.types.EmailAddressAuthentication.EmailAddressAuthenticationCode

trait AuthCallback[F[_]]:
  def provideTdlibParameters: F[SetTdlibParametersParams]
  def providePhoneNumber: F[SetAuthenticationPhoneNumberParams]
  def provideEmailAddress: F[String]
  def provideEmailCode: F[EmailAddressAuthentication]
  def provideCode: F[String]
  def providePassword(hint: String): F[String]

object AuthCallback:
  def default(params: SetTdlibParametersParams): AuthCallback[IO] =
    new AuthCallback[IO]:
      override def provideEmailAddress: IO[String] =
        for {
          _ <- IO.print("Enter email address: ")
          emailAddress <- IO.readLine
        } yield emailAddress

      override def provideCode: IO[String] =
        for {
          _ <- IO.print("Enter auth code: ")
          code <- IO.readLine
        } yield code

      override def provideEmailCode: IO[EmailAddressAuthentication] =
        // TODO: support GoogleId/AppleId
        for {
          _ <- IO.print("Enter email address authentication code: ")
          code <- IO.readLine
        } yield EmailAddressAuthentication.EmailAddressAuthenticationCode(code)

      override def providePassword(hint: String): IO[String] =
        for {
          _ <- IO.print(s"Enter the password (hint: `$hint`): ")
          password <- IO.readLine
        } yield password

      override def provideTdlibParameters: IO[SetTdlibParametersParams] =
        IO.pure(params)

      override def providePhoneNumber: IO[SetAuthenticationPhoneNumberParams] =
        for {
          _ <- IO.print("Enter the phone number: ")
          phone <- IO.readLine
        } yield SetAuthenticationPhoneNumberParams(
          phone_number = phone,
          settings = None
        )
