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
    rated: Option[Boolean] = None,
    // Ties two CRN mirror games together (#101): same two participants, colours swapped, identical dice sequence.
    // `None` for every non-ladder game — most games have no pair, so unlike `rated` there is no "resolve to a
    // definite value" story; this stays `Option` all the way through (Session included), not just at rest.
    pairingId: Option[String] = None,
    // The specific partner game's id (#115) — lets `GameRegistry.resume` rebuild the "has my partner ended yet"
    // reveal-eligibility check after a restart, when it can no longer rely on the in-memory closure built at
    // creation. `pairingId` alone isn't enough for that: it's a shared correlation key, not a pointer.
    partnerGameId: Option[String] = None
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
):
  /** The pure-math view of this state, as `Glicko2.update` consumes and produces it. */
  def glicko: dicechess.play.rating.Glicko =
    dicechess.play.rating.Glicko(rating = glickoRating, deviation = glickoRd, volatility = glickoVol)

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

  /** Every registered bot currently opted into the rating ladder — the pairing scheduler's candidate pool (#102). */
  def onLadderBots: IO[List[Principal.Bot]]

  /** Open a registered bot to human catalog games, or close it (ADR-0014). `false` if no such registered identity. */
  def setOpenToHumans(team: String, name: String, open: Boolean): IO[Boolean]

  /** Set or clear a registered bot's catalog description (ADR-0014). `false` if no such registered identity. */
  def setDescription(team: String, name: String, description: Option[String]): IO[Boolean]

  /** Every registered bot currently open to human catalog games — the catalog's candidate pool (ADR-0014). */
  def openToHumansBots: IO[List[Principal.Bot]]

object BotStore:
  /** In-memory mode (no `PLAY_DB_URL`): registration works for the process's lifetime — durability, like game
    * persistence, is what the database adds. Two refs: identity by token hash (as before), and rating state keyed by
    * `(team, name)` so it survives a token rotation (which changes the hash but not the identity).
    */
  def inMemory: IO[BotStore] =
    (
      cats.effect.Ref.of[IO, Map[String, Principal.Bot]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), BotRating]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), (Boolean, Option[String])]](Map.empty)
    ).mapN { (byHash, ratings, catalog) =>
      new BotStore:
        def register(team: String, name: String, tokenHash: String): IO[Boolean] =
          byHash
            .modify { bots =>
              if bots.values.exists(b => b.team == team && b.name == name) then (bots, false)
              else (bots.updated(tokenHash, Principal.Bot(team, name)), true)
            }
            .flatTap { claimed =>
              (ratings.update(_.updated((team, name), BotRating.initial)) *>
                catalog.update(_.updated((team, name), (false, None)))).whenA(claimed)
            }

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

        def onLadderBots: IO[List[Principal.Bot]] =
          ratings.get.map(_.toList.collect { case ((team, name), r) if r.onLadder => Principal.Bot(team, name) })

        def setOpenToHumans(team: String, name: String, open: Boolean): IO[Boolean] =
          catalog.modify { current =>
            current.get((team, name)) match
              case Some((_, desc)) => (current.updated((team, name), (open, desc)), true)
              case None            => (current, false)
          }

        def setDescription(team: String, name: String, description: Option[String]): IO[Boolean] =
          catalog.modify { current =>
            current.get((team, name)) match
              case Some((open, _)) => (current.updated((team, name), (open, description)), true)
              case None            => (current, false)
          }

        def openToHumansBots: IO[List[Principal.Bot]] =
          catalog.get.map(_.toList.collect { case ((team, name), (open, _)) if open => Principal.Bot(team, name) })
    }

/** A registered bot's verified webhook (F.2, #104): rows exist only after the ownership handshake succeeded, so
  * `verifiedAt` is total. `secret` is the per-bot HMAC key the server signs outbound deliveries with — readable by
  * design (it signs, it does not authenticate into play-api); see the V7 migration comment.
  */
final case class BotWebhook(team: String, name: String, url: String, secret: String, verifiedAt: java.time.Instant)

/** Persistence seam for webhook registrations (F.2, #104). One webhook per bot identity; `put` replaces (re-register
  * rotates the URL and secret together). Deliveries re-read the row per turn, so a delete or re-register takes effect
  * mid-game, not at the next game.
  */
trait WebhookStore:
  def put(webhook: BotWebhook): IO[Unit]
  def get(team: String, name: String): IO[Option[BotWebhook]]

  /** Remove the registration. False when the bot had none. */
  def delete(team: String, name: String): IO[Boolean]

object WebhookStore:
  /** In-memory mode (no `PLAY_DB_URL`): registrations last for the process's lifetime, matching `BotStore.inMemory` —
    * the identities these webhooks belong to die with the process too.
    */
  def inMemory: IO[WebhookStore] =
    cats.effect.Ref.of[IO, Map[(String, String), BotWebhook]](Map.empty).map { hooks =>
      new WebhookStore:
        def put(webhook: BotWebhook): IO[Unit] =
          hooks.update(_.updated((webhook.team, webhook.name), webhook))
        def get(team: String, name: String): IO[Option[BotWebhook]] =
          hooks.get.map(_.get((team, name)))
        def delete(team: String, name: String): IO[Boolean] =
          hooks.modify(m => (m.removed((team, name)), m.contains((team, name))))
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

/** A finished game, as recorded in the queryable `game_results` projection (#98). */
final case class GameResultRow(
    gameId: GameId,
    whiteExternalId: String,
    blackExternalId: String,
    // White-POV: 1 white won, -1 black won, 0 draw — same convention as the analytics ingest wire
    // (`PlaysiteIngest.resultOf`). Option, not a bare Int: `GameResult` has no "unknown" case today, but the schema
    // allows one for forward-compat.
    result: Option[Int],
    termination: String,
    rated: Boolean,
    timeControl: String,
    serverSeed: String,
    pairingId: Option[String],
    finishedAt: java.time.Instant
)

/** Persistence seam for the queryable `game_results` projection (#98): the games table's own snapshot is opaque JSONB
  * (only `status` is indexed), so the ladder scheduler and rating batch need this to enumerate finished games by
  * participant / result / rated / pairing without decoding JSON. One row per finished game, written once (in the same
  * transaction as the terminal snapshot save, see `PgGameStore.save`) and never updated afterward — with one
  * bookkeeping exception, the `rating_applied_at` stamp (V6, see [[RatingStore]]) — Postgres only, since
  * `GameStore.noop`'s in-memory mode has nothing to project.
  */
trait GameResultsStore:
  /** Most recent results `externalId` played (either seat), newest first. */
  def recentResultsFor(externalId: String, limit: Int = GameResultsStore.DefaultRecentLimit): IO[List[GameResultRow]]

  /** Every rated game finished strictly after `since` — an offline batch's cursor for incremental rating updates.
    *
    * '''Not a gap-free cursor:''' `finished_at` defaults to Postgres's `now()`, which freezes at transaction START, not
    * commit. Two concurrent `save` calls can therefore commit out of start order — if a transaction starting at T1
    * commits AFTER one starting later at T2 > T1, a batch that has already advanced its cursor to T2 will never see the
    * T1 row on a later poll (`finished_at > T2` excludes it forever, even though it only just became visible). The
    * realistic window is bounded by how long a `save` transaction can stay open (seconds, not minutes), so callers
    * should poll with `since` set a little further back than their last cursor (a few seconds' overlap) and deduplicate
    * by `GameResultRow.gameId`, which is already a natural idempotency key. A stronger guarantee (a monotonic sequence
    * cursor, or a claim-based queue like `play.outbox`) is a larger, separate design question than this projection's
    * own scope.
    */
  def finishedRatedSince(since: java.time.Instant): IO[List[GameResultRow]]

  /** The (up to two) games sharing this CRN pairing id (#101), for pentanomial scoring. */
  def pairFor(pairingId: String): IO[List[GameResultRow]]

object GameResultsStore:
  /** `recentResultsFor`'s default page size — bounds a prolific bot's history to a reasonable page rather than its
    * entire lifetime.
    */
  val DefaultRecentLimit: Int = 50

/** Persistence seam for the Glicko-2 rating batch (#119). The work queue is claim-based: a rated `game_results` row
  * with no `rating_applied_at` stamp is pending, and applying it stamps it in the SAME transaction as the rating write
  * — chosen over a `finished_at` cursor because rating updates are NOT idempotent (a cursor with overlap would re-apply
  * games; a cursor without overlap loses games to the commit-order race documented on
  * `GameResultsStore.finishedRatedSince`). Postgres only, like [[GameResultsStore]].
  */
trait RatingStore:
  /** Rated games not yet applied to any rating, oldest `finished_at` first — the head of the claim queue. */
  def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]

  /** Atomically write both bots' post-game Glicko state AND stamp the game as applied — one transaction, so a crash
    * between the two can neither double-apply a game nor lose one side's update.
    */
  def applyRatingUpdate(
      gameId: GameId,
      white: Principal.Bot,
      whiteGlicko: dicechess.play.rating.Glicko,
      black: Principal.Bot,
      blackGlicko: dicechess.play.rating.Glicko
  ): IO[Unit]

  /** Stamp a game as applied WITHOUT touching any rating — for games the batch must skip permanently (a non-bot or
    * unregistered participant, a missing result, self-play): left unstamped they would clog the head of the queue
    * forever.
    */
  def markRatingApplied(gameId: GameId): IO[Unit]

/** A bot's rated, decided W-D-L record from `game_results` (undecided/casual games are excluded — this is the ladder
  * record, not a lifetime activity counter).
  */
final case class ResultTally(wins: Int, draws: Int, losses: Int):
  def games: Int = wins + draws + losses

object ResultTally:
  val Empty: ResultTally = ResultTally(0, 0, 0)

/** One public leaderboard row's worth of state: the bot, its rating, and its rated record. */
final case class LeaderboardEntry(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    tally: ResultTally
)

/** Read seam for the public leaderboard/profile API (D.2, #103) — Postgres only, like [[GameResultsStore]]: the queries
  * join `bots` with aggregates over `game_results`, neither of which exists in the in-memory mode (the leaderboard
  * endpoints are simply not mounted without persistence).
  */
trait LeaderboardStore:
  /** Every registered bot whose rating has converged (`glicko_rd <= maxRd`), best rating first, with its rated W-D-L
    * record. Provisional bots (above the threshold) are the caller's to hide — which this filter does — per the ladder
    * policy (#119): counted internally, invisible publicly until the deviation settles.
    */
  def leaderboard(maxRd: Double): IO[List[LeaderboardEntry]]

  /** The rated, decided W-D-L record of one participant (either seat), for the profile endpoint. */
  def resultTallyFor(externalId: String): IO[ResultTally]
