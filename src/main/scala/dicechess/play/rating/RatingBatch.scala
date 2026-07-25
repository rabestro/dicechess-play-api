package dicechess.play.rating

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.Principal
import dicechess.play.store.{BotStore, GameResultRow, GameResultsStore, RatingStore}

import java.time.Instant
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
final class RatingBatch(
    botStore: BotStore,
    ratingStore: RatingStore,
    resultsStore: GameResultsStore,
    config: RatingBatch.Config
):

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

  private def checkAndParkBotIfNeeded(bot: Principal.Bot): IO[Unit] =
    // Fetch enough recent games to cover N pairs plus some slack for casual games in between.
    // A limit of N*4 is generous: it allows up to N casual games interspersed with N ladder pairs.
    resultsStore
      .recentResultsFor(bot.externalId, limit = config.ladderTimeoutParkPairs * 4)
      .flatMap: results =>
        // Only consider ladder games (those with a pairingId) to avoid false positives from casual/challenge timeouts.
        // Each mirrored ladder pairing produces exactly two results sharing the same pairingId.
        val ladderGames = results.filter(_.pairingId.isDefined)
        // Group by pairingId and keep only complete pairs (exactly 2 results)
        val completePairs = ladderGames
          .groupBy(_.pairingId)
          .collect { case (Some(id), games) if games.size == 2 => (id, games) }
          .toList
        // Sort pairs by most recent finished_at (descending) to check consecutiveness from newest to oldest
        val sortedPairs = completePairs.sortBy(_._2.head.finishedAt)(using Ordering[Instant].reverse)
        // Check if the most recent N pairs are all timeout losses for this bot
        val recentPairs               = sortedPairs.take(config.ladderTimeoutParkPairs)
        val allRecentAreTimeoutLosses = recentPairs.forall: (_, games) =>
          games.forall: row =>
            val isBotWhite = row.whiteExternalId == bot.externalId
            val isBotBlack = row.blackExternalId == bot.externalId
            val isBotLoser =
              if isBotWhite then row.result.contains(-1) else if isBotBlack then row.result.contains(1) else false
            isBotLoser && row.termination == "timeout"
        if allRecentAreTimeoutLosses && recentPairs.size == config.ladderTimeoutParkPairs then
          botStore
            .setOnLadder(bot.team, bot.name, onLadder = false)
            .flatMap:
              case Some(_) =>
                Console[IO].println(
                  s"[play][rating] auto-parked bot ${bot.team}/${bot.name} after ${recentPairs.size} consecutive timeout pairings"
                )
              case None =>
                Console[IO].errorln(s"[play][rating] failed to auto-park bot ${bot.team}/${bot.name}: not found")
        else IO.unit

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
            ratingStore.applyRatingUpdate(row.gameId, white, whiteNew, black, blackNew) *>
              checkTimeoutAndPark(white, row) *>
              checkTimeoutAndPark(black, row)
          case _ => skip(row, "a participant is not a REGISTERED bot")
        }
      case (Some(_), Some(_), None) => skip(row, "no definite result")
      case _                        => skip(row, "a participant is not a bot identity")

  private def checkTimeoutAndPark(bot: Principal.Bot, row: GameResultRow): IO[Unit] =
    val isBotWhite = row.whiteExternalId == bot.externalId
    val isBotBlack = row.blackExternalId == bot.externalId
    val isBotLoser =
      if isBotWhite then row.result.contains(-1) else if isBotBlack then row.result.contains(1) else false
    if isBotLoser && row.termination == "timeout" then checkAndParkBotIfNeeded(bot)
    else IO.unit

  private def skip(row: GameResultRow, why: String): IO[Unit] =
    Console[IO].errorln(s"[play][rating] game ${row.gameId.value} skipped ($why); stamped applied") *>
      ratingStore.markRatingApplied(row.gameId)

object RatingBatch:

  /** `interval` between queue polls; `batchSize` is the page size of one poll (the tick keeps paging until a short
    * page, so the backlog after downtime still drains in one tick).
    */
  final case class Config(
      interval: FiniteDuration,
      batchSize: Int,
      ladderTimeoutParkPairs: Int
  )

  object Config:
    val DefaultInterval: FiniteDuration    = 60.seconds
    val DefaultBatchSize: Int              = 100
    val DefaultLadderTimeoutParkPairs: Int = 2
    val Default: Config                    = Config(DefaultInterval, DefaultBatchSize, DefaultLadderTimeoutParkPairs)

    /** Parse from explicit optional raw values (also used by tests — same split, and the same strictly-positive
      * validation, as `LadderScheduler.Config.fromValues`): a non-positive interval would busy-spin the loop, a
      * non-positive batch size would make every tick a no-op; either is treated as absent/unparseable. An invalid
      * interval disables the batch entirely; an invalid batch size or ladder timeout park pairs falls back to the
      * default, since they are tuning knobs, not the on/off switch.
      */
    def fromValues(
        intervalSecondsRaw: Option[String],
        batchSizeRaw: Option[String],
        ladderTimeoutParkPairsRaw: Option[String]
    ): Option[Config] =
      intervalSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map { seconds =>
        val size      = batchSizeRaw.flatMap(_.toIntOption).filter(_ > 0).getOrElse(DefaultBatchSize)
        val parkPairs = ladderTimeoutParkPairsRaw
          .flatMap(_.toIntOption)
          .filter(_ > 0)
          .getOrElse(DefaultLadderTimeoutParkPairs)
        Config(seconds.seconds, size, parkPairs)
      }

  /** Opt-in by env, same "absence disables" idiom as `LADDER_INTERVAL_SECONDS`: with `RATING_INTERVAL_SECONDS` unset,
    * no ratings are ever recomputed.
    */
  def configFromEnv: Option[Config] =
    Config.fromValues(
      sys.env.get("RATING_INTERVAL_SECONDS"),
      sys.env.get("RATING_BATCH_SIZE"),
      sys.env.get("LADDER_TIMEOUT_PARK_PAIRS")
    )

  /** White-POV stored result → (whiteScore, blackScore) in Glicko terms; `None` for any out-of-vocabulary value. */
  private[rating] def scores(result: Int): Option[(Double, Double)] = result match
    case 1  => Some((1.0, 0.0))
    case 0  => Some((0.5, 0.5))
    case -1 => Some((0.0, 1.0))
    case _  => None
