package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.GameRoom
import dicechess.play.store.GameStore

import scala.concurrent.duration.*

/** In-memory registry of live game rooms (one authoritative node, for now). Rooms snapshot themselves into the
  * `GameStore` on every event, and `resume` rebuilds them on boot — so live games survive a restart or deploy.
  */
final class GameRegistry private (
    rooms: Ref[IO, Map[GameId, GameRoom]],
    byPlayer: Ref[IO, Map[Principal, Set[GameId]]],
    disconnectGrace: FiniteDuration,
    store: GameStore
):

  def get(id: GameId): IO[Option[GameRoom]] = rooms.get.map(_.get(id))

  /** Every live room, for the public games listing — one entry per active game on this node, so the map stays small.
    * Per-caller lookups should use [[gamesFor]] instead (indexed); this is for the whole-lobby view.
    */
  def list: IO[List[(GameId, GameRoom)]] = rooms.get.map(_.toList)

  /** The live games `principal` is seated in — an index lookup plus O(own games), not a scan over every room on the
    * node: bot discovery polls this on a timer, so its cost must not grow with everyone else's games.
    */
  def gamesFor(principal: Principal): IO[List[(GameId, GameRoom)]] =
    (byPlayer.get.map(_.getOrElse(principal, Set.empty)), rooms.get).mapN: (ids, all) =>
      ids.toList.sortBy(_.value).flatMap(id => all.get(id).map(id -> _))

  /** Create and start a room for two players. Dice come from a fresh commit-reveal source whose server seed is
    * committed before any client connects; each player then folds in its own post-commit seed (see GameRoom's gate).
    * Errors (e.g. a bad initial position) are returned as a Left, never thrown.
    *
    * `requestedRated` is only a hint: the game is actually rated iff [[GameRegistry.isRated]] agrees, so an anonymous
    * participant on either side silently forces a casual game regardless of what was requested.
    *
    * `ladder` flags a game the ladder scheduler is starting, as opposed to a direct challenge or a catalog game — the
    * only marker in `game_results` distinguishing that, which is what keeps a casual/challenge timeout from ever
    * tripping ladder auto-park (`RatingBatch.shouldPark`, #150).
    */
  def create(
      white: Principal,
      black: Principal,
      timeControl: TimeControl = TimeControl.Unlimited,
      requestedRated: Boolean = false,
      ladder: Boolean = false
  ): IO[Either[String, (GameId, GameRoom)]] =
    (GameId.random, DiceSource.newCommitReveal()).flatMapN { (id, dice) =>
      createRoom(
        id,
        Map(Seat.White -> white, Seat.Black -> black),
        dice,
        timeControl,
        rated = GameRegistry.isRated(white, black, requestedRated),
        ladder = ladder
      )
    }

  /** Shared room-creation seam behind `create`: build the room, register it, start it. */
  private def createRoom(
      id: GameId,
      players: Map[Seat, Principal],
      dice: DiceSource,
      timeControl: TimeControl,
      rated: Boolean,
      ladder: Boolean
  ): IO[Either[String, (GameId, GameRoom)]] =
    for
      made <- GameRoom.create(
        players,
        dice,
        disconnectGrace = disconnectGrace,
        timeControl = timeControl,
        rated = rated,
        ladder = ladder,
        persist = store.save(id, _)
      )
      result <- made.traverse(room => register(id, room) *> room.start.as((id, room)))
    yield result

  /** Rebuild rooms for every game that was live when the process stopped; returns how many were revived. A snapshot
    * that fails to restore is logged and skipped — one corrupt row must not take the server down.
    */
  def resume: IO[Int] =
    store.loadActive.flatMap { snapshots =>
      snapshots
        .traverse { case (id, snapshot) =>
          DiceSource
            .fromHexSeed(snapshot.serverSeed)
            .flatTraverse(dice =>
              GameRoom.restore(snapshot, dice, disconnectGrace = disconnectGrace, persist = store.save(id, _))
            )
            .map(id -> _)
        }
        .flatMap { restored =>
          val failures  = restored.collect { case (id, Left(error)) => id -> error }
          val successes = restored.collect { case (id, Right(room)) => (id, room) }
          failures.traverse_((id, error) => Console[IO].errorln(s"[play][resume] game ${id.value} skipped: $error")) *>
            successes.traverse_((id, room) => register(id, room)) *>
            successes.traverse_((_, room) => room.start).as(successes.size)
        }
    }

  private def register(id: GameId, room: GameRoom): IO[Unit] =
    room.seating.flatMap: seats =>
      val players = seats.values.toList
      rooms.update(_.updated(id, room)) *>
        byPlayer.update(index =>
          players.foldLeft(index)((acc, p) => acc.updated(p, acc.getOrElse(p, Set.empty) + id))
        ) *>
        (room.result *> deregister(id, players)).start.void

  private def deregister(id: GameId, players: List[Principal]): IO[Unit] =
    rooms.update(_.removed(id)) *>
      byPlayer.update(index =>
        players.foldLeft(index): (acc, p) =>
          val rest = acc.getOrElse(p, Set.empty) - id
          if rest.isEmpty then acc.removed(p) else acc.updated(p, rest)
      )

object GameRegistry:

  def create(
      disconnectGrace: FiniteDuration = GameRoom.DefaultDisconnectGrace,
      store: GameStore = GameStore.noop
  ): IO[GameRegistry] =
    (Ref.of[IO, Map[GameId, GameRoom]](Map.empty), Ref.of[IO, Map[Principal, Set[GameId]]](Map.empty))
      .mapN(GameRegistry(_, _, disconnectGrace, store))

  /** Whether a game between these participants should count toward rating, given the caller's request. Anonymous
    * participants — bot accounts on the `anon` team (`POST /bot/anon`), and human guests (there is no registered-human
    * identity yet) — can't sustain a meaningful rating, so a game touching either side is always casual regardless of
    * what was requested. Decided once, at creation; the result is carried verbatim into every snapshot afterward
    * (`GameSnapshot.rated`), never recomputed mid-game.
    */
  private[server] def isRated(white: Principal, black: Principal, requested: Boolean): Boolean =
    def anonymous(p: Principal): Boolean = p match
      case Principal.Guest(_)     => true
      case Principal.User(_)      => false
      case Principal.Bot(team, _) => team == BotAuth.AnonTeam
    requested && !anonymous(white) && !anonymous(black)
