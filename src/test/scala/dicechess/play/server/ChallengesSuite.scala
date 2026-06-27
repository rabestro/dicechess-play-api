package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{BotEvent, Principal}

import scala.concurrent.duration.*

class ChallengesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")

  test("creating a challenge notifies the target's event stream"):
    val received =
      for
        events     <- BotEvents.create
        challenges <- Challenges.create(events)
        event      <- events
          .stream(bob)
          .head
          .compile
          .lastOrError
          .background
          .use: waiting =>
            // Give the stream a moment to register before publishing, then read the delivered event.
            IO.sleep(150.millis) *> challenges.create(alice, bob) *> waiting.flatMap(_.embedNever)
      yield event

    received
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("challenge was not delivered to the target")))
      .map:
        case BotEvent.ChallengeReceived(id, challenger) =>
          assert(id.nonEmpty)
          assertEquals(challenger, alice)
