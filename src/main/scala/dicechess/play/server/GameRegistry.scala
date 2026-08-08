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
    store: GameStore,
    resolveNicknames: List[String] => IO[Map[String, String]]
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

  /** How many games `principal` is actually playing right now — the only capacity count in the server (#189).
    *
    * It is **derived**, never accumulated: a separate counter can leak a slot when a game dies in an unexpected way,
    * and a leaked slot locks a bot out of every future game while failing silently, which is worse than the timeouts
    * per-bot capacity exists to prevent. Here there is nothing to repair — the index is rebuilt from live rooms and a
    * room deregisters itself when its result resolves, so a wrong count cannot outlive the room that caused it.
    *
    * A just-ended room can linger until the registry evicts it, hence the `hasEnded` filter (the same one
    * `GET /bot/games` applies). The one shape that does hold a slot is a genuinely unfinished game — a clockless room
    * with an idle seat can deadlock forever (see the testing notes on idle seats). That is not a miscount, it is a real
    * stuck game, and every path that seats bots automatically imposes a clock.
    */
  def activeGamesFor(principal: Principal): IO[Int] =
    gamesFor(principal).flatMap(_.traverse((_, room) => room.hasEnded)).map(_.count(!_))

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
      // Resolved here rather than inside the room: a room emits a snapshot on every move, so the seat faces must be
      // decided once and carried. Doing it in the registry also means no caller of `create` has to know about it —
      // direct games, lobby accepts, catalog games, bot challenges and ladder pairings all get named seats for free.
      names <- resolveNicknames(players.values.map(_.externalId).toList)
      made  <- GameRoom.create(
        players,
        dice,
        displayNames = names,
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
    store.loadActive.flatMap: snapshots =>
      // A snapshot does not persist display names, so they are resolved again on boot — otherwise a restart would leave
      // every live game's opponents anonymous for the rest of its life. ONE query covering every resumed game's seats,
      // not one per game: the same reason `createRoom` resolves before the room exists instead of letting the room look
      // names up. The shared map is safe because a name is keyed by external id, not by game.
      val seatIds = snapshots.flatMap((_, snapshot) => snapshot.players.values.map(_.externalId)).distinct
      for
        names    <- resolveNicknames(seatIds)
        restored <- snapshots.traverse: (id, snapshot) =>
          DiceSource
            .fromHexSeed(snapshot.serverSeed)
            .flatTraverse: dice =>
              GameRoom.restore(
                snapshot,
                dice,
                displayNames = names,
                disconnectGrace = disconnectGrace,
                persist = store.save(id, _)
              )
            .map(id -> _)
        failures  = restored.collect { case (id, Left(error)) => id -> error }
        successes = restored.collect { case (id, Right(room)) => (id, room) }
        _ <- failures.traverse_((id, error) => Console[IO].errorln(s"[play][resume] game ${id.value} skipped: $error"))
        _ <- successes.traverse_((id, room) => register(id, room))
        _ <- successes.traverse_((_, room) => room.start)
      yield successes.size

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

  /** `resolveNicknames` turns external ids into display names for the seats — `UserStore.nicknamesByExternalId` in
    * production. Passed as a function rather than the whole store (the `upsertOnLogin`/`freshNickname` precedent) so
    * the registry gains no dependency on the accounts trait, and so the default is honestly "no names": in-memory mode
    * has no accounts, and every human renders anonymous exactly as before #194.
    */
  def create(
      disconnectGrace: FiniteDuration = GameRoom.DefaultDisconnectGrace,
      store: GameStore = GameStore.noop,
      resolveNicknames: List[String] => IO[Map[String, String]] = _ => IO.pure(Map.empty)
  ): IO[GameRegistry] =
    (Ref.of[IO, Map[GameId, GameRoom]](Map.empty), Ref.of[IO, Map[Principal, Set[GameId]]](Map.empty))
      .mapN(GameRegistry(_, _, disconnectGrace, store, resolveNicknames))

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
