package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.GameRoom

import scala.concurrent.duration.FiniteDuration

/** In-memory registry of live game rooms (one authoritative node, for now). */
final class GameRegistry private (rooms: Ref[IO, Map[GameId, GameRoom]], disconnectGrace: FiniteDuration):

  def get(id: GameId): IO[Option[GameRoom]] = rooms.get.map(_.get(id))

  /** Create and start a room for two players. Dice come from a fresh commit-reveal source whose server seed is
    * committed before any client connects; each player then folds in its own post-commit seed (see GameRoom's gate).
    * Errors (e.g. a bad initial position) are returned as a Left, never thrown.
    */
  def create(
      white: Principal,
      black: Principal,
      timeControl: TimeControl = TimeControl.Unlimited
  ): IO[Either[String, (GameId, GameRoom)]] =
    for
      id   <- GameId.random
      dice <- DiceSource.newCommitReveal()
      made <- GameRoom.create(
        Map(Seat.White -> white, Seat.Black -> black),
        dice,
        disconnectGrace = disconnectGrace,
        timeControl = timeControl
      )
      result <- made match
        case Left(error) => IO.pure(Left(error))
        case Right(room) =>
          for
            _ <- rooms.update(_.updated(id, room))
            // Evict the room once its game ends, so the map doesn't grow without bound.
            _ <- (room.result *> rooms.update(_.removed(id))).start
            _ <- room.start
          yield Right((id, room))
    yield result

object GameRegistry:
  def create(disconnectGrace: FiniteDuration = GameRoom.DefaultDisconnectGrace): IO[GameRegistry] =
    Ref.of[IO, Map[GameId, GameRoom]](Map.empty).map(GameRegistry(_, disconnectGrace))
