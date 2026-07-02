package dicechess.play.store

import cats.effect.IO
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
    createdAtEpochMs: Option[Long] = None
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
