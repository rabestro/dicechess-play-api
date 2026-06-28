package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.*
import dicechess.play.game.GameRoom
import dicechess.play.wire.Codecs.given
import fs2.{Pipe, Stream}
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes

import scala.concurrent.duration.*

final case class CreateGame(white: String, black: String, timeControl: Option[TimeControl] = None)
    derives Codec.AsObject
final case class SeatToken(seat: Seat, token: String) derives Codec.AsObject

/** The created game plus a per-seat join token: the creator distributes each token to the player who should hold that
  * seat, and the WebSocket upgrade authorizes the seat from the token (never a trusted `?seat=` param).
  */
final case class CreatedGame(gameId: String, commit: String, tokens: List[SeatToken]) derives Codec.AsObject

object PlayRoutes:

  /** WebSocket heartbeat interval — comfortably under Ember's 60s read-idle timeout so the client's pong keeps the
    * connection alive between game events.
    */
  val DefaultKeepAlive: FiniteDuration = 25.seconds

  private object TokenParam extends OptionalQueryParamDecoderMatcher[String]("token")

  def apply(
      registry: GameRegistry,
      wsb: WebSocketBuilder2[IO],
      keepAlive: FiniteDuration = DefaultKeepAlive
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "games" =>
        // attemptAs (not as): a malformed body is the client's fault, so answer 400, not 500.
        req
          .attemptAs[CreateGame]
          .value
          .flatMap:
            case Left(failure) => BadRequest(failure.message)
            case Right(body)   =>
              registry
                .create(
                  Principal.Guest(body.white),
                  Principal.Guest(body.black),
                  body.timeControl.getOrElse(TimeControl.Unlimited)
                )
                .flatMap:
                  case Left(error)       => BadRequest(error)
                  case Right((id, room)) =>
                    val tokens = room.joinTokens.toList.map((seat, token) => SeatToken(seat, token))
                    room.diceCommit.flatMap(c => Created(CreatedGame(id.value, c, tokens)))

      case GET -> Root / "games" / id =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) => room.snapshot.flatMap(Ok(_))

      // The seat is resolved from a verified join token; a tokenless connection is a read-only spectator.
      case GET -> Root / "games" / id / "ws" :? TokenParam(token) =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) =>
              token match
                case None    => wsb.build(clientFrames(room, keepAlive), fromClient(room, Seat.Spectator))
                case Some(t) =>
                  room.seatFor(t) match
                    case None       => Forbidden()
                    case Some(seat) => wsb.build(clientFrames(room, keepAlive), fromClient(room, seat))

  /** Frames pushed to a client: the room's event feed merged with periodic WebSocket pings. The browser auto-replies
    * with a pong, and that inbound frame resets the server's read-idle timeout — so a quiet but live game (a player
    * thinking, or the opening dice auto-passing) is not dropped at the 60s idle deadline. Halts with the event feed
    * (which completes on the terminal event), so the socket still closes when the game ends.
    */
  private[server] def clientFrames(room: GameRoom, keepAlive: FiniteDuration): Stream[IO, WebSocketFrame] =
    val events     = room.subscribe.map(event => WebSocketFrame.Text(event.asJson.noSpaces))
    val keepAlives = Stream.awakeEvery[IO](keepAlive).as(WebSocketFrame.Ping())
    events.mergeHaltL(keepAlives)

  private def fromClient(room: GameRoom, seat: Seat): Pipe[IO, WebSocketFrame, Unit] =
    in =>
      val commands = in.evalMap {
        // Spectators receive events but cannot submit commands.
        case WebSocketFrame.Text(txt, _) if seat.side.isDefined =>
          decode[GameCommand](txt).fold(_ => IO.unit, room.submit(seat, _))
        case _ => IO.unit
      }
      // Hold the seat's presence for the life of the socket. When it drops, the room starts the reconnect grace
      // timer (a no-op for spectators) — so a refresh or a brief blip no longer resigns the game outright.
      Stream.resource(room.connection(seat)) >> commands
