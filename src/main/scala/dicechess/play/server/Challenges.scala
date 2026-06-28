package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{BotEvent, Challenge, Principal, TimeControl}

/** Pending bot-to-bot challenges. Creating one notifies the target; accepting it seats a game and emits `gameStart` to
  * both bots; declining notifies the challenger. Only the challenged bot may accept or decline.
  */
final class Challenges private (
    pending: Ref[IO, Map[String, Challenge]],
    events: BotEvents,
    registry: GameRegistry,
    nextId: Ref[IO, Long]
):
  import Challenges.*

  def create(
      challenger: Principal,
      target: Principal,
      timeControl: TimeControl = TimeControl.Unlimited
  ): IO[Challenge] =
    for
      n <- nextId.getAndUpdate(_ + 1)
      challenge = Challenge(s"challenge-$n", challenger, target, timeControl)
      _ <- pending.update(_.updated(challenge.id, challenge))
      _ <- events.publish(target, BotEvent.ChallengeReceived(challenge.id, challenger))
    yield challenge

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

  /** Atomically remove a pending challenge if `by` is its target — so two accepts can't both seat a game. */
  private def claim(by: Principal, id: String): IO[Either[Rejected, Challenge]] =
    pending.modify { current =>
      current.get(id) match
        case None                        => (current, Left(Rejected.NotFound))
        case Some(ch) if ch.target != by => (current, Left(Rejected.NotYours))
        case Some(ch)                    => (current.removed(id), Right(ch))
    }

object Challenges:

  /** Why an accept/decline was refused. */
  enum Rejected:
    case NotFound               // no pending challenge with that id
    case NotYours               // the caller is not the challenged bot
    case Failed(reason: String) // the game could not be seated

  def create(events: BotEvents, registry: GameRegistry): IO[Challenges] =
    (Ref.of[IO, Map[String, Challenge]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new Challenges(_, events, registry, _))
