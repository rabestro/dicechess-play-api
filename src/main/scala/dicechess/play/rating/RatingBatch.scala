package dicechess.play.rating

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.{Principal, Termination}
import dicechess.play.ingest.PlaysiteIngest
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
  *
  * '''Ladder auto-park (#150)''' rides along here. A bot whose last `ladderTimeoutParkPairs` mirrored pairings were all
  * lost on the clock is opted out of the ladder — `on_ladder = false`, exactly what `POST /bot/ladder/leave` writes —
  * which stops a dead bot bleeding rating all night AND stops every opponent it is paired with banking free timeout
  * wins. The check lives in the batch rather than in `LadderScheduler` because the batch already visits every finished
  * rated game exactly once: it costs one extra bounded query on the rare timeout loss and nothing at all otherwise.
  * There is deliberately no auto-rejoin — returning is an explicit `POST /bot/ladder/join` by the owner, since an
  * automatic cooldown would just send a still-offline bot back out to bleed again.
  *
  * The park write is a separate transaction from the rating write it follows, so a crash between them leaves a rating
  * applied and the bot still on the ladder. That is deliberately not worth a shared transaction: the streak is
  * re-evaluated from scratch on the bot's next timeout loss, which an actually-offline bot supplies within a minute.
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
              parkIfStreakReached(white, row, whiteRating.onLadder) *>
              parkIfStreakReached(black, row, blackRating.onLadder)
          case _ => skip(row, "a participant is not a REGISTERED bot")
        }
      case (Some(_), Some(_), None) => skip(row, "no definite result")
      case _                        => skip(row, "a participant is not a bot identity")

  /** The auto-park check for one participant of a just-applied game (#150).
    *
    * Two cheap guards keep the history query off the hot path. Only the loser of a `timeout` game can start or extend a
    * streak, so every other outcome returns without touching the database. And a bot already off the ladder is skipped:
    * without that guard, the whole backlog a night of downtime produces — a game a minute, both mirror games of every
    * pairing — would re-park an already-parked bot once per game, each with its own UPDATE and its own log line,
    * drowning the one line that carries information. `onLadder` is the value `applyGame` read before the rating write;
    * that write never touches `on_ladder`, so it cannot be stale here.
    */
  private def parkIfStreakReached(bot: Principal.Bot, row: GameResultRow, onLadder: Boolean): IO[Unit] =
    if !onLadder || !RatingBatch.isTimeoutLossFor(row, bot) then IO.unit
    else
      evaluateStreak(bot).handleErrorWith: error =>
        Console[IO].errorln(s"[play][rating] auto-park check for ${bot.team}/${bot.name} failed: $error")

  /** Deliberately non-fatal to the tick. Unlike `applyRatingUpdate`, whose failure leaves the row unstamped and must
    * abort so the next tick retries it, this runs AFTER the rating write has committed: letting a transient query
    * timeout here propagate would abort the rest of the page and delay rating updates for unrelated bots, while
    * retrying it is pointless — the row is already stamped applied and will never come back through the queue. Nothing
    * is lost by giving up on this one game, because the streak is recomputed from scratch on the bot's next timeout
    * loss, which an actually-offline bot supplies within a minute.
    */
  private def evaluateStreak(bot: Principal.Bot): IO[Unit] =
    resultsStore
      .recentResultsFor(bot.externalId, RatingBatch.parkScanLimit(config.ladderTimeoutParkPairs))
      .flatMap: recent =>
        park(bot).whenA(RatingBatch.shouldPark(recent, bot, config.ladderTimeoutParkPairs))

  private def park(bot: Principal.Bot): IO[Unit] =
    botStore.setOnLadder(bot.team, bot.name, onLadder = false).flatMap {
      case Some(_) =>
        Console[IO].errorln(
          s"[play][rating] auto-parked ${bot.team}/${bot.name} off the ladder: " +
            s"${config.ladderTimeoutParkPairs} consecutive fully-timed-out pairings"
        )
      // Unreachable through `applyGame`, which already required `ratingOf` to find both participants — kept because a
      // future caller without that precondition should get a loud line, not a silent no-op.
      case None =>
        Console[IO].errorln(s"[play][rating] auto-park of ${bot.team}/${bot.name} found no registered bot")
    }

  private def skip(row: GameResultRow, why: String): IO[Unit] =
    Console[IO].errorln(s"[play][rating] game ${row.gameId.value} skipped ($why); stamped applied") *>
      ratingStore.markRatingApplied(row.gameId)

object RatingBatch:

  /** The stored `termination` value that counts towards a park streak, taken from the same mapping that WROTE the
    * column (`PgGameStore.finishedGameOf`) rather than spelled out again here: a literal would let the two drift, and
    * the failure mode of that drift is silent — auto-park would simply stop firing, with nothing logged anywhere.
    */
  private val TimeoutTermination: String = PlaysiteIngest.terminationOf(Termination.Timeout)

  /** Did `bot` lose this game on the clock? Nothing else feeds the park streak: a weak-but-live bot legitimately loses
    * by `king_captured` all day and must never be parked for it (#150).
    */
  private[rating] def isTimeoutLossFor(row: GameResultRow, bot: Principal.Bot): Boolean =
    val lost =
      if row.whiteExternalId == bot.externalId then row.result.contains(-1)
      else if row.blackExternalId == bot.externalId then row.result.contains(1)
      else false
    lost && row.termination == TimeoutTermination

  /** The park rule of #150 as a pure function of `recent` (a bot's newest-first results) — the streak logic is worth
    * testing without a database, and this is the whole of it.
    *
    * Counted by mirrored PAIRING, not by raw game: an offline bot flags both games of a pair within seconds of each
    * other, so a two-GAME threshold would really be one pairing and would trip on a single transient blip. Only ladder
    * games carry a `pairingId`, which is also what keeps a casual or challenge timeout from ever parking anyone. A
    * pairing with only one result recorded so far is not yet evidence either way and is skipped — that merely defers
    * the decision to the game that completes it.
    */
  private[rating] def shouldPark(recent: List[GameResultRow], bot: Principal.Bot, parkPairs: Int): Boolean =
    val newestFirst = recent
      .filter(_.pairingId.isDefined)
      .groupBy(_.pairingId)
      .collect { case (Some(_), games) if games.sizeIs == 2 => games }
      .toList
      // The game id breaks `finished_at` ties deterministically. Both are needed: the two mirror games of one pairing
      // finish within seconds, so equal timestamps are realistic, and the grouping above went through a Map, which
      // leaves the pre-sort order as hash order — a stable sort alone would make the answer depend on it.
      .sortBy(pairing => (pairing.map(_.finishedAt).max, pairing.map(_.gameId.value).max))(using
        Ordering[(Instant, String)].reverse
      )
      .take(parkPairs)
    // Size first, and load-bearing: `forall` on a shorter list is vacuously true, so a bot with only one pairing to its
    // name would otherwise be parked by a threshold of two.
    newestFirst.sizeIs == parkPairs && newestFirst.forall(_.forall(isTimeoutLossFor(_, bot)))

  /** How much history one park check reads. Bounded on purpose: it runs per applied timeout loss, and an on-ladder bot
    * accrues games at roughly a pairing a minute, so an unbounded scan grows without limit — precisely what
    * `recentResultsFor`'s default page size exists to prevent, and what its `UNION` of two `LIMIT`ed index scans is
    * built around.
    *
    * Generous rather than tight, because casual and challenge games share this history and dilute it: `parkPairs * 2`
    * is the exact ladder-only need, and any bound near it would silently stop parking as soon as a bot mixed in a few
    * non-ladder games. Erring wide is safe in one direction only, which is the useful one — truncation drops the OLDEST
    * games while the rule reads the NEWEST pairings, so a too-small window can only ever defer a park to the next
    * timeout loss, never cause a wrong one.
    */
  private[rating] def parkScanLimit(parkPairs: Int): Int =
    math.max(GameResultsStore.DefaultRecentLimit, parkPairs * 8)

  /** `interval` between queue polls; `batchSize` is the page size of one poll (the tick keeps paging until a short
    * page, so the backlog after downtime still drains in one tick); `ladderTimeoutParkPairs` is the auto-park threshold
    * (#150), counted in consecutive fully-timed-out mirrored pairings.
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
    * no ratings are ever recomputed — and, since auto-park rides on this batch, no bot is ever auto-parked either.
    *
    * `LADDER_TIMEOUT_PARK_PAIRS` is named for the feature it governs (the ladder), matching
    * `LADDER_INTERVAL_SECONDS`/`LADDER_MAX_CONCURRENT_PAIRS`, not for the component that happens to host the check. The
    * price of that choice is exactly the coupling above — a `LADDER_*` knob that does nothing without a `RATING_*` one
    * — so it is spelled out here and in AGENTS.md rather than left to be discovered on a new deployment.
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
