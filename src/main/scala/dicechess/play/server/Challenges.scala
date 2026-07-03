package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{BotEvent, Challenge, Principal, TimeControl}

import scala.concurrent.duration.*

/** Pending bot-to-bot challenges. Creating one notifies the target's live account stream AND leaves the entry
  * discoverable via `GET /bot/challenges` until it expires — so a bot that was offline when the push happened can still
  * find and claim it by polling. Accepting seats a game and emits `gameStart` to both bots; declining notifies the
  * challenger. Only the challenged bot may accept or decline. A background [[sweeper]] expires unclaimed entries,
  * declining them back to the challenger, so the map never grows without bound.
  */
final class Challenges private (
    pending: Ref[IO, Map[String, Challenges.Entry]],
    events: BotEvents,
    registry: GameRegistry,
    nextId: Ref[IO, Long],
    ttl: FiniteDuration,
    maxPendingPerBot: Int
):
  import Challenges.*

  /** Create a pending challenge, or say why it is refused (self-challenge, per-challenger cap). The result carries
    * whether the target currently holds an account stream — advisory only: an offline target can still discover the
    * entry via `GET /bot/challenges` before the TTL expires.
    */
  def create(
      challenger: Principal,
      target: Principal,
      timeControl: TimeControl = TimeControl.Unlimited
  ): IO[Either[CreateRejected, Created]] =
    if challenger == target then IO.pure(Left(CreateRejected.SelfChallenge))
    else
      (nextId.getAndUpdate(_ + 1), IO.monotonic).flatMapN: (n, now) =>
        val challenge = Challenge(s"challenge-$n", challenger, target, timeControl)
        pending
          .modify: current =>
            if current.values.count(_.challenge.challenger == challenger) >= maxPendingPerBot then
              (current, Left(CreateRejected.TooManyPending))
            else (current.updated(challenge.id, Entry(challenge, now)), Right(challenge))
          .flatMap:
            case Left(rejected) => IO.pure(Left(rejected))
            case Right(created) =>
              events.publish(target, BotEvent.ChallengeReceived(created.id, challenger)) *>
                events.online(target).map(online => Right(Created(created, online)))

  /** The caller's pending challenges: addressed to it (`in` — accept/decline by id) and created by it (`out` — watch
    * their fate). The polling counterpart of the live `ChallengeReceived`/`ChallengeDeclined` pushes.
    */
  def listFor(principal: Principal): IO[(List[Challenge], List[Challenge])] =
    pending.get.map: current =>
      val all = current.values.toList.sortBy(_.createdAt).map(_.challenge)
      (all.filter(_.target == principal), all.filter(_.challenger == principal))

  /** The challenged bot accepts: seat a game (challenger = White, target = Black) and tell both bots its id. */
  def accept(by: Principal, id: String): IO[Either[Rejected, String]] =
    claim(by, id).flatMap {
      case Left(rejected)   => IO.pure(Left(rejected))
      case Right(challenge) =>
        registry.create(challenge.challenger, challenge.target, challenge.timeControl).flatMap {
          case Left(error)        => IO.pure(Left(Rejected.Failed(error)))
          case Right((gameId, _)) =>
            val started = BotEvent.GameStart(gameId.value)
            events.publish(challenge.challenger, started) *>
              events.publish(challenge.target, started).as(Right(gameId.value))
        }
    }

  /** The challenged bot declines: drop it and tell the challenger. */
  def decline(by: Principal, id: String): IO[Either[Rejected, Unit]] =
    claim(by, id).flatMap {
      case Left(rejected)   => IO.pure(Left(rejected))
      case Right(challenge) => events.publish(challenge.challenger, BotEvent.ChallengeDeclined(id)).as(Right(()))
    }

  /** Expire entries past the TTL, declining each back to its challenger — a listening one learns promptly, a polling
    * one sees the entry vanish from its `out` list.
    */
  def sweep: IO[Unit] =
    IO.monotonic
      .flatMap: now =>
        pending.modify: current =>
          val (alive, expired) = current.partition((_, entry) => now - entry.createdAt < ttl)
          (alive, expired.values.toList)
      .flatMap(_.traverse_ { entry =>
        events.publish(entry.challenge.challenger, BotEvent.ChallengeDeclined(entry.challenge.id))
      })

  /** Background TTL-sweep loop; start once at boot. */
  def sweeper(interval: FiniteDuration = SweepInterval): IO[Unit] = (IO.sleep(interval) *> sweep).foreverM

  /** Atomically remove a pending challenge if `by` is its target — so two accepts can't both seat a game. */
  private def claim(by: Principal, id: String): IO[Either[Rejected, Challenge]] =
    pending.modify { current =>
      current.get(id) match
        case None                                        => (current, Left(Rejected.NotFound))
        case Some(entry) if entry.challenge.target != by => (current, Left(Rejected.NotYours))
        case Some(entry)                                 => (current.removed(id), Right(entry.challenge))
    }

object Challenges:

  /** How long an unclaimed challenge stays pending (and discoverable via `GET /bot/challenges`). Long enough for a
    * polling bot on a lazy timer to find it, short enough that a challenge to a gone bot doesn't strand its
    * challenger's cap for long.
    */
  val DefaultTtl: FiniteDuration = 5.minutes

  /** How often the background sweep runs. */
  private val SweepInterval: FiniteDuration = 15.seconds

  /** Cap on one bot's outstanding challenges — bounds the pending map against a create-loop. */
  val DefaultMaxPendingPerBot: Int = 10

  /** A pending challenge plus its creation stamp (monotonic, for the TTL sweep). */
  final private case class Entry(challenge: Challenge, createdAt: FiniteDuration)

  /** Why a create was refused. */
  enum CreateRejected:
    case SelfChallenge  // a bot cannot challenge itself
    case TooManyPending // the challenger is at its pending cap

  /** A created pending challenge, plus whether the target currently holds an account stream (advisory). */
  final case class Created(challenge: Challenge, targetOnline: Boolean)

  /** Why an accept/decline was refused. */
  enum Rejected:
    case NotFound               // no pending challenge with that id
    case NotYours               // the caller is not the challenged bot
    case Failed(reason: String) // the game could not be seated

  def create(
      events: BotEvents,
      registry: GameRegistry,
      ttl: FiniteDuration = DefaultTtl,
      maxPendingPerBot: Int = DefaultMaxPendingPerBot
  ): IO[Challenges] =
    (Ref.of[IO, Map[String, Entry]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new Challenges(_, events, registry, _, ttl, maxPendingPerBot))
