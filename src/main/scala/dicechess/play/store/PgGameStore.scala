package dicechess.play.store

import cats.effect.{IO, Resource}
import cats.effect.std.Console
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import dicechess.play.core.GameId
import dicechess.play.ingest.PlaysiteIngest
import io.circe.Json
import io.circe.syntax.*
import org.flywaydb.core.Flyway

import scala.concurrent.duration.*

/** Postgres-backed store. Deployed against a **dedicated `play` database** (analytics is an aggregator with its own
  * lifecycle; play state is operational and restores independently) — pointed at by `PLAY_DB_URL`, with Flyway owning
  * the `play` schema inside it. play-api reaches analytics only as an ordinary writer via `POST /api/games`, never
  * through the database.
  *
  * Every round trip is bounded by a timeout: the caller treats store trouble as a degradation, and a *hung* query —
  * unlike a failed one — would otherwise stall the game's writer fiber in a way `handleErrorWith` can't catch.
  */
final class PgGameStore private (xa: Transactor[IO]) extends GameStore with OutboxStore:
  import PgGameStore.{BootTimeout, SaveTimeout}

  /** Upsert the snapshot — and, in the SAME transaction, enqueue the finished game's analytics payload: the terminal
    * write and its handoff are atomic, so a crash can't record a finished game that analytics never hears about.
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
    (upsert *> enqueue).transact(xa).timeout(SaveTimeout)

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

object PgGameStore:

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
