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
final class PgGameStore private (xa: Transactor[IO]) extends GameStore:
  import PgGameStore.{BootTimeout, SaveTimeout}

  def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    val status = if snapshot.ended then "ended" else "active"
    sql"""INSERT INTO play.games (id, status, snapshot)
          VALUES (${id.value}::uuid, $status, ${snapshot.asJson})
          ON CONFLICT (id) DO UPDATE
          SET status = EXCLUDED.status, snapshot = EXCLUDED.snapshot, updated_at = now()""".update.run
      .transact(xa)
      .void
      .timeout(SaveTimeout)

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

  /** Migrate (Flyway owns schema `play`, creating it if absent) and open a pooled transactor. */
  def resource(config: Config): Resource[IO, GameStore] =
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

  private def migrate(config: Config): IO[Unit] = IO.blocking {
    Flyway
      .configure()
      .dataSource(config.url, config.user, config.password)
      .schemas("play") // migrations and their history live in schema `play`
      .createSchemas(true)
      .load()
      .migrate()
    ()
  }
