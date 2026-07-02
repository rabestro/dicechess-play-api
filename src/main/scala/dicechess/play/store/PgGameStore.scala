package dicechess.play.store

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.hikari.HikariTransactor
import dicechess.play.core.GameId
import io.circe.Json
import io.circe.syntax.*
import org.flywaydb.core.Flyway

/** Postgres-backed store: schema `play` on the shared homelab instance (kept out of the public analytics schema, but
  * inside the same database so the existing pg_dump backup covers it for free).
  */
final class PgGameStore private (xa: Transactor[IO]) extends GameStore:

  def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    val status = if snapshot.ended then "ended" else "active"
    sql"""INSERT INTO play.games (id, status, snapshot)
          VALUES (${id.value}::uuid, $status, ${snapshot.asJson})
          ON CONFLICT (id) DO UPDATE
          SET status = EXCLUDED.status, snapshot = EXCLUDED.snapshot, updated_at = now()""".update.run
      .transact(xa)
      .void

  def loadActive: IO[List[(GameId, GameSnapshot)]] =
    sql"""SELECT id::text, snapshot FROM play.games WHERE status = 'active'"""
      .query[(String, Json)]
      .to[List]
      .transact(xa)
      .flatMap {
        _.traverse { case (id, json) =>
          IO.fromEither(json.as[GameSnapshot].left.map(e => new RuntimeException(s"corrupt snapshot $id: $e")))
            .map(GameId(id) -> _)
        }
      }

object PgGameStore:

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
      _  <- Resource.eval(migrate(config))
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = scala.concurrent.ExecutionContext.global
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
