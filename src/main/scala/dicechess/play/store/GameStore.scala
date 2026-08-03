package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, Json, JsonObject, KeyDecoder, KeyEncoder}

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

object TurnRecord:
  /** The snake_case turn shape shared by every history representation that leaves the operational storage codec below:
    * analytics' `PlaysiteIngest.turn` and the play-owned `GameArchive` (#177). One field list, so the two projections
    * cannot silently drift apart — each caller adds only what is specific to it (analytics' own `thinking_time_ms`,
    * always null today).
    */
  private[play] def json(record: TurnRecord): JsonObject =
    JsonObject(
      "turn_number"  -> record.turnNumber.asJson,
      "active_color" -> record.activeColor.asJson,
      "dice"         -> record.dice.asJson,
      "moves"        -> record.moves.asJson,
      "fen_after"    -> record.fenAfter.asJson
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
    // Whether the ladder scheduler started this game (#190) — the only marker distinguishing that from a direct
    // challenge, which is what keeps a casual timeout from ever tripping ladder auto-park. Same `Option` story as
    // `rated` immediately above, for the same reason: a pre-existing row from before this field existed has no
    // key at all, and a defaulted non-`Option` field fails to decode ("Missing required field") rather than
    // falling back, which would discard every active game as corrupt on the next resume. Resolved to a definite
    // `false` in `GameRoom.restore`, exactly like `rated`.
    ladder: Option[Boolean] = None
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

/** One archived game as read back from `play.game_archive` (#177): the sanitized payload `GameArchive.payload` wrote,
  * plus the column recording when it was written — needed by `GET /games/{id}/history` (#178) to answer `finishedAt`,
  * which lives outside the JSONB payload itself.
  */
final case class ArchivedGame(payload: Json, finishedAt: java.time.Instant)

/** One batch of the archive backfill (#199): how far the cursor advanced and what happened to the rows it scanned.
  *
  * `lastId` is the cursor for the NEXT batch — the highest `game_id` this batch looked at, whether or not it could be
  * converted. That is the point: a row that is scanned but legitimately not inserted (an unparseable snapshot, or
  * `GameArchive.payload` returning `None` for an aborted game) must not be re-selected forever, which is exactly what a
  * plain `WHERE NOT EXISTS ... LIMIT n` loop would do. `scanned == 0` means the walk is finished.
  */
final case class ArchiveBackfillBatch(lastId: Option[GameId], scanned: Int, inserted: Int, skipped: Int)

/** Read seam for the durable game-history archive (#177): a point lookup by id — there is no listing surface, per
  * `GameArchive`'s own doc. Postgres only, like `GameResultsStore`: `game_archive` doesn't exist without persistence,
  * so `GET /games/{id}/history` (#178) is simply not mounted in that mode, same idiom as the leaderboard.
  */
trait GameArchiveStore:
  def archiveFor(id: GameId): IO[Option[ArchivedGame]]

  /** One keyset-paginated batch of the one-off backfill (#199): ended games that still have a snapshot but no archive
    * row (everything finished before #177 reached production) get the row they would have been written at game end.
    *
    * Ordered by `game_id` and resumed from `after`, NOT re-querying `NOT EXISTS` from the start each time — see
    * [[ArchiveBackfillBatch]] for why a row this cannot convert would otherwise loop forever.
    *
    * The payload comes from `GameArchive.payload`, the very same pure function the live write path uses, so a
    * backfilled row is indistinguishable from a natively written one. `finished_at`, however, is passed EXPLICITLY from
    * `game_results.finished_at` (falling back to `games.updated_at`): the column's own `DEFAULT now()` is right for a
    * game that just ended and badly wrong for a backfill, and `GET /games/{id}/history` serves that field straight to
    * the replay page.
    */
  def backfillArchive(after: Option[GameId], limit: Int): IO[ArchiveBackfillBatch]

/** What one retention batch removed, and what it deliberately did not (#179).
  *
  * `retainedUnarchived` is the safety valve made visible: an ended, non-aborted game whose history is NOT in
  * `game_archive` is never pruned, because its snapshot is then the only copy of that history — the exact loss #199 had
  * to repair. Such a row would otherwise be deleted silently, so it is counted and logged instead.
  *
  * '''`retainedUnarchived` is only populated on a terminal batch''' — one where nothing was removed, i.e. `!
  * removedAnything`. It is a whole-table aggregate with no `LIMIT`, and the only consumer (`Retention.drain`) reads it
  * exclusively from the last page, so computing it per page would scan the table once per page purely to discard the
  * result. On a page that DID remove rows the field is `0`, which means "not measured", not "none retained" — read it
  * only after the drain has finished.
  */
final case class RetentionSweep(
    outboxDeleted: Int,
    snapshotsDeleted: Int,
    clientReportsDeleted: Int,
    retainedUnarchived: Int
):
  def removedAnything: Boolean = outboxDeleted > 0 || snapshotsDeleted > 0 || clientReportsDeleted > 0

/** Persistence seam for the retention pass (#179): the operational tables (`games` snapshots, delivered `outbox` and
  * `client_reports` rows) stop growing forever once `game_archive` is the durable history record. Postgres only, like
  * the other projections — there is nothing to prune in the in-memory mode.
  *
  * Explicitly NOT pruned, ever: `game_archive` (permanent by contract), `game_results` (the list/rating projection),
  * `bots`, `bot_webhooks`.
  */
trait RetentionStore:
  /** One bounded batch: delivered outbox and client-report rows older than `olderThan`, then the ended snapshots older
    * than it that are safe to drop. Client reports are pruned purely by delivery age — unlike snapshots they carry no
    * archive relationship, so no "history preserved elsewhere" check applies to them. Bounded so a crash mid-pass
    * leaves a consistent state and the next tick simply continues.
    */
  def pruneOnce(olderThan: java.time.Instant, limit: Int): IO[RetentionSweep]

/** A registered bot's rating-ladder state (#100): Glicko-2 parameters plus whether it has opted into the ladder, and a
  * forward-looking owner slot for when human accounts arrive (always `None` today — nothing populates it yet; adding
  * the column now avoids a later migration).
  */
final case class BotRating(
    glickoRating: Double,
    glickoRd: Double,
    glickoVol: Double,
    onLadder: Boolean,
    ownerExternalId: Option[String],
    // Whether a game against a HUMAN counts for rating (#247). Operator-only, unlike `onLadder` right above it —
    // see V15's own comment for why the rated party must never be able to set this.
    ratedForHumans: Boolean = false
):
  /** The pure-math view of this state, as `Glicko2.update` consumes and produces it. */
  def glicko: dicechess.play.rating.Glicko =
    dicechess.play.rating.Glicko(rating = glickoRating, deviation = glickoRd, volatility = glickoVol)

object BotRating:
  /** A freshly registered bot's starting state: Glickman's suggested defaults for a new, unrated player, opted out of
    * the ladder until explicitly turned on.
    */
  val initial: BotRating = BotRating(
    glickoRating = 1500,
    glickoRd = 350,
    glickoVol = 0.06,
    onLadder = false,
    ownerExternalId = None,
    ratedForHumans = false
  )

/** A registered bot's catalog opt-in state (ADR-0014): whether it is advertised to human players and its optional
  * one-line description. Returned by the open/close store methods, read back atomically so the caller sees exactly the
  * persisted state (no separate read a concurrent write could make stale).
  */
final case class BotCatalogState(openToHumans: Boolean, description: Option[String])

/** A registered bot's declared seating capacity (#189), together with the one policy flag that changes how that
  * capacity may be spent. Read as a unit because every decision needs both: how many games the author says the bot can
  * hold, and whether a human might turn up wanting one of those slots.
  */
final case class BotSeatPolicy(bot: Principal.Bot, maxConcurrentGames: Int, openToHumans: Boolean):

  /** How much of the declared capacity the **ladder** may occupy. A bot that is also open to humans keeps one slot
    * free, so a scheduler running every minute cannot make it permanently unplayable by a person — a regression the
    * ladder would otherwise introduce the moment per-bot limits exist.
    *
    * At `maxConcurrentGames = 1` there is nothing to reserve: the floor of 1 lets the ladder take the only slot, and
    * the human path answers "this bot is busy" instead. An explicit refusal is the better half of that trade; a
    * reservation here would instead mean a bot that declared 1 never plays a rated game again.
    */
  def ladderAllowance: Int = if openToHumans then math.max(1, maxConcurrentGames - 1) else maxConcurrentGames

object BotSeatPolicy:

  /** What a bot holds until it says otherwise. Deliberately not "unlimited": the whole point of #189 is that silence
    * must select the conservative policy, since the authors who most need the limit are the ones who never read about
    * it. Raising it is one call to `POST /bot/capacity`.
    */
  val DefaultMaxConcurrentGames: Int = 1

  /** Sanity rail on a self-declared number, matching the `bots_max_concurrent_games_range` check in V12 — it stops one
    * row from claiming the whole ladder, and it is not an opinion about what hardware can do.
    */
  val MaxDeclarableConcurrentGames: Int = 32

  /** Whether a declared value is within the range the `bots` table will accept. Callers must check BEFORE writing: the
    * store surfaces an out-of-range value as a constraint violation, not as a `None`.
    */
  def isDeclarable(maxConcurrentGames: Int): Boolean =
    maxConcurrentGames >= DefaultMaxConcurrentGames && maxConcurrentGames <= MaxDeclarableConcurrentGames

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

  /** The OPERATOR's switch for "games between this bot and a human are rated" (#247). Deliberately not reachable from
    * the `/bot` API: a bot author who could set it would register a weak bot and farm rating off it (V15 spells this
    * out). `None` for an unregistered identity, exactly like `setOnLadder`.
    */
  def setRatedForHumans(team: String, name: String, ratedForHumans: Boolean): IO[Option[BotRating]]

  /** Every registered bot currently opted into the rating ladder, each with its declared seating capacity — the pairing
    * scheduler's candidate pool (#102), read in one query because the scheduler needs the capacity of every candidate
    * before it can pick a pair (#189).
    */
  def onLadderCandidates: IO[List[BotSeatPolicy]]

  /** The registered bot's declared seating capacity, or `None` if no such registered identity exists — which is how
    * every caller distinguishes a registered bot from a static (`PLAY_BOT_TOKENS`) or anonymous one. Those have no row
    * and so no declaration: they are unbounded, and must stay that way (the house bot opposes every quickstart visitor
    * at once).
    */
  def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]]

  /** Declare how many games this bot is willing to hold at once, returning its resulting policy. `None` if no such
    * registered identity, same as the other policy writes. The caller must have checked [[BotSeatPolicy.isDeclarable]]
    * first — an out-of-range value violates the table's check constraint.
    */
  def setMaxConcurrentGames(team: String, name: String, maxConcurrentGames: Int): IO[Option[BotSeatPolicy]]

  /** Open a registered bot to human catalog games, replacing its catalog description in the same write (ADR-0014).
    * `None` if no such registered identity; otherwise the resulting state, read back atomically.
    */
  def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]]

  /** Close a registered bot to human catalog games, leaving its description untouched (ADR-0014). `None` if no such
    * registered identity; otherwise the resulting state.
    */
  def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]]

  /** Every registered bot currently open to human catalog games — the catalog's candidate pool (ADR-0014). */
  def openToHumansBots: IO[List[Principal.Bot]]

object BotStore:
  /** In-memory mode (no `PLAY_DB_URL`): registration works for the process's lifetime — durability, like game
    * persistence, is what the database adds. Four refs: identity by token hash (as before), and rating, catalog and
    * declared-capacity state keyed by `(team, name)` so each survives a token rotation (which changes the hash but not
    * the identity).
    */
  def inMemory: IO[BotStore] =
    (
      cats.effect.Ref.of[IO, Map[String, Principal.Bot]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), BotRating]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), (Boolean, Option[String])]](Map.empty),
      cats.effect.Ref.of[IO, Map[(String, String), Int]](Map.empty)
    ).mapN { (byHash, ratings, catalog, capacities) =>
      new BotStore:
        def register(team: String, name: String, tokenHash: String): IO[Boolean] =
          byHash
            .modify { bots =>
              if bots.values.exists(b => b.team == team && b.name == name) then (bots, false)
              else (bots.updated(tokenHash, Principal.Bot(team, name)), true)
            }
            .flatTap { claimed =>
              (ratings.update(_.updated((team, name), BotRating.initial)) *>
                catalog.update(_.updated((team, name), (false, None))) *>
                capacities.update(
                  _.updated((team, name), BotSeatPolicy.DefaultMaxConcurrentGames)
                )).whenA(claimed)
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
          updateRating(team, name)(_.copy(onLadder = onLadder))

        def setRatedForHumans(team: String, name: String, ratedForHumans: Boolean): IO[Option[BotRating]] =
          updateRating(team, name)(_.copy(ratedForHumans = ratedForHumans))

        private def updateRating(team: String, name: String)(f: BotRating => BotRating): IO[Option[BotRating]] =
          ratings.modify { current =>
            current.get((team, name)) match
              case Some(r) =>
                val updated = f(r)
                (current.updated((team, name), updated), Some(updated))
              case None => (current, None)
          }

        def onLadderCandidates: IO[List[BotSeatPolicy]] =
          (ratings.get, capacities.get, catalog.get).mapN { (rated, caps, cat) =>
            rated.toList.collect { case (key, r) if r.onLadder => seatPolicy(key, caps, cat) }
          }

        def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]] =
          (capacities.get, catalog.get).mapN { (caps, cat) =>
            Option.when(caps.contains((team, name)))(seatPolicy((team, name), caps, cat))
          }

        def setMaxConcurrentGames(team: String, name: String, limit: Int): IO[Option[BotSeatPolicy]] =
          capacities
            .modify { current =>
              if current.contains((team, name)) then (current.updated((team, name), limit), true)
              else (current, false)
            }
            .flatMap(declared => if declared then seatPolicyOf(team, name) else IO.pure(None))

        /** Assemble a policy from the two refs that hold its parts. The capacity fallback is dead in practice —
          * `register` seeds every key — and exists so a policy is still well-formed if a future write path forgets to.
          */
        private def seatPolicy(
            key: (String, String),
            caps: Map[(String, String), Int],
            cat: Map[(String, String), (Boolean, Option[String])]
        ): BotSeatPolicy =
          BotSeatPolicy(
            Principal.Bot(key._1, key._2),
            caps.getOrElse(key, BotSeatPolicy.DefaultMaxConcurrentGames),
            cat.get(key).exists(_._1)
          )

        def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
          catalog.modify { current =>
            if current.contains((team, name)) then
              val state = BotCatalogState(openToHumans = true, description)
              (current.updated((team, name), (true, description)), Some(state))
            else (current, None)
          }

        def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] =
          catalog.modify { current =>
            current.get((team, name)) match
              case Some((_, desc)) =>
                (current.updated((team, name), (false, desc)), Some(BotCatalogState(openToHumans = false, desc)))
              case None => (current, None)
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

/** Intake seam for browser-submitted game reports (#212): finished games the SPA played against its own in-browser
  * bots, accepted by `POST /ingest/games` and relayed to analytics. Reports are forgeable — they land in the separate
  * `client_reports` queue and nowhere else (never `game_results`/`game_archive`); the analytics replay gate stays the
  * authoritative validator.
  */
trait ClientReportStore:
  /** Enqueue a report: `true` = newly accepted (201), `false` = already known (200) — first write wins, a duplicate
    * never overwrites the stored payload, and a delivered or parked report is not resurrected.
    */
  def insertClientReport(id: GameId, payload: io.circe.Json): IO[Boolean]

  /** The deliverer's port onto `client_reports` — browser reports drain with the same retry/parking semantics as the
    * first-party outbox, just from their own table.
    */
  def clientReports: OutboxStore

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
    // Historical: `None` for a game finished after #190. Kept, not backfilled — StrengthReport's pentanomial
    // grouping still scores pre-existing CRN pairs correctly; a new row simply never joins a group.
    pairingId: Option[String],
    // Whether the ladder scheduler started this game (#190) — the only marker distinguishing that from a direct
    // rated challenge between the same two bots. `RatingBatch.shouldPark`'s auto-park streak (#150) filters on
    // this so a casual/challenge timeout can never park a bot.
    ladder: Boolean,
    finishedAt: java.time.Instant
)

/** Persistence seam for the queryable `game_results` projection (#98): the games table's own snapshot is opaque JSONB
  * (only `status` is indexed), so the ladder scheduler and rating batch need this to enumerate finished games by
  * participant / result / rated / ladder-origin without decoding JSON. One row per finished game, written once (in the
  * same transaction as the terminal snapshot save, see `PgGameStore.save`) and never updated afterward — with one
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

  /** A filtered, keyset-paginated page of finished games (#173) — the general-purpose sibling of `recentResultsFor`,
    * which serves only the small fixed-size page a bot's profile glance needs and must stay unchanged for that caller.
    * Always fetches one row past `limit`, so `GameResultsStore.Page.hasMore` is exact without a `COUNT(*)` or a second
    * round trip.
    *
    * @param externalIds
    *   every identity that counts as "the requester". Usually one; a signed-in account passes its own `user:<uuid>`
    *   PLUS every `guest:<uuid>` it has claimed (#236), which is how a merged history reads as one timeline without
    *   rewriting a single stored row.
    * @param before
    *   exclusive upper bound on `finished_at`; `None` for the first page.
    * @param opponent
    *   restricts to games against this opponent bucket; `None` for no opponent filter. The caller (the route layer) is
    *   trusted to have already resolved `OpponentFilter.Bot` to a real bot's `externalId` — this method does not itself
    *   guard against being passed an arbitrary guest id (see `OpponentFilter`'s own doc for why the public route must
    *   never accept one).
    * @param result
    *   restricts to games with this outcome from the requester's own point of view; `None` for no result filter.
    */
  def playerGamesPage(
      externalIds: List[String],
      before: Option[java.time.Instant],
      opponent: Option[OpponentFilter],
      result: Option[PovResultFilter],
      limit: Int
  ): IO[GameResultsStore.Page]

  /** The requester's aggregate W-D-L against every opponent it has played, one row per bot plus one collapsed row for
    * every human/guest opponent (#174) — most-played first, ties broken by most recent. Unlike `resultTallyFor`
    * (`LeaderboardStore`, rated-decided games only), this includes casual games: every guest game is casual by
    * `GameRegistry.isRated`, so a rated-only tally would always be empty for a guest caller.
    *
    * `externalIds` carries the same "one requester, possibly several identities" meaning as `playerGamesPage`.
    * Self-play is excluded, and with several identities that means a game whose OTHER seat is also the requester (a
    * signed-in player against their own claimed guest id) — it has no opponent to aggregate against either.
    */
  def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]]

object GameResultsStore:
  /** `recentResultsFor`'s default page size — bounds a prolific bot's history to a reasonable page rather than its
    * entire lifetime.
    */
  val DefaultRecentLimit: Int = 50

  /** One page from `playerGamesPage`: at most `limit` rows, plus whether strictly-older rows exist beyond them. */
  final case class Page(games: List[GameResultRow], hasMore: Boolean)

/** Restricts `playerGamesPage` to games against a specific kind of opponent (#173). `Bot` is the only per-identity case
  * — accepting an arbitrary opposing `guest:<uuid>` would let a caller confirm a specific uuid is one of their own
  * anonymous opponents (deanonymisation by intersection: "does my history contain a game against THIS id?"). Bots have
  * no such privacy; humans stay one undifferentiated anonymous bucket, matching the wire's existing stance
  * (`PublicPlayer`) everywhere else.
  */
enum OpponentFilter:
  case Bot(externalId: String)
  case HumanOnly

/** Restricts `playerGamesPage` to games with this outcome, from the querying participant's own point of view. */
enum PovResultFilter:
  case Win, Draw, Loss

/** One participant's aggregated record against a single opponent bucket (#174). `botExternalId = None` is the collapsed
  * "every human opponent" row; `Some(id)` is one specific registered bot.
  */
final case class OpponentAggregateRow(
    botExternalId: Option[String],
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    lastPlayedAt: java.time.Instant
)

/** Persistence seam for the Glicko-2 rating batch (#119). The work queue is claim-based: a rated `game_results` row
  * with no `rating_applied_at` stamp is pending, and applying it stamps it in the SAME transaction as the rating write
  * — chosen over a `finished_at` cursor because rating updates are NOT idempotent (a cursor with overlap would re-apply
  * games; a cursor without overlap loses games to the commit-order race documented on
  * `GameResultsStore.finishedRatedSince`). Postgres only, like [[GameResultsStore]].
  */
/** Which row a rating write lands on (#248). A rated participant is either a registered bot or an account, and the two
  * live in different tables — but they share ONE Glicko scale, so the batch treats them uniformly and only the write
  * needs to know them apart.
  */
enum RatedIdentity:
  case Bot(team: String, name: String)
  case User(id: String)

object RatedIdentity:
  def of(bot: Principal.Bot): RatedIdentity = RatedIdentity.Bot(bot.team, bot.name)

trait RatingStore:
  /** Rated games not yet applied to any rating, oldest `finished_at` first — the head of the claim queue. */
  def unappliedRatedGames(limit: Int): IO[List[GameResultRow]]

  /** Atomically write both participants' post-game Glicko state AND stamp the game as applied — one transaction, so a
    * crash between the two can neither double-apply a game nor lose one side's update.
    *
    * The two sides may live in DIFFERENT tables (an account vs a bot, #248). The atomicity guarantee is therefore per
    * GAME, not per table: one transaction spanning both writes plus the stamp.
    */
  def applyRatingUpdate(
      gameId: GameId,
      white: RatedIdentity,
      whiteGlicko: dicechess.play.rating.Glicko,
      black: RatedIdentity,
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

/** One account's leaderboard row (#249) — the human counterpart of [[LeaderboardEntry]]. `nickname` is the only public
  * handle: the account's uuid stays server-side, per ADR-0017.
  */
final case class PlayerLeaderboardEntry(nickname: String, rating: Double, rd: Double, tally: ResultTally)

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

  /** Every ACCOUNT whose rating has converged (`glicko_rd <= maxRd`), best rating first, with its rated W-D-L record
    * (#249). The human half of the one shared scale — same threshold, same policy: a provisional player is counted
    * internally and invisible publicly until the deviation settles.
    */
  def playerLeaderboard(maxRd: Double): IO[List[PlayerLeaderboardEntry]]

  /** The rated, decided W-D-L record of one participant (either seat), for the profile endpoint. */
  def resultTallyFor(externalId: String): IO[ResultTally]

/** One human-catalog card's data (ADR-0014, E2): a bot that opened itself to human games, with the rating summary its
  * card shows. `provisional` is derived by the route from `rd`, not stored here; likewise `available` (#224) is derived
  * by the route from `maxConcurrentGames` against the registry's live game count, not stored here —
  * `maxConcurrentGames` is the declaration (#189), read off the same row so the route needs no second query per bot to
  * derive it.
  */
final case class BotCatalogListing(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    description: Option[String],
    maxConcurrentGames: Int
)

/** Read seam for the human-facing bot catalog (ADR-0014) — Postgres only, like [[LeaderboardStore]]: it reads the
  * `bots` table (the rating and description columns), absent in the in-memory mode, so the catalog endpoint is simply
  * not mounted without persistence.
  */
trait BotCatalogStore:
  /** Every bot currently open to human games (`open_to_humans = true`), best rating first — the catalog's cards. */
  def catalogBots: IO[List[BotCatalogListing]]
