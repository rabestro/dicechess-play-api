package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.GameRoom
import dicechess.play.store.GameStore

import scala.concurrent.duration.FiniteDuration

/** In-memory registry of live game rooms (one authoritative node, for now). Rooms snapshot themselves into the
  * `GameStore` on every event, and `resume` rebuilds them on boot — so live games survive a restart or deploy.
  */
final class GameRegistry private (
    rooms: Ref[IO, Map[GameId, GameRoom]],
    disconnectGrace: FiniteDuration,
    store: GameStore
):

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
        timeControl = timeControl,
        persist = store.save(id, _)
      )
      result <- made.traverse(room => register(id, room) *> room.start.as((id, room)))
    yield result

  /** Rebuild rooms for every game that was live when the process stopped; returns how many were revived. A snapshot
    * that fails to restore is logged and skipped — one corrupt row must not take the server down.
    */
  def resume: IO[Int] =
    store.loadActive
      .flatMap(_.traverse { case (id, snapshot) =>
        DiceSource
          .fromHexSeed(snapshot.serverSeed)
          .flatTraverse(dice =>
            GameRoom.restore(snapshot, dice, disconnectGrace = disconnectGrace, persist = store.save(id, _))
          )
          .flatMap {
            case Left(error) => Console[IO].errorln(s"[play][resume] game ${id.value} skipped: $error").as(0)
            case Right(room) => register(id, room) *> room.start.as(1)
          }
      })
      .map(_.sum)

  private def register(id: GameId, room: GameRoom): IO[Unit] =
    rooms.update(_.updated(id, room)) *>
      // Evict the room once its game ends, so the map doesn't grow without bound.
      (room.result *> rooms.update(_.removed(id))).start.void

object GameRegistry:
  def create(
      disconnectGrace: FiniteDuration = GameRoom.DefaultDisconnectGrace,
      store: GameStore = GameStore.noop
  ): IO[GameRegistry] =
    Ref.of[IO, Map[GameId, GameRoom]](Map.empty).map(GameRegistry(_, disconnectGrace, store))
