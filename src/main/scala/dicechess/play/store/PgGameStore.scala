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
    with BotStore
    with GameResultsStore
    with RatingStore:
  import PgGameStore.{BootTimeout, SaveTimeout}

  /** Upsert the snapshot — and, in the SAME transaction, enqueue the finished game's analytics payload and (for a
    * terminal write) its `game_results` row: the snapshot write and both handoffs are atomic, so a crash can't record a
    * finished game that analytics or the ladder/rating projection never hears about.
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
                 server_seed, pairing_id)
              VALUES (${id.value}::uuid, ${fg.whiteExternalId}, ${fg.blackExternalId}, ${fg.result},
                      ${fg.termination}, ${fg.rated}, ${fg.timeControl}, ${fg.serverSeed}, ${fg.pairingId}::uuid)
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
    warnIfMalformed *> (upsert *> enqueue *> recordResult).transact(xa).timeout(SaveTimeout)

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

  def onLadderBots: IO[List[Principal.Bot]] =
    sql"""SELECT team, name FROM play.bots WHERE on_ladder = true"""
      .query[(String, String)]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(Principal.Bot(_, _)))

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
                  server_seed, pairing_id::text, finished_at
           FROM play.game_results
           WHERE white_external_id = $externalId
           ORDER BY finished_at DESC
           LIMIT $limit)
          UNION
          (SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                  server_seed, pairing_id::text, finished_at
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
                 server_seed, pairing_id::text, finished_at
          FROM play.game_results
          WHERE rated = true AND finished_at > $since
          ORDER BY finished_at ASC"""
      .query[PgGameStore.ResultTuple]
      .to[List]
      .transact(xa)
      .timeout(SaveTimeout)
      .map(_.map(PgGameStore.toRow))

  /** `None` for a malformed `pairingId` short-circuits to an empty result without touching the database: the `::uuid`
    * cast below would otherwise raise a Postgres error (22P02) instead of "found nothing", and a caller with a
    * genuinely-minted pairing id (this method's only realistic caller today) never hits this path anyway.
    */
  def pairFor(pairingId: String): IO[List[GameResultRow]] =
    scala.util.Try(java.util.UUID.fromString(pairingId)).toOption match
      case None    => IO.pure(Nil)
      case Some(_) =>
        sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                     server_seed, pairing_id::text, finished_at
              FROM play.game_results
              WHERE pairing_id = ${pairingId}::uuid"""
          .query[PgGameStore.ResultTuple]
          .to[List]
          .transact(xa)
          .timeout(SaveTimeout)
          .map(_.map(PgGameStore.toRow))

  // ── RatingStore (#119) ────────────────────────────────────────────────────

  def unappliedRatedGames(limit: Int): IO[List[GameResultRow]] =
    sql"""SELECT game_id::text, white_external_id, black_external_id, result, termination, rated, time_control,
                 server_seed, pairing_id::text, finished_at
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
      pairingId: Option[String]
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
            pairingId = snapshot.pairingId
          )
        }

  private type ResultTuple =
    (String, String, String, Option[Int], String, Boolean, String, String, Option[String], Instant)

  private def toRow(t: ResultTuple): GameResultRow =
    val (gameId, white, black, result, termination, rated, timeControl, serverSeed, pairingId, finishedAt) = t
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
      finishedAt
    )

  /** Bound on a per-event snapshot write: long enough for a slow LAN round trip, short enough that a stalled database
    * degrades the game to in-memory play instead of freezing its writer fiber.
    */
  private val SaveTimeout: FiniteDuration = 5.seconds

  /** Bound on the boot-time resume scan (one query for all live games). */
  private val BootTimeout: FiniteDuration = 30.seconds

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
