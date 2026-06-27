package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{BotEvent, Principal}

import scala.concurrent.duration.*

class ChallengesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")

  private def harness: IO[(BotEvents, Challenges)] =
    for
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry)
    yield (events, challenges)

  test("creating a challenge notifies the target's event stream"):
    harness
      .flatMap: (events, challenges) =>
        events
          .stream(bob)
          .head
          .compile
          .lastOrError
          .background
          .use: waiting =>
            IO.sleep(150.millis) *> challenges.create(alice, bob) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("challenge not delivered")))
      .map:
        case BotEvent.ChallengeReceived(id, challenger) =>
          assert(id.nonEmpty)
          assertEquals(challenger, alice)
        case other => fail(s"expected ChallengeReceived, got: $other")

  test("accepting a challenge seats a game and notifies the challenger"):
    harness
      .flatMap: (events, challenges) =>
        challenges
          .create(alice, bob)
          .flatMap: ch =>
            events
              .stream(alice)
              .head
              .compile
              .lastOrError
              .background
              .use: waiting =>
                IO.sleep(150.millis) *> challenges
                  .accept(bob, ch.id)
                  .flatMap: result =>
                    IO(assert(result.isRight, s"accept should succeed: $result")) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("gameStart not delivered")))
      .map:
        case BotEvent.GameStart(gameId) => assert(gameId.nonEmpty)
        case other                      => fail(s"expected GameStart, got: $other")

  test("declining a challenge notifies the challenger"):
    harness
      .flatMap: (events, challenges) =>
        challenges
          .create(alice, bob)
          .flatMap: ch =>
            events
              .stream(alice)
              .head
              .compile
              .lastOrError
              .background
              .use: waiting =>
                IO.sleep(150.millis) *> challenges.decline(bob, ch.id) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("decline not delivered")))
      .map:
        case BotEvent.ChallengeDeclined(id) => assert(id.nonEmpty)
        case other                          => fail(s"expected ChallengeDeclined, got: $other")

  test("only the challenged bot may accept"):
    harness
      .flatMap: (_, challenges) =>
        challenges.create(alice, bob).flatMap(ch => challenges.accept(alice, ch.id))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotYours): Either[Challenges.Rejected, String]))

  test("accepting an unknown challenge is NotFound"):
    harness
      .flatMap((_, challenges) => challenges.accept(bob, "nope"))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String]))
