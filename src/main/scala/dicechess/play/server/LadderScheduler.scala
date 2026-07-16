package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.BotStore

import scala.concurrent.duration.*

/** Background matchmaker for the bot rating ladder (#102): on an interval, randomly selects two on-ladder bots and
  * starts a CRN mirrored pair (`GameRegistry.createMirroredPair`, #101) between them, up to a concurrency cap. Pairings
  * are **server-chosen only** — a bot cannot pick its opponent, so an owner can't farm rating with two colluding bots.
  * MVP policy: uniform-random subject to a one-step anti-repeat (don't immediately re-pick the pair just started,
  * unless it's the only pair available); rating-aware pairing (prefer close ratings / high RD) is a future refinement,
  * not required for the ladder to start producing results.
  */
final class LadderScheduler private (
    botStore: BotStore,
    registry: GameRegistry,
    events: BotEvents,
    inFlight: Ref[IO, Int],
    lastPairedWith: Ref[IO, Map[Principal.Bot, Principal.Bot]],
    config: LadderScheduler.Config
):

  /** One scheduling tick: if under the concurrency cap and at least two bots are on the ladder, start one mirrored
    * pair. A no-op — not an error — when there's nothing to do yet, whether too few bots are on the ladder or the cap
    * is already spent.
    */
  def tick: IO[Unit] =
    inFlight.get.flatMap: running =>
      if running >= config.maxConcurrentPairs then IO.unit
      else
        botStore.onLadderBots.flatMap: pool =>
          pickPair(pool).flatMap:
            case None               => IO.unit
            case Some((botA, botB)) => startPair(botA, botB)

  private def pickPair(pool: List[Principal.Bot]): IO[Option[(Principal.Bot, Principal.Bot)]] =
    if pool.size < 2 then IO.pure(None)
    else
      (shuffled(pool), lastPairedWith.get).mapN: (shuffledPool, recent) =>
        val candidates = shuffledPool.combinations(2).map(l => (l(0), l(1))).toList
        candidates.find((a, b) => recent.get(a).forall(_ != b)).orElse(candidates.headOption)

  private def startPair(botA: Principal.Bot, botB: Principal.Bot): IO[Unit] =
    inFlight.update(_ + 1) *>
      registry
        .createMirroredPair(botA, botB, config.timeControl)
        .flatMap:
          case Left(error) =>
            inFlight.update(_ - 1) *> Console[IO].errorln(s"[play][ladder] pairing failed: $error")
          case Right(pair) =>
            lastPairedWith.update(current => current + (botA -> botB) + (botB -> botA)) *>
              notifyBoth(botA, botB, pair) *>
              awaitBothEnded(pair).guarantee(inFlight.update(_ - 1)).start.void

  /** Push `gameStart` for both games of the pair to both bots — same advisory-push idiom as `Challenges.accept` — so a
    * listening bot learns immediately; a poll-only bot still discovers both games via `GET /bot/games`.
    */
  private def notifyBoth(botA: Principal.Bot, botB: Principal.Bot, pair: GameRegistry.MirroredPair): IO[Unit] =
    val started = List(BotEvent.GameStart(pair.gameAWhite.value), BotEvent.GameStart(pair.gameBWhite.value))
    List(botA, botB).traverse_(bot => started.traverse_(events.publish(bot, _)))

  private def awaitBothEnded(pair: GameRegistry.MirroredPair): IO[Unit] =
    (awaitEnded(pair.gameAWhite), awaitEnded(pair.gameBWhite)).parTupled.void

  private def awaitEnded(id: GameId): IO[Unit] =
    registry
      .get(id)
      .flatMap:
        case Some(room) => room.result.void
        case None       => IO.unit

  private def shuffled(pool: List[Principal.Bot]): IO[List[Principal.Bot]] =
    IO(scala.util.Random.shuffle(pool))

  /** Background scheduling loop; start once at boot. Same idiom as `Lobby.sweeper`/`Challenges.sweeper`. */
  def scheduler(interval: FiniteDuration = config.interval): IO[Unit] = (IO.sleep(interval) *> tick).foreverM

object LadderScheduler:

  /** `interval` between ticks; `maxConcurrentPairs` bounds simultaneously in-flight mirrored pairs; `timeControl` is
    * used for every scheduler-started game — **`Fischer`, not `Unlimited`/`PerMove`**, which are being removed from the
    * client (`rabestro/dicechess-play#99`) and would leave a ladder game with no clock enforcement.
    */
  final case class Config(interval: FiniteDuration, maxConcurrentPairs: Int, timeControl: TimeControl)

  object Config:
    val DefaultInterval: FiniteDuration = 60.seconds
    val DefaultMaxConcurrentPairs: Int  = 4
    val DefaultTimeControl: TimeControl = TimeControl.Fischer(300, 3)
    val Default: Config                 = Config(DefaultInterval, DefaultMaxConcurrentPairs, DefaultTimeControl)

  /** Opt-in by env, same "absence disables" idiom as `PgGameStore.configFromEnv`/`IngestDeliverer.configFromEnv`: with
    * `LADDER_INTERVAL_SECONDS` unset, the scheduler is never built and no ladder games start automatically.
    */
  def configFromEnv: Option[Config] =
    sys.env.get("LADDER_INTERVAL_SECONDS").filter(_.nonEmpty).flatMap(_.toIntOption).map { seconds =>
      val cap =
        sys.env.get("LADDER_MAX_CONCURRENT_PAIRS").flatMap(_.toIntOption).getOrElse(Config.DefaultMaxConcurrentPairs)
      Config(seconds.seconds, cap, Config.DefaultTimeControl)
    }

  def create(
      botStore: BotStore,
      registry: GameRegistry,
      events: BotEvents,
      config: Config = Config.Default
  ): IO[LadderScheduler] =
    (Ref.of[IO, Int](0), Ref.of[IO, Map[Principal.Bot, Principal.Bot]](Map.empty))
      .mapN((inFlight, lastPairedWith) =>
        new LadderScheduler(botStore, registry, events, inFlight, lastPairedWith, config)
      )
