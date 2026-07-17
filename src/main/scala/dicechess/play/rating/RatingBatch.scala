package dicechess.play.rating

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.Principal
import dicechess.play.store.{BotStore, GameResultRow, RatingStore}

import scala.concurrent.duration.*

/** The Glicko-2 rating batch (#119): a single background fiber that drains the claim queue of rated, not-yet-applied
  * `game_results` rows (oldest first) and updates both participants' `bots.glicko_*` state — per game, in one
  * transaction with the row's `rating_applied_at` stamp, so a crash can neither double-apply a game nor lose one side's
  * update. Runs OUTSIDE the game write-path (the roadmap's "zero load on the game flow" holds); single fiber = single
  * writer, so rating reads-then-writes never race themselves.
  *
  * Every game is its own one-game rating period for both participants, applied in `finished_at` order — see
  * [[Glicko2]]'s doc for why (and for the deliberate absence of idle RD inflation). Both sides update from the same
  * PRE-game snapshots, the standard simultaneous treatment.
  *
  * Games the update can never apply — a participant that is not a registered bot (humans arrive with accounts later), a
  * missing result, self-play — are logged and stamped applied anyway: left unstamped they would sit at the head of the
  * queue forever.
  */
final class RatingBatch(botStore: BotStore, ratingStore: RatingStore, config: RatingBatch.Config):

  /** One batch tick: process the queue page by page until a short page says it is drained. */
  def tick: IO[Unit] =
    ratingStore.unappliedRatedGames(config.batchSize).flatMap { games =>
      games.traverse_(applyGame) *> tick.whenA(games.size == config.batchSize)
    }

  /** Background loop; start once at boot. Unlike the in-memory sweepers (`Lobby`/`Challenges`), a tick here does real
    * database I/O, so a transient failure is logged and the loop lives on to retry next interval — a poisoned row halts
    * progress at the head of the queue *visibly* (an error per tick), never silently kills rating updates.
    */
  def scheduler(interval: FiniteDuration = config.interval): IO[Unit] =
    (IO.sleep(interval) *> tick.handleErrorWith(error =>
      Console[IO].errorln(s"[play][rating] tick failed, retrying next interval: $error")
    )).foreverM

  private def applyGame(row: GameResultRow): IO[Unit] =
    (
      Principal.fromBotExternalId(row.whiteExternalId),
      Principal.fromBotExternalId(row.blackExternalId),
      row.result.flatMap(RatingBatch.scores)
    ) match
      case (Some(white), Some(black), _) if white == black =>
        skip(row, "self-play carries no rating information")
      case (Some(white), Some(black), Some((whiteScore, blackScore))) =>
        (botStore.ratingOf(white.team, white.name), botStore.ratingOf(black.team, black.name)).flatMapN {
          case (Some(whiteRating), Some(blackRating)) =>
            val whiteNew = Glicko2.update(whiteRating.glicko, List(Glicko2.Result(blackRating.glicko, whiteScore)))
            val blackNew = Glicko2.update(blackRating.glicko, List(Glicko2.Result(whiteRating.glicko, blackScore)))
            ratingStore.applyRatingUpdate(row.gameId, white, whiteNew, black, blackNew)
          case _ => skip(row, "a participant is not a REGISTERED bot")
        }
      case (Some(_), Some(_), None) => skip(row, "no definite result")
      case _                        => skip(row, "a participant is not a bot identity")

  private def skip(row: GameResultRow, why: String): IO[Unit] =
    Console[IO].errorln(s"[play][rating] game ${row.gameId.value} skipped ($why); stamped applied") *>
      ratingStore.markRatingApplied(row.gameId)

object RatingBatch:

  /** `interval` between queue polls; `batchSize` is the page size of one poll (the tick keeps paging until a short
    * page, so the backlog after downtime still drains in one tick).
    */
  final case class Config(interval: FiniteDuration, batchSize: Int)

  object Config:
    val DefaultInterval: FiniteDuration = 60.seconds
    val DefaultBatchSize: Int           = 100
    val Default: Config                 = Config(DefaultInterval, DefaultBatchSize)

    /** Parse from explicit optional raw values (also used by tests — same split, and the same strictly-positive
      * validation, as `LadderScheduler.Config.fromValues`): a non-positive interval would busy-spin the loop, a
      * non-positive batch size would make every tick a no-op; either is treated as absent/unparseable. An invalid
      * interval disables the batch entirely; an invalid batch size falls back to the default, since it is a tuning
      * knob, not the on/off switch.
      */
    def fromValues(intervalSecondsRaw: Option[String], batchSizeRaw: Option[String]): Option[Config] =
      intervalSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map { seconds =>
        val size = batchSizeRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultBatchSize)
        Config(seconds.seconds, size)
      }

  /** Opt-in by env, same "absence disables" idiom as `LADDER_INTERVAL_SECONDS`: with `RATING_INTERVAL_SECONDS` unset,
    * no ratings are ever recomputed.
    */
  def configFromEnv: Option[Config] =
    Config.fromValues(sys.env.get("RATING_INTERVAL_SECONDS"), sys.env.get("RATING_BATCH_SIZE"))

  /** White-POV stored result → (whiteScore, blackScore) in Glicko terms; `None` for any out-of-vocabulary value. */
  private[rating] def scores(result: Int): Option[(Double, Double)] = result match
    case 1  => Some((1.0, 0.0))
    case 0  => Some((0.5, 0.5))
    case -1 => Some((0.0, 1.0))
    case _  => None
