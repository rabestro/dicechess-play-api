package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{BotEvent, Challenge, Principal}

import scala.concurrent.duration.*

class ChallengesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")
  private val carol = Principal.Bot("acme", "carol")

  private def harness(
      ttl: FiniteDuration = Challenges.DefaultTtl,
      maxPendingPerBot: Int = Challenges.DefaultMaxPendingPerBot,
      admitBoth: (Principal, Principal) => IO[Boolean] = Challenges.AdmitAny
  ): IO[(BotEvents, Challenges)] =
    for
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry, ttl, maxPendingPerBot, admitBoth)
    yield (events, challenges)

  /** Create a challenge that the test expects to be admitted, unwrapping the hygiene envelope. */
  private def mustCreate(challenges: Challenges, from: Principal, to: Principal): IO[Challenge] =
    challenges
      .create(from, to)
      .flatMap:
        case Right(created) => IO.pure(created.challenge)
        case Left(rejected) => IO.raiseError(RuntimeException(s"create unexpectedly rejected: $rejected"))

  test("creating a challenge notifies the target's event stream"):
    harness()
      .flatMap: (events, challenges) =>
        events
          .stream(bob)
          .head
          .compile
          .lastOrError
          .background
          .use: waiting =>
            IO.sleep(150.millis) *> mustCreate(challenges, alice, bob) *> waiting.flatMap(_.embedNever)
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("challenge not delivered")))
      .map:
        case BotEvent.ChallengeReceived(id, challenger) =>
          assert(id.nonEmpty)
          assertEquals(challenger, alice)
        case other => fail(s"expected ChallengeReceived, got: $other")

  test("creation reports whether the target holds a live account stream"):
    harness()
      .flatMap: (events, challenges) =>
        for
          // Nobody is streaming: offline, but the challenge is still created (discoverable by polling).
          offline <- challenges.create(alice, bob)
          online  <- events
            .stream(bob)
            .head
            .compile
            .last
            .background
            .use(_ => IO.sleep(150.millis) *> challenges.create(carol, bob))
        yield
          assertEquals(offline.map(_.targetOnline), Right(false))
          assertEquals(online.map(_.targetOnline), Right(true))

  test("accepting a challenge seats a game and notifies the challenger"):
    harness()
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob)
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
    harness()
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob)
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
    harness()
      .flatMap: (_, challenges) =>
        mustCreate(challenges, alice, bob).flatMap(ch => challenges.accept(alice, ch.id))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotYours): Either[Challenges.Rejected, String]))

  test("accepting an unknown challenge is NotFound"):
    harness()
      .flatMap((_, challenges) => challenges.accept(bob, "nope"))
      .map(result => assertEquals(result, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String]))

  test("listFor separates challenges addressed to the caller from those it created"):
    harness()
      .flatMap: (_, challenges) =>
        for
          toBob     <- mustCreate(challenges, alice, bob)
          toAlice   <- mustCreate(challenges, bob, alice)
          fromCarol <- mustCreate(challenges, carol, alice)
          (in, out) <- challenges.listFor(alice)
        yield
          assertEquals(in.map(_.id).toSet, Set(toAlice.id, fromCarol.id))
          assertEquals(out.map(_.id), List(toBob.id))

  test("a claimed challenge disappears from the listings"):
    harness()
      .flatMap: (_, challenges) =>
        for
          ch       <- mustCreate(challenges, alice, bob)
          _        <- challenges.accept(bob, ch.id)
          (in, _)  <- challenges.listFor(bob)
          (_, out) <- challenges.listFor(alice)
        yield
          assertEquals(in, Nil)
          assertEquals(out, Nil)

  test("a bot cannot challenge itself"):
    harness()
      .flatMap((_, challenges) => challenges.create(alice, alice))
      .map(result => assertEquals(result.left.map(identity), Left(Challenges.CreateRejected.SelfChallenge)))

  test("the per-challenger pending cap rejects the overflow and frees up on decline"):
    harness(maxPendingPerBot = 2)
      .flatMap: (_, challenges) =>
        for
          _        <- mustCreate(challenges, alice, bob)
          second   <- mustCreate(challenges, alice, carol)
          overflow <- challenges.create(alice, Principal.Bot("acme", "dave"))
          _        <- challenges.decline(carol, second.id)
          retry    <- challenges.create(alice, Principal.Bot("acme", "dave"))
        yield
          assertEquals(overflow.left.map(identity), Left(Challenges.CreateRejected.TooManyPending))
          assert(retry.isRight, s"after a decline the cap must free up: $retry")

  test("the TTL sweep expires unclaimed challenges and declines them back to the challenger"):
    harness(ttl = 100.millis)
      .flatMap: (events, challenges) =>
        mustCreate(challenges, alice, bob).flatMap: ch =>
          events
            .stream(alice)
            .head
            .compile
            .lastOrError
            .background
            .use: waiting =>
              for
                _        <- IO.sleep(250.millis) // past the TTL (and lets the subscriber register)
                _        <- challenges.sweep
                (in, _)  <- challenges.listFor(bob)
                (_, out) <- challenges.listFor(alice)
                declined <- waiting.flatMap(_.embedNever)
                notFound <- challenges.accept(bob, ch.id)
              yield
                assertEquals(in, Nil)
                assertEquals(out, Nil)
                assertEquals(declined, BotEvent.ChallengeDeclined(ch.id))
                assertEquals(notFound, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String])
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("expiry was not delivered")))

  test("an accept refused for capacity leaves the challenge pending, so it can be taken once a game ends (#189)"):
    // A gate that refuses exactly once, then admits: the retry must succeed against the SAME challenge id, which is
    // only possible if the first refusal did not consume it.
    IO.ref(true).flatMap { refuse =>
      harness(admitBoth = (_, _) => refuse.getAndSet(false).map(!_))
        .flatMap: (_, challenges) =>
          for
            ch      <- mustCreate(challenges, alice, bob)
            busy    <- challenges.accept(bob, ch.id)
            stillIn <- challenges.listFor(bob).map(_._1.map(_.id))
            retry   <- challenges.accept(bob, ch.id)
            gone    <- challenges.listFor(bob).map(_._1)
          yield
            assertEquals(busy, Left(Challenges.Rejected.Busy): Either[Challenges.Rejected, String])
            assertEquals(stillIn, List(ch.id), "a busy refusal must not consume the challenge")
            assert(retry.isRight, s"the same challenge must be acceptable once capacity frees up: $retry")
            assertEquals(gone, Nil, "the successful accept claims it")
    }

  test("a capacity refusal is distinguishable from an unknown or someone else's challenge (#189)"):
    harness(admitBoth = (_, _) => IO.pure(false))
      .flatMap: (_, challenges) =>
        for
          ch      <- mustCreate(challenges, alice, bob)
          busy    <- challenges.accept(bob, ch.id)
          unknown <- challenges.accept(bob, "challenge-nope")
          notMine <- challenges.accept(carol, ch.id)
        yield
          assertEquals(busy, Left(Challenges.Rejected.Busy): Either[Challenges.Rejected, String])
          // Identity checks run first: a stranger learns nothing about anyone's capacity.
          assertEquals(unknown, Left(Challenges.Rejected.NotFound): Either[Challenges.Rejected, String])
          assertEquals(notMine, Left(Challenges.Rejected.NotYours): Either[Challenges.Rejected, String])
