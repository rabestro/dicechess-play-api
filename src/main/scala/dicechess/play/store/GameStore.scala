package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}

/** One completed turn, recorded for the analytics handoff: the dice that were rolled and the UCI micro-moves played
  * (empty for a forced pass). `fenAfter` is the position after the turn, so the replay gate can cross-check.
  */
final case class TurnRecord(
    turnNumber: Long,
    activeColor: String, // "w" | "b"
    dice: List[Int],
    moves: List[String],
    fenAfter: String
)

/** A durable snapshot of a game — everything needed to (a) resume the room after a restart and (b) hand the finished
  * game to analytics. Written before each event is broadcast, so any state a player has seen survives a crash.
  *
  * Includes the secrets a live game cannot run without: the per-seat join tokens (players must be able to reconnect
  * after a restart) and the dice server seed (the room must keep rolling the committed sequence, and reveal it at the
  * end). The store is private-side infrastructure — these never leave the server.
  */
final case class GameSnapshot(
    version: Long,
    dfen: String, // carries the pending dice pool while a turn is in flight
    players: Map[Seat, Principal],
    seatTokens: Map[Seat, String],
    serverSeed: String, // hex; SHA-256 of its bytes is the published commit
    clientSeeds: Map[Seat, String],
    started: Boolean,
    ply: Long,
    pending: Boolean,
    status: GameStatus,
    timeControl: TimeControl,
    remainingMs: Map[Seat, Long],
    lastRoll: List[Int],
    turns: Vector[TurnRecord],
    // Wall-clock creation time — carried into the analytics handoff as `started_at`. Optional so snapshots written
    // before this field existed still decode.
    createdAtEpochMs: Option[Long] = None,
    // Decided once at creation from both participants' identities (see GameRegistry.isRated); never recomputed.
    // Option, NOT a bare `Boolean = false` default: circe's generic derivation only falls back to `None` for a
    // MISSING key on an Option field (decodeOption's own special case) — a defaulted non-Option field still fails
    // to decode with "Missing required field" when the key is absent, which would have discarded every
    // pre-existing active game as corrupt on the next resume. Callers resolve this to a definite Boolean
    // (`GameRoom.restore`), defaulting a missing value to `false` — correct, since it predates the concept.
    rated: Option[Boolean] = None
):
  def ended: Boolean = status match
    case GameStatus.Ended(_) => true
    case GameStatus.Active   => false

object GameSnapshot:
  // Private storage codecs (NOT the public wire): reuse the protocol's shapes where they exist, and encode
  // seat-keyed maps by case name so a snapshot stays readable in psql.
  import dicechess.play.wire.Codecs.given

  private given KeyEncoder[Seat] = KeyEncoder.encodeKeyString.contramap(_.toString)
  private given KeyDecoder[Seat] = KeyDecoder.instance(s => Seat.values.find(_.toString == s))

  given Codec[TurnRecord]   = deriveCodec
  given Codec[GameSnapshot] = deriveCodec

/** Persistence seam for game snapshots. `save` upserts by game id; `loadActive` returns every game to resume on boot.
  */
trait GameStore:
  def save(id: GameId, snapshot: GameSnapshot): IO[Unit]
  def loadActive: IO[List[(GameId, GameSnapshot)]]

object GameStore:
  /** In-memory mode: no persistence (local dev / tests without a database). Games die with the process. */
  val noop: GameStore = new GameStore:
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = IO.unit
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

/** A registered bot's rating-ladder state (#100): Glicko-2 parameters plus whether it has opted into the ladder, and a
  * forward-looking owner slot for when human accounts arrive (always `None` today — nothing populates it yet; adding
  * the column now avoids a later migration).
  */
final case class BotRating(
    glickoRating: Double,
    glickoRd: Double,
    glickoVol: Double,
    onLadder: Boolean,
    ownerExternalId: Option[String]
)

object BotRating:
  /** A freshly registered bot's starting state: Glickman's suggested defaults for a new, unrated player, opted out of
    * the ladder until explicitly turned on.
    */
  val initial: BotRating = BotRating(glickoRating = 1500, glickoRd = 350, glickoVol = 0.06, onLadder = false, None)

/** Persistence seam for durable self-service bot identities (#70). Only token *hashes* cross this boundary — hashing
  * (and token minting) is the caller's job, so the store stays a dumb map from hash to identity.
  */
trait BotStore:
  /** Claim `(team, name)` with the given token hash. False when the identity is already taken. */
  def register(team: String, name: String, tokenHash: String): IO[Boolean]

  /** The registered identity a presented token's hash authenticates as, if any. */
  def authenticate(tokenHash: String): IO[Option[Principal.Bot]]

  /** Swap the identity's token hash (rotation: the old token stops authenticating immediately). False when no such
    * registered identity exists — the caller distinguishes registered bots from static/anonymous ones by this.
    */
  def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]

  /** The registered bot's current rating-ladder state, or `None` if no such registered identity exists. */
  def ratingOf(team: String, name: String): IO[Option[BotRating]]

  /** Opt a registered bot in or out of the rating ladder, returning its resulting state. `None` if no such registered
    * identity exists — the caller distinguishes registered bots from static/anonymous ones by this, same as `rotate`.
    */
  def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]]

object BotStore:
  /** In-memory mode (no `PLAY_DB_URL`): registration works for the process's lifetime — durability, like game
    * persistence, is what the database adds. Two refs: identity by token hash (as before), and rating state keyed by
    * `(team, name)` so it survives a token rotation (which changes the hash but not the identity).
    */
  def inMemory: IO[BotStore] =
    (
      cats.effect.Ref.of[IO, Map[String, Principal.Bot]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), BotRating]](Map.empty)
    ).mapN { (byHash, ratings) =>
      new BotStore:
        def register(team: String, name: String, tokenHash: String): IO[Boolean] =
          byHash
            .modify { bots =>
              if bots.values.exists(b => b.team == team && b.name == name) then (bots, false)
              else (bots.updated(tokenHash, Principal.Bot(team, name)), true)
            }
            .flatTap(claimed => ratings.update(_.updated((team, name), BotRating.initial)).whenA(claimed))

        def authenticate(tokenHash: String): IO[Option[Principal.Bot]] = byHash.get.map(_.get(tokenHash))

        def rotate(team: String, name: String, newTokenHash: String): IO[Boolean] =
          byHash.modify { bots =>
            if bots.values.exists(b => b.team == team && b.name == name) then
              val cleared = bots.filterNot((_, b) => b.team == team && b.name == name)
              (cleared.updated(newTokenHash, Principal.Bot(team, name)), true)
            else (bots, false)
          }

        def ratingOf(team: String, name: String): IO[Option[BotRating]] = ratings.get.map(_.get((team, name)))

        def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] =
          ratings.modify { current =>
            current.get((team, name)) match
              case Some(r) =>
                val updated = r.copy(onLadder = onLadder)
                (current.updated((team, name), updated), Some(updated))
              case None => (current, None)
          }
    }

/** An undelivered analytics handoff: the game's `GameIngest` payload plus its retry bookkeeping. */
final case class OutboxRow(gameId: GameId, payload: io.circe.Json, attempts: Int)

/** The deliverer's port onto the outbox (rows are enqueued transactionally by the store itself when a finished game's
  * snapshot is saved). `due` returns undelivered, non-parked rows whose next attempt is due.
  */
trait OutboxStore:
  def due(limit: Int): IO[List[OutboxRow]]
  def markDelivered(gameId: GameId): IO[Unit]
  def markRetry(
      gameId: GameId,
      attempts: Int,
      retryIn: scala.concurrent.duration.FiniteDuration,
      error: String
  ): IO[Unit]

  /** Park a row that will never succeed (a 4xx such as the replay gate's 422) for manual inspection. */
  def markParked(gameId: GameId, error: String): IO[Unit]
