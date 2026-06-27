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
import org.http4s.Method.*
import org.http4s.{Status, Uri}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}

import scala.concurrent.duration.*

class PlayRoutesSuite extends munit.CatsEffectSuite:

  // The dice source is a fresh CSPRNG-seeded commit-reveal, so greedy-vs-greedy game length is
  // not deterministic — a passive line can drag to the draw cap. Bound the wire test by having
  // White resign once it has played a few real turns, so it always reaches a terminal quickly.
  private val WhiteTurnCap = 8

  private def greedy: SearchAlgorithm =
    BotRegistry.getAlgorithm("greedy").getOrElse(sys.error("greedy bot not registered"))

  /** An Ember server on an ephemeral port; yields the bound port. */
  private def server: Resource[IO, Int] =
    for
      registry <- Resource.eval(GameRegistry.create)
      srv      <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        // Ember's default graceful-shutdown drain is 30s; bound it so the resource releases promptly
        // even if a WebSocket close handshake races with shutdown at the end of the test.
        .withShutdownTimeout(1.second)
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb).orNotFound)
        .build
    yield srv.address.getPort

  test("create over HTTP, then two WebSocket clients play to a terminal"):
    val resources =
      for
        port <- server
        http <- Resource.eval(JdkHttpClient.simple[IO])
        ws   <- Resource.eval(JdkWSClient.simple[IO])
      yield (port, http, ws)

    resources.use: bundle =>
      val (port, http, ws) = bundle
      val httpBase         = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      val wsBase           = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created  <- http.expect[CreatedGame](POST(CreateGame("white", "black"), httpBase / "games"))
        snapshot <- http.expect[PublicGameState](httpBase / "games" / created.gameId)
        // Don't assert the active seat: the opening roll may have no legal move and auto-pass to Black.
        _        = assertEquals(snapshot.status, GameStatus.Active)
        _        = assert(created.commit.nonEmpty, "the dice commitment must be published at creation")
        whiteUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.White))
        blackUri = wsBase / "games" / created.gameId / "ws" +? ("token" -> tokenOf(created, Seat.Black))
        ended <- (
          ws.connectHighLevel(WSRequest(whiteUri)).use(playSeat(_, Seat.White)),
          ws.connectHighLevel(WSRequest(blackUri)).use(playSeat(_, Seat.Black))
        ).parTupled
          .timeoutTo(20.seconds, IO.raiseError(RuntimeException("no terminal over the wire")))
      yield assert(ended._1 && ended._2, "both clients should observe GameEnded")

  test("POST /games rejects a malformed body with 400, not 500"):
    val resources =
      for
        port <- server
        http <- Resource.eval(JdkHttpClient.simple[IO])
      yield (port, http)

    resources.use: (port, http) =>
      val games = Uri.unsafeFromString(s"http://127.0.0.1:$port") / "games"
      for
        missingFields <- http.status(POST(io.circe.Json.obj(), games))
        notJson       <- http.status(POST("not json", games))
      yield
        assertEquals(missingFields, Status.BadRequest)
        assertEquals(notJson, Status.BadRequest)

  test("a WebSocket upgrade with an invalid join token is forbidden"):
    val resources =
      for
        port <- server
        http <- Resource.eval(JdkHttpClient.simple[IO])
      yield (port, http)

    resources.use: (port, http) =>
      val httpBase = Uri.unsafeFromString(s"http://127.0.0.1:$port")
      for
        created <- http.expect[CreatedGame](POST(CreateGame("white", "black"), httpBase / "games"))
        // A plain GET (no upgrade) with a bogus token still exercises the auth gate before the upgrade.
        wsPath = httpBase / "games" / created.gameId / "ws"
        status <- http.status(GET(wsPath +? ("token" -> "not-a-real-token")))
      yield assertEquals(status, Status.Forbidden)

  private def tokenOf(created: CreatedGame, seat: Seat): String =
    created.tokens.find(_.seat == seat).map(_.token).getOrElse(sys.error(s"no join token for $seat"))

  /** Drive one seat over the wire with the greedy bot; complete when GameEnded arrives. */
  private def playSeat(conn: WSConnectionHighLevel[IO], seat: Seat): IO[Boolean] =
    (Ref.of[IO, Long](-1L), Ref.of[IO, Int](0)).flatMapN: (handled, turns) =>
      conn.receiveStream
        .evalMap(frame => handle(conn, seat, handled, turns, frame))
        .takeThrough(ended => !ended)
        .compile
        .lastOrError

  private def handle(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      turns: Ref[IO, Int],
      frame: WSFrame
  ): IO[Boolean] =
    frame match
      case WSFrame.Text(txt, _) =>
        decode[GameEvent](txt) match
          case Right(GameEvent.GameEnded(_, _)) => IO.pure(true)
          case Right(event)                     => maybeAct(conn, seat, handled, turns, event).as(false)
          case Left(_)                          => IO.pure(false)
      case _ => IO.pure(false)

  private def maybeAct(
      conn: WSConnectionHighLevel[IO],
      seat: Seat,
      handled: Ref[IO, Long],
      turns: Ref[IO, Int],
      event: GameEvent
  ): IO[Unit] =
    turnFor(seat, event) match
      case None                  => IO.unit
      case Some((version, dfen)) =>
        handled
          .modify(last => if version > last then (version, true) else (last, false))
          .flatMap: fresh =>
            if !fresh then IO.unit
            else
              turns
                .updateAndGet(_ + 1)
                .flatMap: n =>
                  if seat == Seat.White && n > WhiteTurnCap then resign(conn)
                  else move(conn, dfen)

  private def move(conn: WSConnectionHighLevel[IO], dfen: String): IO[Unit] =
    EngineOps.parse(dfen) match
      case Left(_)      => resign(conn)
      case Right(state) =>
        greedy.findBestMove(state) match
          // No legal move: the server auto-passes the turn, so just wait — don't resign.
          case None      => IO.unit
          case Some(seq) => send(conn, GameCommand.SubmitTurn(seq.moves.map(EngineOps.toUci)))

  private def resign(conn: WSConnectionHighLevel[IO]): IO[Unit] = send(conn, GameCommand.Resign)

  private def send(conn: WSConnectionHighLevel[IO], command: GameCommand): IO[Unit] =
    conn.send(WSFrame.Text((command: GameCommand).asJson.noSpaces))

  private def turnFor(seat: Seat, event: GameEvent): Option[(Long, String)] = event match
    case GameEvent.DiceRolled(v, s, _, dfen) if s == seat                     => Some((v, dfen))
    case GameEvent.Snapshot(v, ps) if ps.dicePending && ps.activeSeat == seat => Some((v, ps.dfen))
    case _                                                                    => None
