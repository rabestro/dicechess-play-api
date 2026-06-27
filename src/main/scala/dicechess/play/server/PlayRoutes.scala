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
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder}

final case class CreateGame(white: String, black: String) derives Codec.AsObject
final case class CreatedGame(gameId: String, commit: String) derives Codec.AsObject

object PlayRoutes:

  private given QueryParamDecoder[Seat] =
    QueryParamDecoder[String].emap: raw =>
      val name = raw.toLowerCase.capitalize
      scala.util.Try(Seat.valueOf(name)).toEither.left.map(_ => ParseFailure(s"invalid seat: $raw", raw))

  private object SeatParam extends QueryParamDecoderMatcher[Seat]("seat")

  def apply(registry: GameRegistry, wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "games" =>
        for
          body <- req.as[CreateGame]
          made <- registry.create(Principal.Guest(body.white), Principal.Guest(body.black))
          resp <- made match
            case Left(error)       => BadRequest(error)
            case Right((id, room)) => room.diceCommit.flatMap(c => Created(CreatedGame(id.value, c)))
        yield resp

      case GET -> Root / "games" / id =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) => room.snapshot.flatMap(Ok(_))

      case GET -> Root / "games" / id / "ws" :? SeatParam(seat) =>
        registry
          .get(GameId(id))
          .flatMap:
            case None       => NotFound()
            case Some(room) => wsb.build(toClient(room), fromClient(room, seat))

  private def toClient(room: GameRoom): Stream[IO, WebSocketFrame] =
    room.subscribe.map(event => WebSocketFrame.Text(event.asJson.noSpaces))

  private def fromClient(room: GameRoom, seat: Seat): Pipe[IO, WebSocketFrame, Unit] =
    _.evalMap:
      // Spectators receive events but cannot submit commands.
      case WebSocketFrame.Text(txt, _) if seat.side.isDefined =>
        decode[GameCommand](txt).fold(_ => IO.unit, room.submit(seat, _))
      case _ => IO.unit
