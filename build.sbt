ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.3"

ThisBuild / description := "Authoritative real-time server for Dice Chess (human-vs-human + Bot API)."

// The engine artifact lives in GitHub Packages, which requires authentication
// even for public packages (read:packages scope).
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"

// Credentials for that resolver. `credentials` is an sbt *setting*, evaluated on every
// load — even for offline tasks — so we keep it free of network calls: GitHub Packages
// validates only the token (the password) and accepts any non-empty username. CI exports
// GITHUB_TOKEN; locally we read it from the gh CLI, which returns the token from the OS
// keychain without touching the network (works offline; the token never lands in a file).
def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion = "1.6.1"
val CatsEffectVersion      = "3.6.3"
val Fs2Version             = "3.12.0"
val MunitVersion           = "1.3.0"
val MunitCatsEffectVersion = "2.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "dicechess-play-api",
    libraryDependencies ++= Seq(
      // Game rules: the engine is the single source of truth (resolved from GitHub Packages)
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,
      // Effect system + streaming/concurrency primitives (Ref, Queue, Topic)
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"    % Fs2Version,
      // Testing
      "org.scalameta" %% "munit"             % MunitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test
    ),
    scalacOptions ++= Seq(
      "-Werror",
      "-Wunused:all",
      "-deprecation",
      "-feature",
      "-explain"
    ),
    coverageExcludedFiles := ".*Main\\.scala",
    // Raised to a real threshold once 3a-core lands; the scaffold has no logic to cover yet.
    coverageFailOnMinimum := false,
    Test / fork           := true
  )
