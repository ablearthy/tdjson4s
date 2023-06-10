package io.github.ablearthy.tdjson4s

import io.github.ablearthy.tl.{types, functions}

import cats.effect.IO
import cats.effect.std.Console

import cats.Monad
import cats.syntax.all._

trait AuthCallback[F[_]]:
  def onWaitTdlibParameters: F[functions.SetTdlibParametersParams]
  def onWaitPhoneNumber: F[
    functions.CheckAuthenticationBotTokenParams |
      functions.SetAuthenticationPhoneNumberParams
  ]
  def onWaitOtherDeviceConfirmation(link: String): F[Unit]
  def onWaitEmailAddress: F[String]
  def onWaitEmailCode: F[types.EmailAddressAuthentication]
  def onWaitCode(info: types.AuthenticationCodeInfo): F[String]
  def onWaitRegistration(
      tos: types.TermsOfService
  ): F[functions.RegisterUserParams]
  def onWaitPassword(hint: String): F[String]
  def onReady: F[Unit]

object AuthCallback:
  def default[F[_]: Console: Monad](
      params: functions.SetTdlibParametersParams
  ): AuthCallback[F] =
    new AuthCallback[F]:
      override def onWaitTdlibParameters
          : F[functions.SetTdlibParametersParams] =
        Monad[F].pure(params)

      override def onWaitPhoneNumber: F[
        functions.CheckAuthenticationBotTokenParams |
          functions.SetAuthenticationPhoneNumberParams
      ] =
        Console[F].print("Enter the phone number: ") *> Console[F].readLine.map(
          phone =>
            functions.SetAuthenticationPhoneNumberParams(
              phone_number = phone,
              settings = None
            )
        )

      override def onWaitOtherDeviceConfirmation(link: String): F[Unit] = ???

      override def onWaitEmailAddress: F[String] =
        Console[F].print("Enter email address: ") *> Console[F].readLine

      override def onWaitEmailCode: F[types.EmailAddressAuthentication] =
        Console[F].print(
          "Enter email address authentication code: "
        ) *> Console[F].readLine.map(code =>
          types.EmailAddressAuthentication.EmailAddressAuthenticationCode(code)
        )

      override def onWaitCode(info: types.AuthenticationCodeInfo): F[String] =
        Console[F].print("Enter auth code: ") *> Console[F].readLine

      override def onWaitRegistration(
          tos: types.TermsOfService
      ): F[functions.RegisterUserParams] =
        for {
          _ <- Console[F].print("Enter your first name: ")
          firstName <- Console[F].readLine
          _ <- Console[F].print("Enter your last name: ")
          lastName <- Console[F].readLine
        } yield functions.RegisterUserParams(
          first_name = firstName,
          last_name = lastName
        )

      override def onWaitPassword(hint: String): F[String] =
        Console[F]
          .print(s"Enter the password (hint: `$hint`): ") *> Console[F].readLine

      override def onReady: F[Unit] =
        Console[F].println("Successfully logged in!")
