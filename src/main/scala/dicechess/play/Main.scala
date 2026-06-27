package dicechess.play

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.server.{BotAuth, BotRoutes, GameRegistry, PlayRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host = host"0.0.0.0"
  private val port = port"8080"

  def run: IO[Unit] =
    for
      registry <- GameRegistry.create
      botAuth  <- BotAuth.fromEnv
      _        <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(wsb => (PlayRoutes(registry, wsb) <+> BotRoutes(botAuth)).orNotFound)
        .build
        .useForever
    yield ()
