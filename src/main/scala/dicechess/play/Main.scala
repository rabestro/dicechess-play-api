package dicechess.play

import cats.effect.{IO, IOApp, Resource}
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
import dicechess.play.store.{GameStore, PgGameStore}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = port"8080"
  private val version = sys.env.getOrElse("APP_VERSION", "dev")

  // Persistence is opt-in by env: with PLAY_DB_URL set, games snapshot into Postgres (schema `play`) and live games
  // are resumed on boot; without it the server runs in-memory exactly as before (games die with the process).
  private def storeResource: Resource[IO, GameStore] =
    PgGameStore.configFromEnv match
      case Some(config) => PgGameStore.resource(config)
      case None         => Resource.pure(GameStore.noop)

  def run: IO[Unit] = storeResource.use(serve)

  private def serve(store: GameStore): IO[Unit] =
    for
      registry   <- GameRegistry.create(store = store)
      resumed    <- registry.resume
      _          <- IO.println(s"[play] resumed $resumed live game(s)").whenA(resumed > 0)
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
