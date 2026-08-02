package dicechess.play.store

import cats.effect.{IO, Resource}
import cats.effect.std.Console
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import dicechess.play.core.{GameId, GameOver, GameStatus, Principal, Seat, Termination}
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.rating.Glicko
import io.circe.Json
import io.circe.syntax.*
import org.flywaydb.core.Flyway

import java.time.Instant
import scala.concurrent.duration.*

/** Postgres-backed store. Deployed against a **dedicated `play` database** (analytics is an aggregator with its own
  * lifecycle; play state is operational and restores independently) — pointed at by `PLAY_DB_URL`, with Flyway owning
  * the `play` schema inside it. play-api reaches analytics only as an ordinary writer via `POST /api/games`, never
  * through the database.
  *
  * Every round trip is bounded by a timeout: the caller treats store trouble as a degradation, and a *hung* query —
  * unlike a failed one — would otherwise stall the game's writer fiber in a way `handleErrorWith` can't catch.
  */
final class PgGameStore private (xa: Transactor[IO])
    extends GameStore
    with OutboxStore
    with ClientReportStore
    with BotStore
    with GameResultsStore
    with GameArchiveStore
    with RetentionStore
    with RatingStore
    with LeaderboardStore
    with BotCatalogStore
    with WebhookStore
    with WebhookStatsStore:
  import PgGameStore.{BootTimeout, SaveTimeout}

  /** Upsert the snapshot — and, in the SAME transaction, enqueue the finished game's analytics payload and write its
    * `game_results` and `game_archive` (#177) rows: the snapshot write and all three handoffs are atomic, so a crash
    * can't record a finished game that analytics, the ladder/rating projection, or the durable history record never
    * hears about.
    */
  def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    val status = if snapshot.ended then "ended" else "active"
    val upsert =
      sql"""INSERT INTO play.games (id, status, snapshot)
            VALUES (${id.value}::uuid, $status, ${snapshot.asJson})
            ON CONFLICT (id) DO UPDATE
            SET status = EXCLUDED.status, snapshot = EXCLUDED.snapshot, updated_at = now()""".update.run
    val enqueue = PlaysiteIngest.payload(id, snapshot) match
      case None          => ().pure[ConnectionIO]
      case Some(payload) =>
        sql"""INSERT INTO play.outbox (game_id, payload)
              VALUES (${id.value}::uuid, $payload)
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    val finishedGame = PgGameStore.finishedGameOf(snapshot)
    // finishedGameOf returning None while the snapshot IS ended means players was missing a seat — a malformed
    // snapshot, not the normal "still active" case. The games-table write still goes through (it's the more
    // foundational record), but a gap here must be visible, not silent, same as loadActive's corrupt-row logging.
    val warnIfMalformed =
      Console[IO]
        .errorln(
          s"[play][store] ended game ${id.value} produced no game_results row: players=${snapshot.players.keySet}"
        )
        .whenA(snapshot.ended && finishedGame.isEmpty)
    val recordResult = finishedGame match
      case None     => ().pure[ConnectionIO]
      case Some(fg) =>
        sql"""INSERT INTO play.game_results
                (game_id, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, ladder)
              VALUES (${id.value}::uuid, ${fg.whiteExternalId}, ${fg.blackExternalId}, ${fg.result},
                      ${fg.termination}, ${fg.rated}, ${fg.timeControl}, ${fg.serverSeed}, ${fg.ladder})
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    val archive = GameArchive.payload(snapshot) match
      case None          => ().pure[ConnectionIO]
      case Some(payload) =>
        sql"""INSERT INTO play.game_archive (game_id, payload)
              VALUES (${id.value}::uuid, $payload)
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    warnIfMalformed *> (upsert *> enqueue *> recordResult *> archive).transact(xa).timeout(SaveTimeout)

  // ── OutboxStore ─────────────────────────────────────────────────────────────

  def due(limit: Int): IO[List[OutboxRow]] =
    sql"""SELECT game_id::text, payload, attempts FROM play.outbox
          WHERE delivered_at IS NULL AND NOT failed_permanently AND next_attempt_at <= now()
          ORDER BY next_attempt_at
          LIMIT $limit"""
      .query[(String, Json, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map((id, payload, attempts) => OutboxRow(GameId(id), payload, attempts)))

  def markDelivered(gameId: GameId): IO[Unit] =
    sql"""UPDATE play.outbox SET delivered_at = now(), last_error = NULL
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  def markRetry(gameId: GameId, attempts: Int, retryIn: FiniteDuration, error: String): IO[Unit] =
    sql"""UPDATE play.outbox
          SET attempts = $attempts, next_attempt_at = now() + make_interval(secs => ${retryIn.toSeconds.toDouble}),
              last_error = $error
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  def markParked(gameId: GameId, error: String): IO[Unit] =
    sql"""UPDATE play.outbox
          SET failed_permanently = true, attempts = attempts + 1, last_error = $error
          WHERE game_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  // ── ClientReportStore ───────────────────────────────────────────────────────

  /** See [[ClientReportStore.insertClientReport]]. Same first-write-wins shape as the outbox enqueue in `save`, but the
    * key is the report's own idempotency UUID — a browser game never has a `games` row to reference.
    */
  def insertClientReport(id: GameId, payload: Json): IO[Boolean] =
    sql"""INSERT INTO play.client_reports (report_id, payload)
          VALUES (${id.value}::uuid, $payload)
          ON CONFLICT (report_id) DO NOTHING""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  /** See [[ClientReportStore.clientReports]] — a mirror of the OutboxStore methods above over `client_reports`, so one
    * `IngestDeliverer` drains each queue with identical semantics.
    */
  val clientReports: OutboxStore = new OutboxStore:
    def due(limit: Int): IO[List[OutboxRow]] =
      sql"""SELECT report_id::text, payload, attempts FROM play.client_reports
            WHERE delivered_at IS NULL AND NOT failed_permanently AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT $limit"""
        .query[(String, Json, Int)]
        .to[List]
        .transact(xa)
        .timeout(SaveTimeout)
        .map(_.map((id, payload, attempts) => OutboxRow(GameId(id), payload, attempts)))

    def markDelivered(gameId: GameId): IO[Unit] =
      sql"""UPDATE play.client_reports SET delivered_at = now(), last_error = NULL
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

    def markRetry(gameId: GameId, attempts: Int, retryIn: FiniteDuration, error: String): IO[Unit] =
      sql"""UPDATE play.client_reports
            SET attempts = $attempts, next_attempt_at = now() + make_interval(secs => ${retryIn.toSeconds.toDouble}),
                last_error = $error
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

    def markParked(gameId: GameId, error: String): IO[Unit] =
      sql"""UPDATE play.client_reports
            SET failed_permanently = true, attempts = attempts + 1, last_error = $error
            WHERE report_id = ${gameId.value}::uuid""".update.run.transact(xa).void.timeout(SaveTimeout)

  // ── GameArchiveStore ────────────────────────────────────────────────────────

  def archiveFor(id: GameId): IO[Option[ArchivedGame]] =
    sql"""SELECT payload, finished_at FROM play.game_archive WHERE game_id = ${id.value}::uuid"""
      .query[(Json, Instant)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map((payload, finishedAt) => ArchivedGame(payload, finishedAt)))

  /** See [[GameArchiveStore.backfillArchive]]. `LEFT JOIN game_results` rather than an inner one so a game missing its
    * projection row still gets archived (falling back to `games.updated_at` for `finished_at`) instead of being
    * silently stranded — the archive is the durable record, and it should not depend on another projection being
    * intact.
    *
    * Each row is inserted in its own transaction, not one per batch: an interrupted run then leaves every row it
    * already converted committed, and the cursor simply restarts from the last `game_id` the caller logged.
    */
  def backfillArchive(after: Option[GameId], limit: Int): IO[ArchiveBackfillBatch] =
    val cursor = after.map(_.value).getOrElse(PgGameStore.ZeroUuid)
    sql"""SELECT g.id::text, g.snapshot, COALESCE(r.finished_at, g.updated_at)
          FROM play.games g
          LEFT JOIN play.game_results r ON r.game_id = g.id
          WHERE g.status = 'ended'
            AND g.id > $cursor::uuid
            AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
          ORDER BY g.id
          LIMIT $limit"""
      .query[(String, Json, Instant)]
      .to[List]
      .transact(xa)
      .timeout(PgGameStore.BackfillTimeout)
      .flatMap { rows =>
        rows
          .traverse { (id, json, finishedAt) =>
            PgGameStore.archivablePayload(json) match
              case Left(reason) =>
                // Never silently dropped, and the cursor still moves past it (see ArchiveBackfillBatch). The reason is
                // spelled out because a run over tens of thousands of rows is useless to an operator who cannot tell an
                // expected skip from one worth investigating.
                Console[IO].errorln(s"[play][backfill] game $id skipped: $reason").as(0)
              case Right(payload) =>
                sql"""INSERT INTO play.game_archive (game_id, payload, finished_at)
                      VALUES ($id::uuid, $payload, $finishedAt)
                      ON CONFLICT (game_id) DO NOTHING""".update.run
                  .transact(xa)
                  .timeout(PgGameStore.BackfillTimeout)
          }
          .map { inserts =>
            val inserted = inserts.sum
            ArchiveBackfillBatch(
              lastId = rows.lastOption.map((id, _, _) => GameId(id)),
              scanned = rows.size,
              inserted = inserted,
              skipped = rows.size - inserted
            )
          }
      }

  // ── RetentionStore ──────────────────────────────────────────────────────────

  /** See [[RetentionStore.pruneOnce]]. One transaction for the whole batch: the two deletes are ordered by the V2
    * foreign key (`outbox.game_id REFERENCES games(id)`, no `ON DELETE`), so a snapshot can only go once its outbox row
    * has, and doing both atomically means a crash can never leave a game whose outbox row is gone while the row that
    * needed it survives. Bounding the batch — rather than one giant statement — is what keeps that transaction short.
    *
    * Two rules make this safe to run against live data:
    *   - only `status = 'ended'` rows are ever considered, so a game in progress is untouchable regardless of age (boot
    *     resume reads `WHERE status='active'`, and pruning a live snapshot would forfeit a real game);
    *   - a snapshot is dropped only when its history is preserved elsewhere — an archive row exists, or the game was
    *     aborted and therefore has no history to serve by design (`GameArchive.payload` excludes exactly those). An
    *     ended, non-aborted game with no archive row is RETAINED and counted, never quietly destroyed.
    *
    * A parked outbox row (`failed_permanently`) is left alone for inspection, which by the FK also pins its snapshot —
    * the `NOT EXISTS (outbox)` guard below needs no special case for it.
    *
    * Delivered `client_reports` rows (#212) are pruned by the same rule as delivered outbox rows — the row has done its
    * job — and parked ones are likewise kept for inspection. They join this transaction for the summary count only:
    * with no FK anywhere, they have no ordering relationship with the other two deletes.
    */
  def pruneOnce(olderThan: Instant, limit: Int): IO[RetentionSweep] =
    val deleteOutbox =
      sql"""DELETE FROM play.outbox
            WHERE game_id IN (
              SELECT o.game_id FROM play.outbox o
              WHERE o.delivered_at IS NOT NULL
                AND NOT o.failed_permanently
                AND o.delivered_at < $olderThan
              ORDER BY o.game_id
              LIMIT $limit
            )""".update.run

    val deleteClientReports =
      sql"""DELETE FROM play.client_reports
            WHERE report_id IN (
              SELECT c.report_id FROM play.client_reports c
              WHERE c.delivered_at IS NOT NULL
                AND NOT c.failed_permanently
                AND c.delivered_at < $olderThan
              ORDER BY c.report_id
              LIMIT $limit
            )""".update.run

    val deleteSnapshots =
      sql"""DELETE FROM play.games
            WHERE id IN (
              SELECT g.id FROM play.games g
              LEFT JOIN play.game_results r ON r.game_id = g.id
              WHERE g.status = 'ended'
                AND g.updated_at < $olderThan
                AND NOT EXISTS (SELECT 1 FROM play.outbox o WHERE o.game_id = g.id)
                AND (
                  EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
                  OR r.termination = 'aborted'
                )
              ORDER BY g.id
              LIMIT $limit
            )""".update.run

    // Counted, not deleted: the ended snapshots this pass refuses to touch because their history exists nowhere else.
    val countRetained =
      sql"""SELECT count(*) FROM play.games g
            LEFT JOIN play.game_results r ON r.game_id = g.id
            WHERE g.status = 'ended'
              AND g.updated_at < $olderThan
              AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id)
              AND COALESCE(r.termination, '') <> 'aborted'""".query[Int].unique

    // Only on a batch that removed nothing — see `RetentionSweep.retainedUnarchived`. This count is a whole-table
    // aggregate with no LIMIT, and `Retention.drain` reads it exclusively from the terminal batch, so computing it on
    // every page would scan the table once per page to throw the answer away (~47 wasted scans on the first real run).
    (deleteOutbox, deleteSnapshots, deleteClientReports)
      .flatMapN { (outboxDeleted, snapshotsDeleted, reportsDeleted) =>
        if outboxDeleted == 0 && snapshotsDeleted == 0 && reportsDeleted == 0 then
          countRetained.map(RetentionSweep(outboxDeleted, snapshotsDeleted, reportsDeleted, _))
        else RetentionSweep(outboxDeleted, snapshotsDeleted, reportsDeleted, 0).pure[ConnectionIO]
      }
      .transact(xa)
      .timeout(PgGameStore.BackfillTimeout)

  // ── BotStore ────────────────────────────────────────────────────────────────

  /** Claim the identity atomically: the primary key makes a concurrent double-register lose cleanly. */
  def register(team: String, name: String, tokenHash: String): IO[Boolean] =
    sql"""INSERT INTO play.bots (team, name, token_hash)
          VALUES ($team, $name, $tokenHash)
          ON CONFLICT (team, name) DO NOTHING""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  def authenticate(tokenHash: String): IO[Option[Principal.Bot]] =
    sql"""SELECT team, name FROM play.bots WHERE token_hash = $tokenHash"""
      .query[(String, String)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(Principal.Bot(_, _)))

  def rotate(team: String, name: String, newTokenHash: String): IO[Boolean] =
    sql"""UPDATE play.bots SET token_hash = $newTokenHash, rotated_at = now()
          WHERE team = $team AND name = $name""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  def ratingOf(team: String, name: String): IO[Option[BotRating]] =
    sql"""SELECT glicko_rating, glicko_rd, glicko_vol, on_ladder, owner_external_id
          FROM play.bots WHERE team = $team AND name = $name"""
      .query[(Double, Double, Double, Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (rating, rd, vol, onLadder, owner) => BotRating(rating, rd, vol, onLadder, owner) })

  /** `RETURNING` in the same statement: the update and the read of its result are one round trip, so there's no window
    * for a concurrent change to make the returned state stale.
    */
  def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] =
    sql"""UPDATE play.bots SET on_ladder = $onLadder WHERE team = $team AND name = $name
          RETURNING glicko_rating, glicko_rd, glicko_vol, on_ladder, owner_external_id"""
      .query[(Double, Double, Double, Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (rating, rd, vol, onLadder, owner) => BotRating(rating, rd, vol, onLadder, owner) })

  /** The scheduler's whole candidate read in one query (#189): being on the ladder, the declared capacity, and the
    * catalog flag that reserves part of it all come off the same row, so there is no reason to make it three.
    */
  def onLadderCandidates: IO[List[BotSeatPolicy]] =
    sql"""SELECT team, name, max_concurrent_games, open_to_humans
          FROM play.bots WHERE on_ladder = true"""
      .query[(String, String, Int, Boolean)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  def seatPolicyOf(team: String, name: String): IO[Option[BotSeatPolicy]] =
    sql"""SELECT max_concurrent_games, open_to_humans
          FROM play.bots WHERE team = $team AND name = $name"""
      .query[(Int, Boolean)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  /** `RETURNING` in the same statement, same no-stale-window reasoning as `setOnLadder`. The value is range-checked by
    * the caller and again by `bots_max_concurrent_games_range`; the constraint is the backstop, not the validation.
    */
  def setMaxConcurrentGames(team: String, name: String, maxConcurrentGames: Int): IO[Option[BotSeatPolicy]] =
    sql"""UPDATE play.bots SET max_concurrent_games = $maxConcurrentGames
          WHERE team = $team AND name = $name
          RETURNING max_concurrent_games, open_to_humans"""
      .query[(Int, Boolean)]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (limit, open) => BotSeatPolicy(Principal.Bot(team, name), limit, open) })

  /** `RETURNING` in the same statement (same no-stale-window reasoning as `setOnLadder`): open the bot and set its
    * description in one write, then read the persisted state back. `None` if no such registered identity.
    */
  def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
    sql"""UPDATE play.bots SET open_to_humans = true, description = $description
          WHERE team = $team AND name = $name
          RETURNING open_to_humans, description"""
      .query[(Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] =
    sql"""UPDATE play.bots SET open_to_humans = false WHERE team = $team AND name = $name
          RETURNING open_to_humans, description"""
      .query[(Boolean, Option[String])]
      .option
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (open, desc) => BotCatalogState(open, desc) })

  def openToHumansBots: IO[List[Principal.Bot]] =
    sql"""SELECT team, name FROM play.bots WHERE open_to_humans = true"""
      .query[(String, String)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(Principal.Bot(_, _)))

  /** Catalog cards for `GET /lobby/bots` (ADR-0014): the open-to-humans bots with their rating summary and blurb, best
    * rating first. Reads only `bots`, so unlike the leaderboard it needs no `game_results` join. `max_concurrent_games`
    * (#189) rides along in the same row so the route can derive `available` with a pure in-memory registry lookup per
    * card, rather than a second query per bot (#224).
    */
  def catalogBots: IO[List[BotCatalogListing]] =
    sql"""SELECT team, name, glicko_rating, glicko_rd, description, max_concurrent_games
          FROM play.bots WHERE open_to_humans = true
          ORDER BY glicko_rating DESC, team, name"""
      .query[(String, String, Double, Double, Option[String], Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, rating, rd, description, maxConcurrentGames) =>
        BotCatalogListing(team, name, rating, rd, description, maxConcurrentGames)
      })

  // ── WebhookStore (F.2, #104) ────────────────────────────────────────────────

  /** Upsert: a re-register replaces URL and secret together (the old secret stops signing immediately). */
  def put(webhook: BotWebhook): IO[Unit] =
    sql"""INSERT INTO play.bot_webhooks (team, name, url, secret, verified_at)
          VALUES (${webhook.team}, ${webhook.name}, ${webhook.url}, ${webhook.secret}, ${webhook.verifiedAt})
          ON CONFLICT (team, name)
          DO UPDATE SET url = EXCLUDED.url, secret = EXCLUDED.secret, verified_at = EXCLUDED.verified_at""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .void

  def get(team: String, name: String): IO[Option[BotWebhook]] =
    sql"""SELECT team, name, url, secret, verified_at FROM play.bot_webhooks
          WHERE team = $team AND name = $name"""
      .query[BotWebhook]
      .option
      .transact(xa)
      .timeout(SaveTimeout)

  def delete(team: String, name: String): IO[Boolean] =
    sql"""DELETE FROM play.bot_webhooks WHERE team = $team AND name = $name""".update.run
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_ == 1)

  // ── WebhookStatsStore (#225) ─────────────────────────────────────────────────

  /** One delivery folded into its histogram cell, PLUS — for a genuine fault (`DeliveryOutcome.isFailure`) — the bot's
    * "last failure" columns. `hour` is truncated in SQL (`date_trunc`), not in Scala, so the truncation rule lives in
    * exactly one place and `statsFor`'s own range read reasons about the same column the same way. Both writes are
    * best-effort off the turn path: `Webhooks`'s drain loop is the only caller, and a failure here is dropped, never
    * retried, never allowed to touch a game.
    */
  def recordDelivery(
      team: String,
      name: String,
      outcome: DeliveryOutcome,
      elapsed: FiniteDuration,
      at: Instant
  ): IO[Unit] =
    val key             = DeliveryOutcome.key(outcome)
    val bucket          = LatencyHistogram.bucketOf(elapsed)
    val upsertHistogram =
      sql"""INSERT INTO play.bot_webhook_stats (team, name, hour, outcome, latency_bucket, count)
            VALUES ($team, $name, date_trunc('hour', $at::timestamptz), $key, $bucket, 1)
            ON CONFLICT (team, name, hour, outcome, latency_bucket)
            DO UPDATE SET count = play.bot_webhook_stats.count + 1""".update.run.void
    val markLastFailure =
      sql"""UPDATE play.bot_webhooks SET last_failure_at = $at, last_failure_reason = ${DeliveryOutcome.describe(
          outcome
        )}
            WHERE team = $team AND name = $name""".update.run.void
        .whenA(DeliveryOutcome.isFailure(outcome))
    (upsertHistogram *> markLastFailure).transact(xa).timeout(SaveTimeout)

  /** `GET /bot/webhook/stats`'s read: one query covers both windows (7 days is the wider one; the 24h window is
    * re-aggregated from the same rows in Scala, in `DeliveryStatsWindow.aggregate` — no reason to hit Postgres twice
    * for a subset of what the first query already fetched), plus the bot's last-failure columns.
    */
  def statsFor(team: String, name: String, now: Instant): IO[WebhookStats] =
    val since7d  = now.minus(7, java.time.temporal.ChronoUnit.DAYS)
    val since24h = now.minus(24, java.time.temporal.ChronoUnit.HOURS)
    val rows     =
      sql"""SELECT outcome, latency_bucket, hour, count FROM play.bot_webhook_stats
            WHERE team = $team AND name = $name AND hour >= $since7d"""
        .query[(String, Int, Instant, Long)]
        .to[List]
    val lastFailure =
      sql"""SELECT last_failure_at, last_failure_reason FROM play.bot_webhooks
            WHERE team = $team AND name = $name"""
        .query[(Option[Instant], Option[String])]
        .option
    (rows, lastFailure).tupled
      .transact(xa)
      .timeout(SaveTimeout)
      .map { case (all7d, failureRow) =>
        val last24h = all7d.filter { case (_, _, hour, _) => !hour.isBefore(since24h) }
        WebhookStats(
          last24h = DeliveryStatsWindow.aggregate(last24h.map { case (o, b, _, c) => (o, b, c) }),
          last7d = DeliveryStatsWindow.aggregate(all7d.map { case (o, b, _, c) => (o, b, c) }),
          lastFailure = failureRow.flatMap {
            case (Some(at), Some(reason)) => Some(LastFailure(at, reason))
            case _                        => None
          }
        )
      }

  /** Every live game, decoded row by row: one corrupt snapshot is logged and skipped, never aborting the batch — a
    * single bad row must not stop every other game from resuming.
    */
  def loadActive: IO[List[(GameId, GameSnapshot)]] =
    sql"""SELECT id::text, snapshot FROM play.games WHERE status = 'active'"""
      .query[(String, Json)]
      .to[List]
      .transact(xa)
      .timeout(BootTimeout)
      .flatMap {
        _.flatTraverse { case (id, json) =>
          json.as[GameSnapshot] match
            case Right(snapshot) => IO.pure(List(GameId(id) -> snapshot))
            case Left(error)     =>
              Console[IO].errorln(s"[play][store] corrupt snapshot for game $id skipped: $error").as(Nil)
        }
      }

  // ── GameResultsStore ──────────────────────────────────────────────────────

  /** Two LIMIT-bounded, already-ordered subqueries (one per side) unioned and re-limited, rather than one `OR` across
    * both columns: an `OR` predicate on two single-column indexes forces Postgres to bitmap-scan and sort ALL of the
    * participant's matching rows before applying LIMIT — O(history size) — whereas each `(participant, finished_at
    * DESC)` composite index below serves its half of this query as a plain bounded index scan. Plain `UNION`, not
    * `UNION ALL`: `GameRegistry.create` doesn't itself forbid seating the same principal on both sides (only its
    * `Lobby`/`Challenges` callers do), so a self-played game would otherwise match both branches and come back twice.
    * The dedupe cost is over at most `2 * limit` rows, not the participant's whole history.
    */
  def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
    sql"""(SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, pairing_id::text, ladder, finished_at
           FROM play.game_results
           WHERE white_external_id = $externalId
           ORDER BY finished_at DESC
           LIMIT $limit)
          UNION
          (SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, pairing_id::text, ladder, finished_at
           FROM play.game_results
           WHERE black_external_id = $externalId
           ORDER BY finished_at DESC
           LIMIT $limit)
          ORDER BY finished_at DESC
          LIMIT $limit"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  def finishedRatedSince(since: Instant): IO[List[GameResultRow]] =
    sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, pairing_id::text, ladder, finished_at
          FROM play.game_results
          WHERE rated = true AND finished_at > $since
          ORDER BY finished_at ASC"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  /** One side's `WHERE` clause: the participant match plus whichever optional filters are present, folded from a list
    * rather than built with always-present `col IS NULL OR ...` guards — the latter risks the planner falling back to a
    * full scan of the participant's matching rows on a parameter it can't prove absent at plan time, defeating the
    * whole point of the composite `(participant, finished_at DESC)` index this shares with `recentResultsFor`.
    * `opponentCol` is the OTHER side's column (the participant's opponent in this branch).
    */
  private def pageSide(
      participantCol: String,
      opponentCol: String,
      externalId: String,
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      povResult: Option[Int],
      fetchLimit: Int
  ): Fragment =
    val opponentFrag = opponent.map:
      case OpponentFilter.Bot(id)   => Fragment.const(opponentCol) ++ fr"= $id"
      case OpponentFilter.HumanOnly => Fragment.const(opponentCol) ++ fr"NOT LIKE 'bot:team:%'"
    // The participant match is always present, so this list is never empty — `reduce` (not `reduceOption`) is safe.
    val predicates = (Fragment.const(participantCol) ++ fr"= $externalId") :: List(
      before.map(b => fr"finished_at < $b"),
      opponentFrag,
      povResult.map(r => fr"result = $r")
    ).flatten
    val where = predicates.reduce(_ ++ fr" AND " ++ _)
    fr"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                server_seed, pairing_id::text, ladder, finished_at
         FROM play.game_results
         WHERE""" ++ where ++ fr"ORDER BY finished_at DESC LIMIT $fetchLimit"

  /** The requester's own POV result, translated to the white-POV value the `result` column stores for THIS branch —
    * `PovResultFilter.Draw` is its own inverse (`-0 = 0`), so only Win/Loss actually flip between the two branches.
    */
  private def povResultValue(result: PovResultFilter, requesterIsWhite: Boolean): Int =
    val whitePov = result match
      case PovResultFilter.Win  => 1
      case PovResultFilter.Draw => 0
      case PovResultFilter.Loss => -1
    if requesterIsWhite then whitePov else -whitePov

  def playerGamesPage(
      externalId: String,
      before: Option[Instant],
      opponent: Option[OpponentFilter],
      result: Option[PovResultFilter],
      limit: Int
  ): IO[GameResultsStore.Page] =
    // One row past `limit`, so `hasMore` is exact without a COUNT(*) or a second round trip (same idea as
    // recentResultsFor's own limit-per-branch-then-relimit shape, just with optional filters folded in).
    val fetchLimit  = limit + 1
    val whiteBranch =
      pageSide(
        "white_external_id",
        "black_external_id",
        externalId,
        before,
        opponent,
        result.map(povResultValue(_, requesterIsWhite = true)),
        fetchLimit
      )
    val blackBranch =
      pageSide(
        "black_external_id",
        "white_external_id",
        externalId,
        before,
        opponent,
        result.map(povResultValue(_, requesterIsWhite = false)),
        fetchLimit
      )
    (fr"(" ++ whiteBranch ++ fr") UNION (" ++ blackBranch ++ fr") ORDER BY finished_at DESC LIMIT $fetchLimit")
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map { rows =>
        GameResultsStore.Page(rows.take(limit).map(PgGameStore.toRow), hasMore = rows.length > limit)
      }

  /** Self-play (`white_external_id = black_external_id`) is excluded from both branches — a game against yourself has
    * no opponent to aggregate against. `bot_key` collapses every non-bot opponent onto `NULL`, so `GROUP BY bot_key`
    * yields one row per registered bot plus one row for every human/guest opponent combined.
    */
  def opponentsFor(externalId: String): IO[List[OpponentAggregateRow]] =
    sql"""SELECT bot_key, count(*)::int AS games,
                 count(*) FILTER (WHERE pov_result = 1)::int AS wins,
                 count(*) FILTER (WHERE pov_result = 0)::int AS draws,
                 count(*) FILTER (WHERE pov_result = -1)::int AS losses,
                 max(finished_at) AS last_played_at
          FROM (
            (SELECT CASE WHEN black_external_id LIKE 'bot:team:%' THEN black_external_id END AS bot_key,
                    result AS pov_result, finished_at
             FROM play.game_results
             WHERE white_external_id = $externalId AND black_external_id <> white_external_id)
            UNION ALL
            (SELECT CASE WHEN white_external_id LIKE 'bot:team:%' THEN white_external_id END AS bot_key,
                    -result AS pov_result, finished_at
             FROM play.game_results
             WHERE black_external_id = $externalId AND white_external_id <> black_external_id)
          ) per_game
          GROUP BY bot_key
          ORDER BY games DESC, last_played_at DESC"""
      .query[(Option[String], Int, Int, Int, Int, Instant)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (botKey, games, wins, draws, losses, lastPlayedAt) =>
        OpponentAggregateRow(botKey, games, wins, draws, losses, lastPlayedAt)
      })

  // ── RatingStore (#119) ────────────────────────────────────────────────────

  def unappliedRatedGames(limit: Int): IO[List[GameResultRow]] =
    sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, pairing_id::text, ladder, finished_at
          FROM play.game_results
          WHERE rated = true AND rating_applied_at IS NULL
          ORDER BY finished_at ASC
          LIMIT $limit"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  def applyRatingUpdate(
      gameId: GameId,
      white: Principal.Bot,
      whiteGlicko: Glicko,
      black: Principal.Bot,
      blackGlicko: Glicko
  ): IO[Unit] =
    (updateGlicko(white, whiteGlicko) *> updateGlicko(black, blackGlicko) *> stampApplied(gameId))
      .transact(xa)
      .timeout(SaveTimeout)

  def markRatingApplied(gameId: GameId): IO[Unit] =
    stampApplied(gameId).transact(xa).timeout(SaveTimeout)

  private def updateGlicko(bot: Principal.Bot, glicko: Glicko): ConnectionIO[Unit] =
    sql"""UPDATE play.bots
          SET glicko_rating = ${glicko.rating}, glicko_rd = ${glicko.deviation}, glicko_vol = ${glicko.volatility}
          WHERE team = ${bot.team} AND name = ${bot.name}""".update.run.void

  private def stampApplied(gameId: GameId): ConnectionIO[Unit] =
    sql"""UPDATE play.game_results SET rating_applied_at = now()
          WHERE game_id = ${gameId.value}::uuid""".update.run.void

  // ── LeaderboardStore (#103) ───────────────────────────────────────────────

  /** One query: registered bots joined against their rated, decided W-D-L aggregated from `game_results` (each game
    * contributes from both seats' perspectives via the UNION ALL). The scan over rated games is acceptable at this
    * corpus's scale; if the ladder ever grows past that, a materialised tally is the upgrade path — behind this same
    * trait method.
    */
  def leaderboard(maxRd: Double): IO[List[LeaderboardEntry]] =
    sql"""SELECT b.team, b.name, b.glicko_rating, b.glicko_rd, b.on_ladder,
                 COALESCE(t.wins, 0), COALESCE(t.draws, 0), COALESCE(t.losses, 0)
          FROM play.bots b
          LEFT JOIN (
            SELECT external_id, SUM(win) AS wins, SUM(draw) AS draws, SUM(loss) AS losses
            FROM (
              SELECT white_external_id AS external_id,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END AS win,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END AS draw,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END AS loss
              FROM play.game_results WHERE rated = true AND result IS NOT NULL
              UNION ALL
              SELECT black_external_id,
                     CASE WHEN result = -1 THEN 1 ELSE 0 END,
                     CASE WHEN result = 0  THEN 1 ELSE 0 END,
                     CASE WHEN result = 1  THEN 1 ELSE 0 END
              FROM play.game_results WHERE rated = true AND result IS NOT NULL
            ) sides
            GROUP BY external_id
          ) t ON t.external_id = 'bot:team:' || b.team || ':' || b.name
          WHERE b.glicko_rd <= $maxRd
          ORDER BY b.glicko_rating DESC, b.glicko_rd ASC, b.team, b.name"""
      .query[(String, String, Double, Double, Boolean, Int, Int, Int)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map { case (team, name, rating, rd, onLadder, wins, draws, losses) =>
        LeaderboardEntry(team, name, rating, rd, onLadder, ResultTally(wins, draws, losses))
      })

  def resultTallyFor(externalId: String): IO[ResultTally] =
    sql"""SELECT
            COALESCE(SUM(CASE WHEN (white_external_id = $externalId AND result = 1)
                               OR (black_external_id = $externalId AND result = -1) THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN result = 0 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN (white_external_id = $externalId AND result = -1)
                               OR (black_external_id = $externalId AND result = 1) THEN 1 ELSE 0 END), 0)
          FROM play.game_results
          WHERE rated = true AND result IS NOT NULL
            AND (white_external_id = $externalId OR black_external_id = $externalId)"""
      .query[(Int, Int, Int)]
      .unique
      .transact(xa)
      .timeout(SaveTimeout)
      .map(ResultTally(_, _, _))

object PgGameStore:

  /** The `game_results` fields derivable from a snapshot alone — everything except `finished_at`, which the INSERT
    * leaves to the column's own `DEFAULT now()` rather than threading a captured instant through.
    */
  final private case class FinishedGame(
      whiteExternalId: String,
      blackExternalId: String,
      result: Option[Int],
      termination: String,
      rated: Boolean,
      timeControl: String,
      serverSeed: String,
      ladder: Boolean
  )

  /** `None` while the game is still active (or, for an ended snapshot, if `players` is unexpectedly missing a seat —
    * `save` logs that case separately, since it's a malformed row, not the normal "still active" path). Unlike
    * `PlaysiteIngest.payload`, this does NOT exclude aborted games from the table entirely: `game_results` is an
    * operational projection the scheduler/rating batch query, not the analytics corpus, so an aborted game is still a
    * real row (`termination = "aborted"`). It IS excluded from rating eligibility specifically — `result = None` and
    * `rated = false` regardless of what was decided at creation — since an aborted game has no sporting outcome and
    * must never hand `finishedRatedSince`'s caller a fabricated win/loss/draw.
    */
  private def finishedGameOf(snapshot: GameSnapshot): Option[FinishedGame] =
    snapshot.status match
      case GameStatus.Active                               => None
      case GameStatus.Ended(GameOver(result, termination)) =>
        val aborted = termination == Termination.Aborted
        (snapshot.players.get(Seat.White), snapshot.players.get(Seat.Black)).mapN { (white, black) =>
          FinishedGame(
            whiteExternalId = white.externalId,
            blackExternalId = black.externalId,
            result = Option.unless(aborted)(PlaysiteIngest.resultOf(result)),
            termination = PlaysiteIngest.terminationOf(termination),
            rated = !aborted && snapshot.rated.getOrElse(false),
            timeControl = snapshot.timeControl.toString,
            serverSeed = snapshot.serverSeed,
            ladder = snapshot.ladder.getOrElse(false)
          )
        }

  /** A stored snapshot's archive payload, or `Left(reason)` naming WHY there isn't one (#199). The three causes are not
    * equivalent to whoever is watching a backfill run: an aborted game is a correct, permanent skip, whereas a snapshot
    * that will not decode or is missing a seat is a data problem worth looking at. Collapsing them into one message —
    * and swallowing circe's decode error — would leave an operator scanning tens of thousands of rows with no way to
    * tell the two apart, which is exactly why `loadActive` logs its own decode failures in full.
    */
  private def archivablePayload(json: Json): Either[String, Json] =
    json.as[GameSnapshot] match
      case Left(error)     => Left(s"snapshot does not decode — investigate ($error)")
      case Right(snapshot) =>
        GameArchive.payload(snapshot) match
          case Some(payload) => Right(payload)
          case None          =>
            snapshot.status match
              case GameStatus.Ended(GameOver(_, Termination.Aborted)) =>
                Left("aborted — expected, aborted games are never archived")
              // The SQL filters on `status = 'ended'`, so an active snapshot here means the column and the JSON
              // disagree — impossible through `save`, hence worth surfacing rather than quietly counting.
              case GameStatus.Active   => Left("column says ended but the snapshot says active — investigate")
              case GameStatus.Ended(_) =>
                Left(s"ended but missing a player seat (${snapshot.players.keySet}) — investigate")

  private type ResultTuple =
    (String, String, String, Option[Int], String, Boolean, String, String, Option[String], Boolean, Instant)

  private def toRow(t: ResultTuple): GameResultRow =
    val (gameId, white, black, result, termination, rated, timeControl, serverSeed, pairingId, ladder, finishedAt) = t
    GameResultRow(
      GameId(gameId),
      white,
      black,
      result,
      termination,
      rated,
      timeControl,
      serverSeed,
      pairingId,
      ladder,
      finishedAt
    )

  /** Bound on a per-event snapshot write: long enough for a slow LAN round trip, short enough that a stalled database
    * degrades the game to in-memory play instead of freezing its writer fiber.
    */
  private val SaveTimeout: FiniteDuration = 5.seconds

  /** Bound on the boot-time resume scan (one query for all live games). */
  private val BootTimeout: FiniteDuration = 30.seconds

  /** Bound on one backfill batch's query/insert (#199). Generous compared with `SaveTimeout`: this is an offline
    * maintenance run scanning a large table, and unlike a live snapshot write there is no game waiting on it — a
    * spurious timeout here would just make the operator re-run a batch.
    */
  private val BackfillTimeout: FiniteDuration = 60.seconds

  /** The keyset cursor's starting point — `uuid` has no `-infinity`, so the first batch compares against the lowest
    * possible value rather than special-casing the predicate away.
    */
  private val ZeroUuid: String = "00000000-0000-0000-0000-000000000000"

  /** Connection settings, from the environment. Persistence is opt-in: with `PLAY_DB_URL` unset the server runs
    * in-memory exactly as before (games do not survive a restart).
    */
  final case class Config(url: String, user: String, password: String)

  def configFromEnv: Option[Config] =
    sys.env.get("PLAY_DB_URL").filter(_.nonEmpty).map { url =>
      Config(url, sys.env.getOrElse("PLAY_DB_USER", "play"), sys.env.getOrElse("PLAY_DB_PASSWORD", ""))
    }

  /** Migrate (Flyway owns schema `play`, creating it if absent) and open a pooled transactor. Returns the concrete
    * type: the caller wires it as the registry's `GameStore` and the deliverer's `OutboxStore`.
    */
  def resource(config: Config): Resource[IO, PgGameStore] =
    for
      _ <- Resource.eval(migrate(config))
      // A small dedicated pool for awaiting connections, so blocking waits never land on the compute pool.
      connectEC <- ExecutionContexts.fixedThreadPool[IO](4)
      xa        <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = connectEC
      )
    yield new PgGameStore(xa)

  /** Boot-time connect races are normal (compose may start the app before Postgres accepts connections; the
    * testcontainers port-forward on Rancher lags a moment), so the initial migration retries briefly before failing the
    * boot for real.
    */
  private def migrate(config: Config): IO[Unit] =
    def attempt(remaining: Int): IO[Unit] =
      IO.blocking {
        Flyway
          .configure()
          .dataSource(config.url, config.user, config.password)
          .schemas("play") // migrations and their history live in schema `play`
          .createSchemas(true)
          .load()
          .migrate()
        ()
      }.handleErrorWith { error =>
        if remaining <= 1 then IO.raiseError(error)
        else
          Console[IO].errorln(s"[play][store] database not ready (${error.getClass.getSimpleName}), retrying…") *>
            IO.sleep(1.second) *> attempt(remaining - 1)
      }
    attempt(10)
