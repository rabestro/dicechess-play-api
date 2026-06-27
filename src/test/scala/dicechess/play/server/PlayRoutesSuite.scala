package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.engine.search.{BotRegistry, SearchAlgorithm}
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.wire.Codecs.given
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.Uri
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient

import scala.concurrent.duration.*

class PlayRoutesSuite extends munit.CatsEffectSuite:

  private def greedy: SearchAlgorithm = BotRegistry.getAlgorithm("greedy").get

  /** An Ember server on an ephemeral port, yielding its registry and ws:// base URI. */
  private def server: Resource[IO, (GameRegistry, Uri)] =
    for
      registry <- Resource.eval(GameRegistry.create)
      srv      <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb).orNotFound)
        .build
      base = Uri.unsafeFromString(s"ws://127.0.0.1:${srv.address.getPort}")
    yield (registry, base)

  test("two WebSocket clients play a full game to a terminal"):
    val resources =
      for
        (registry, base) <- server
        ws               <- Resource.eval(JdkWSClient.simple[IO])
      yield (registry, base, ws)

    resources.use: bundle =>
      val (registry, base, ws) = bundle
      registry
        .create(Principal.Guest("white"), Principal.Guest("black"))
        .flatMap:
          case Left(error)    => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right((id, _)) =>
            val whiteUri = base / "games" / id.value / "ws" +? ("seat" -> "white")
            val blackUri = base / "games" / id.value / "ws" +? ("seat" -> "black")
            val play     = (
              ws.connectHighLevel(WSRequest(whiteUri)).use(playSeat(_, Seat.White)),
              ws.connectHighLevel(WSRequest(blackUri)).use(playSeat(_, Seat.Black))
            ).parTupled
            play
              .timeoutTo(30.seconds, IO.raiseError(RuntimeException("no terminal over the wire")))
              .map:
                case (whiteEnded, blackEnded) =>
                  assert(whiteEnded && blackEnded, "both clients should observe GameEnded")

  /** Drive one seat over the wire with the greedy bot; complete when GameEnded arrives. */
  private def playSeat(conn: WSConnectionHighLevel[IO], seat: Seat): IO[Boolean] =
    Ref
      .of[IO, Long](-1L)
      .flatMap: handled =>
        conn.receiveStream
          .evalMap(frame => handle(conn, seat, handled, frame))
          .takeThrough(ended => !ended)
          .compile
          .lastOrError

  private def handle(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      frame: WSFrame
  ): IO[Boolean] =
    frame match
      case WSFrame.Text(txt, _) =>
        decode[GameEvent](txt) match
          case Right(GameEvent.GameEnded(_, _)) => IO.pure(true)
          case Right(event)                     => maybeMove(conn, seat, handled, event).as(false)
          case Left(_)                          => IO.pure(false)
      case _ => IO.pure(false)

  private def maybeMove(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      event: GameEvent
  ): IO[Unit] =
    turnFor(seat, event) match
      case None                  => IO.unit
      case Some((version, dfen)) =>
        handled
          .modify(last => if version > last then (version, true) else (last, false))
          .flatMap: fresh =>
            if fresh then submitMove(conn, dfen) else IO.unit

  private def submitMove(conn: WSConnectionHighLevel[IO], dfen: String): IO[Unit] =
    EngineOps.parse(dfen) match
      case Left(_)      => IO.unit
      case Right(state) =>
        greedy.findBestMove(state) match
          case None      => IO.unit
          case Some(seq) =>
            val command: GameCommand = GameCommand.SubmitTurn(seq.moves.map(EngineOps.toUci))
            conn.send(WSFrame.Text(command.asJson.noSpaces))

  private def turnFor(seat: Seat, event: GameEvent): Option[(Long, String)] = event match
    case GameEvent.DiceRolled(v, s, _, dfen) if s == seat                     => Some((v, dfen))
    case GameEvent.Snapshot(v, ps) if ps.dicePending && ps.activeSeat == seat => Some((v, ps.dfen))
    case _                                                                    => None
