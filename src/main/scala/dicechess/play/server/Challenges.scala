package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{BotEvent, Challenge, Principal}

/** Pending bot-to-bot challenges. Creating one notifies the target's event stream; accept/decline arrive in the next
  * slice (where accept seats a game and emits `gameStart`).
  */
final class Challenges private (pending: Ref[IO, Map[String, Challenge]], events: BotEvents, nextId: Ref[IO, Long]):

  def create(challenger: Principal, target: Principal): IO[Challenge] =
    for
      n <- nextId.getAndUpdate(_ + 1)
      challenge = Challenge(s"challenge-$n", challenger, target)
      _ <- pending.update(_.updated(challenge.id, challenge))
      _ <- events.publish(target, BotEvent.ChallengeReceived(challenge.id, challenger))
    yield challenge

object Challenges:
  def create(events: BotEvents): IO[Challenges] =
    (Ref.of[IO, Map[String, Challenge]](Map.empty), Ref.of[IO, Long](0L)).mapN(new Challenges(_, events, _))
