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

final case class CreateGame(white: String, black: String) derives Codec.AsObject
final case class SeatToken(seat: Seat, token: String) derives Codec.AsObject

/** The created game plus a per-seat join token: the creator distributes each token to the player who should hold that
  * seat, and the WebSocket upgrade authorizes the seat from the token (never a trusted `?seat=` param).
  */
final case class CreatedGame(gameId: String, commit: String, tokens: List[SeatToken]) derives Codec.AsObject

object PlayRoutes:

  private object TokenParam extends OptionalQueryParamDecoderMatcher[String]("token")

  def apply(registry: GameRegistry, wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
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
                .create(Principal.Guest(body.white), Principal.Guest(body.black))
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
                case None    => wsb.build(toClient(room), fromClient(room, Seat.Spectator))
                case Some(t) =>
                  room.seatFor(t) match
                    case None       => Forbidden()
                    case Some(seat) => wsb.build(toClient(room), fromClient(room, seat))

  private def toClient(room: GameRoom): Stream[IO, WebSocketFrame] =
    room.subscribe.map(event => WebSocketFrame.Text(event.asJson.noSpaces))

  private def fromClient(room: GameRoom, seat: Seat): Pipe[IO, WebSocketFrame, Unit] =
    in =>
      in.evalMap {
        // Spectators receive events but cannot submit commands.
        case WebSocketFrame.Text(txt, _) if seat.side.isDefined =>
          decode[GameCommand](txt).fold(_ => IO.unit, room.submit(seat, _))
        case _ => IO.unit
      }.onFinalize {
        // A player whose socket closes mid-game resigns; a no-op once the game is already over.
        if seat.side.isDefined then room.submit(seat, GameCommand.Resign) else IO.unit
      }
