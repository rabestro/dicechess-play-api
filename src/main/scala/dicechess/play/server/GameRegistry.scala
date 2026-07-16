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
    */
  def create(
      white: Principal,
      black: Principal,
      timeControl: TimeControl = TimeControl.Unlimited,
      requestedRated: Boolean = false
  ): IO[Either[String, (GameId, GameRoom)]] =
    DiceSource.newCommitReveal().flatMap { dice =>
      createRoom(
        Map(Seat.White -> white, Seat.Black -> black),
        dice,
        timeControl,
        rated = GameRegistry.isRated(white, black, requestedRated)
      )
    }

  /** Create a CRN-paired ladder matchup (#101): two games between the same two bots, colours swapped, sharing one
    * server seed AND one fixed (white, black) client-seed pair — so `roll(ply, seedWhite, seedBlack)` computes the
    * IDENTICAL dice sequence in both games regardless of which bot is seated where, and luck cancels over the pair. A
    * naive colour swap alone does NOT achieve this: client seeds are normally participant-bound (`Session.seedFor`
    * falls back to the seat's own player id), so swapping seats without also fixing the seeds would swap the seed
    * *arguments* to `roll` and change the dice. The fix bypasses the public `SubmitSeed` flow entirely — the seeds are
    * set once, here, at creation.
    *
    * Both games are rated exactly when [[GameRegistry.isRated]] agrees (the ladder only ever pairs registered,
    * non-anonymous bots, so today this is always true — but the policy stays the single source of truth rather than
    * being assumed here too).
    *
    * '''Known gap — do not wire this into live play yet (tracked in #115):''' both games share one secret, and
    * `GameEnded` reveals it (`seed`/`clientSeeds`, unconditionally, in the clear) the instant EITHER game ends — public
    * reveal, since `GET /games/{id}` exposes it too. Once the faster game ends, that reveal hands anyone (not just the
    * two bots) everything needed to compute every remaining roll of the slower game:
    * `roll(ply, clientSeedWhite, clientSeedBlack)` is a pure function of exactly the now-public values. The two games
    * must not both reveal independently at their own natural end — the reveal needs to wait for BOTH games in the pair
    * to finish. No caller exists yet (that's #102's job), so the blast radius today is zero; do not call this from a
    * real scheduler before that's fixed.
    */
  def createMirroredPair(
      botA: Principal.Bot,
      botB: Principal.Bot,
      timeControl: TimeControl
  ): IO[Either[String, GameRegistry.MirroredPair]] =
    val rated = GameRegistry.isRated(botA, botB, requested = true)
    for
      dice      <- DiceSource.newCommitReveal()
      seedWhite <- randomSeed
      seedBlack <- randomSeed
      pairingId <- IO(java.util.UUID.randomUUID().toString)
      fixedSeeds = Map(Seat.White -> seedWhite, Seat.Black -> seedBlack)
      gameAWhite <- createRoom(
        Map(Seat.White -> botA, Seat.Black -> botB),
        dice,
        timeControl,
        rated,
        Some(pairingId),
        fixedSeeds
      )
      gameBWhite <- createRoom(
        Map(Seat.White -> botB, Seat.Black -> botA),
        dice,
        timeControl,
        rated,
        Some(pairingId),
        fixedSeeds
      )
    // No orphaned first game on a "partial" failure: createRoom's only error path is EngineOps.parse(initialDfen),
    // and both calls above use the same default (always-valid) InitialDfen — parsing is a pure function of that
    // string alone, unaffected by players/seeds/pairingId, so the two calls always succeed or fail together. If a
    // future caller ever threads a custom initial position through here, that invariant would need revisiting.
    yield (gameAWhite, gameBWhite) match
      case (Right((idA, _)), Right((idB, _))) => Right(GameRegistry.MirroredPair(pairingId, idA, idB))
      case (Left(error), _)                   => Left(error)
      case (_, Left(error))                   => Left(error)

  /** Shared room-creation seam behind both `create` and `createMirroredPair`: mint an id, build the room, register it
    * once live. `presetClientSeeds` bypasses the normal `SubmitSeed` gate (empty for an ordinary game).
    */
  private def createRoom(
      players: Map[Seat, Principal],
      dice: DiceSource,
      timeControl: TimeControl,
      rated: Boolean,
      pairingId: Option[String] = None,
      presetClientSeeds: Map[Seat, String] = Map.empty
  ): IO[Either[String, (GameId, GameRoom)]] =
    for
      id   <- GameId.random
      made <- GameRoom.create(
        players,
        dice,
        disconnectGrace = disconnectGrace,
        timeControl = timeControl,
        rated = rated,
        pairingId = pairingId,
        presetClientSeeds = presetClientSeeds,
        persist = store.save(id, _)
      )
      result <- made.traverse(room => register(id, room) *> room.start.as((id, room)))
    yield result

  /** A fresh 16-byte (128-bit) dice-seed contribution, hex-encoded — same shape as a real client's `SubmitSeed`, but
    * minted server-side once and reused for both mirror games.
    */
  private def randomSeed: IO[String] = IO:
    val bytes = new Array[Byte](16)
    java.security.SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

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
    room.seating.flatMap: seats =>
      val players = seats.values.toList
      rooms.update(_.updated(id, room)) *>
        byPlayer.update(index =>
          players.foldLeft(index)((acc, p) => acc.updated(p, acc.getOrElse(p, Set.empty) + id))
        ) *>
        // Evict the room (and its index entries) once its game ends, so neither map grows without bound.
        (room.result *> deregister(id, players)).start.void

  private def deregister(id: GameId, players: List[Principal]): IO[Unit] =
    rooms.update(_.removed(id)) *>
      byPlayer.update(index =>
        players.foldLeft(index): (acc, p) =>
          val rest = acc.getOrElse(p, Set.empty) - id
          if rest.isEmpty then acc.removed(p) else acc.updated(p, rest)
      )

object GameRegistry:

  /** The result of `createMirroredPair`: two games tied together by a shared `pairingId` for downstream CRN scoring
    * (pentanomial: the pair is scored as one unit, not two independent games). `gameAWhite` seats `botA` White / `botB`
    * Black; `gameBWhite` is the mirror.
    */
  final case class MirroredPair(pairingId: String, gameAWhite: GameId, gameBWhite: GameId)

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
