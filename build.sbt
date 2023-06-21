val scala3Version = "3.2.2"

val circeVersion = "0.14.3"

ThisBuild / organization := "io.github.ablearthy"

lazy val root = project
  .in(file("."))
  .settings(
    name := "tdjson4s",
    version := "1.8.10a",

    scalaVersion := scala3Version,

    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.4.5",
   
    libraryDependencies += "io.github.ablearthy" %% "td-types" % "1.8.10-2",
    libraryDependencies += "io.github.ablearthy" %% "tdjson-bind-core" % "1.8.10-1",
    libraryDependencies += "io.github.ablearthy" % "tdjson-bind-native" % "1.8.10-1",

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),

    libraryDependencies += "co.fs2" %% "fs2-core" % "3.6.1",

    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,

    scalacOptions ++= Seq("-Xmax-inlines", "5000"),
  )
