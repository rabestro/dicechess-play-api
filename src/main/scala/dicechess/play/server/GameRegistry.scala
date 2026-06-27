package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.GameRoom

/** In-memory registry of live game rooms (one authoritative node, for now). */
final class GameRegistry private (rooms: Ref[IO, Map[GameId, GameRoom]]):

  def get(id: GameId): IO[Option[GameRoom]] = rooms.get.map(_.get(id))

  /** Create and start a room for two players. Dice come from a fresh commit-reveal source seeded with the players' ids.
    * Errors (e.g. a bad initial position) are returned as a Left, never thrown.
    */
  def create(white: Principal, black: Principal): IO[Either[String, (GameId, GameRoom)]] =
    for
      id     <- GameId.random
      dice   <- DiceSource.newCommitReveal(white.externalId, black.externalId)
      made   <- GameRoom.create(Map(Seat.White -> white, Seat.Black -> black), dice)
      result <- made match
        case Left(error) => IO.pure(Left(error))
        case Right(room) => rooms.update(_.updated(id, room)) *> room.start.as(Right((id, room)))
    yield result

object GameRegistry:
  def create: IO[GameRegistry] =
    Ref.of[IO, Map[GameId, GameRoom]](Map.empty).map(GameRegistry(_))
