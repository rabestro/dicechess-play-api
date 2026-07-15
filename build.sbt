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
val Http4sVersion          = "0.23.30"
val CirceVersion           = "0.14.10"
val LogbackVersion         = "1.5.37"
val Http4sJdkClientVersion = "0.10.0"
val MunitVersion           = "1.3.4"
val MunitCatsEffectVersion = "2.1.0"
// Persistence stack — kept in lockstep with dicechess-analytics so both backends
// share one operational profile (same driver, migrations, test harness).
val DoobieVersion             = "1.0.0-RC9"
val FlywayVersion             = "12.10.0"
val PostgresDriverVersion     = "42.7.7"
val TestcontainersVersion     = "0.43.0"
val TestcontainersJavaVersion = "1.21.3"
val DockerJavaVersion         = "3.7.1"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "dicechess-play-api",
    // The packaged launcher (the Docker ENTRYPOINT) must be told the entrypoint explicitly.
    Compile / mainClass := Some("dicechess.play.Main"),
    libraryDependencies ++= Seq(
      // Game rules: the engine is the single source of truth (resolved from GitHub Packages)
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,
      // Effect system + streaming/concurrency primitives (Ref, Queue, Topic)
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"    % Fs2Version,
      // HTTP / WebSocket server + JSON
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion, // analytics ingest handoff (outbox deliverer)
      "org.http4s" %% "http4s-dsl"          % Http4sVersion,
      "org.http4s" %% "http4s-circe"        % Http4sVersion,
      "io.circe"   %% "circe-core"          % CirceVersion,
      "io.circe"   %% "circe-generic"       % CirceVersion,
      "io.circe"   %% "circe-parser"        % CirceVersion,
      // Persistence: warm game snapshots in Postgres (schema `play`), Flyway migrations
      "org.tpolecat"  %% "doobie-core"                % DoobieVersion,
      "org.tpolecat"  %% "doobie-hikari"              % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres"            % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres-circe"      % DoobieVersion,
      "org.flywaydb"   % "flyway-database-postgresql" % FlywayVersion,
      "org.postgresql" % "postgresql"                 % PostgresDriverVersion,
      // Logging backend for Ember
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      // Testing
      "org.scalameta" %% "munit"                  % MunitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect"      % MunitCatsEffectVersion % Test,
      "org.http4s"    %% "http4s-jdk-http-client" % Http4sJdkClientVersion % Test,
      // Real PostgreSQL in tests via testcontainers (same harness as dicechess-analytics)
      "com.dimafeng" %% "testcontainers-scala-munit"      % TestcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % TestcontainersVersion % Test,
      // The scala wrapper pins testcontainers-java 1.20.x with docker-java 3.4.x, which
      // speaks Docker API 1.32 — rejected by Docker 29+ daemons (min 1.41). Force newer.
      "org.testcontainers"     % "testcontainers"                % TestcontainersJavaVersion % Test,
      "org.testcontainers"     % "postgresql"                    % TestcontainersJavaVersion % Test,
      "com.github.docker-java" % "docker-java-api"               % DockerJavaVersion         % Test,
      "com.github.docker-java" % "docker-java-transport-zerodep" % DockerJavaVersion         % Test
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
    Test / fork           := true,
    // docker-java defaults to Docker API 1.32, which Docker 29+ daemons reject (min 1.41);
    // pin a modern version for the testcontainers client (same fix as dicechess-analytics).
    Test / javaOptions += "-Dapi.version=1.43"
  )
