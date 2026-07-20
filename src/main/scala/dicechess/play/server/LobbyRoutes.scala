package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Principal, Seat, TimeControl}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

/** Create an open seek. `creator`'s eventual seat (White or Black) is decided at accept time, not here — see
  * `Lobby.accept`.
  */
final case class CreateSeek(creator: String, timeControl: Option[TimeControl] = None) derives Codec.AsObject

/** The created seek's public id plus the creator's capability secret (poll status / cancel with it). */
final case class CreatedSeek(seekId: String, secret: String) derives Codec.AsObject

/** Accept an open seek. `accepter`'s seat (White or Black) is randomly assigned — see `Lobby.accept`. */
final case class AcceptSeek(accepter: String) derives Codec.AsObject

/** A creator's status poll: `matched` false while open; once matched it carries the game id, the creator's seat token,
  * and the seat that token names (randomly assigned at accept time — see `Lobby.accept`).
  */
final case class SeekState(
    matched: Boolean,
    gameId: Option[String],
    token: Option[String],
    seat: Option[Seat]
) derives Codec.AsObject

/** The accept response: the seated game id, the accepter's seat token, and the seat it names (randomly assigned — see
  * `Lobby.accept`).
  */
final case class SeekMatch(gameId: String, token: String, seat: Seat) derives Codec.AsObject

/** Lobby REST (polling): list open seeks, post one, poll its status (creator only, via the secret), accept one, cancel
  * one. A seat token is delivered to each player out-of-band: the accepter here, the creator on its next status poll.
  */
object LobbyRoutes:

  private object SecretParam extends OptionalQueryParamDecoderMatcher[String]("secret")

  def apply(lobby: Lobby): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "lobby" / "seeks" =>
        lobby.list.flatMap(Ok(_))

      case req @ POST -> Root / "lobby" / "seeks" =>
        req
          .attemptAs[CreateSeek]
          .value
          .flatMap:
            case Left(failure) => BadRequest(failure.message)
            case Right(body)   =>
              Principal.guest(body.creator) match
                case Left(err)      => BadRequest(s"creator: $err")
                case Right(creator) =>
                  lobby
                    .create(creator, body.timeControl.getOrElse(TimeControl.Unlimited))
                    .flatMap:
                      case Right((seek, secret)) => Created(CreatedSeek(seek.id, secret))
                      // Guests are uncapped today; the branch exists for the type (the cap applies to bot creators).
                      case Left(Lobby.CreateRejected.TooManyOpenSeeks) => TooManyRequests("too many open seeks")

      case GET -> Root / "lobby" / "seeks" / id :? SecretParam(secret) =>
        secret match
          case None    => Forbidden()
          case Some(s) =>
            lobby
              .status(id, s)
              .flatMap:
                case None                                       => NotFound()
                case Some(Lobby.SeekStatus.Open)                => Ok(SeekState(matched = false, None, None, None))
                case Some(Lobby.SeekStatus.Matched(g, t, seat)) =>
                  Ok(SeekState(matched = true, Some(g), Some(t), Some(seat)))

      case req @ POST -> Root / "lobby" / "seeks" / id / "accept" =>
        req
          .attemptAs[AcceptSeek]
          .value
          .flatMap:
            case Left(failure) => BadRequest(failure.message)
            case Right(body)   =>
              Principal.guest(body.accepter) match
                case Left(err)       => BadRequest(s"accepter: $err")
                case Right(accepter) =>
                  lobby
                    .accept(id, accepter)
                    .flatMap:
                      case Right(m)                           => Created(SeekMatch(m.gameId, m.token, m.seat))
                      case Left(Lobby.Rejected.NotFound)      => NotFound()
                      case Left(Lobby.Rejected.AlreadyTaken)  => Conflict()
                      case Left(Lobby.Rejected.OwnSeek)       => BadRequest("cannot accept your own seek")
                      case Left(Lobby.Rejected.Failed(error)) => BadRequest(error)

      case DELETE -> Root / "lobby" / "seeks" / id :? SecretParam(secret) =>
        secret match
          case None    => Forbidden()
          case Some(s) => lobby.cancel(id, s).flatMap(removed => if removed then NoContent() else NotFound())
