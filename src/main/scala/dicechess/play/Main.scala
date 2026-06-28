package dicechess.play

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.server.{
  AnonMintLimiter,
  BotAuth,
  BotEvents,
  BotRoutes,
  Challenges,
  Cors,
  GameRegistry,
  HealthRoutes,
  Lobby,
  LobbyRoutes,
  PlayRoutes
}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = port"8080"
  private val version = sys.env.getOrElse("APP_VERSION", "dev")

  def run: IO[Unit] =
    for
      registry   <- GameRegistry.create()
      botAuth    <- BotAuth.fromEnv
      botEvents  <- BotEvents.create
      challenges <- Challenges.create(botEvents, registry)
      mintLimit  <- AnonMintLimiter.create()
      lobby      <- Lobby.create(registry)
      cors       <- Cors.fromEnv
      // The seek-sweeper is scoped to the server: it runs while the server runs and is cancelled with it, so a failure
      // surfaces instead of being silently dropped by a detached fiber.
      _ <- lobby
        .sweeper()
        .background
        .surround:
          EmberServerBuilder
            .default[IO]
            .withHost(host)
            .withPort(port)
            .withHttpWebSocketApp(wsb =>
              cors(
                (HealthRoutes(version) <+> PlayRoutes(registry, wsb) <+> LobbyRoutes(lobby) <+> BotRoutes(
                  botAuth,
                  challenges,
                  botEvents,
                  registry,
                  mintLimit
                )).orNotFound
              )
            )
            .build
            .useForever
    yield ()
