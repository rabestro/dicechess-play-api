package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{PlayerKind, Principal, Seat, Seek, TimeControl}

import scala.concurrent.duration.*

class LobbySuite extends munit.CatsEffectSuite:

  private def lobby: IO[Lobby] = GameRegistry.create().flatMap(Lobby.create(_))

  private val alice  = Principal.Guest("alice")
  private val bob    = Principal.Guest("bob")
  private val botFoo = Principal.Bot("acme", "foo")

  /** Create a seek the test expects to be admitted, unwrapping the cap envelope. */
  private def mustCreate(l: Lobby, creator: Principal, tc: TimeControl): IO[(Seek, String)] =
    l.create(creator, tc).flatMap {
      case Right(created) => IO.pure(created)
      case Left(rejected) => IO.raiseError(RuntimeException(s"create unexpectedly rejected: $rejected"))
    }

  test("a created seek appears in the open list and reports Open to its creator"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Fischer(300, 3))
      open           <- l.list
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(open.map(_.id), List(seek.id))
      assertEquals(open.head.timeControl, TimeControl.Fischer(300, 3))
      assertEquals(status, Some(Lobby.SeekStatus.Open))

  test("a guest seek is an anonymous Human; a bot seek carries its public name"):
    for
      l          <- lobby
      (human, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      (bot, _)   <- mustCreate(l, botFoo, TimeControl.Unlimited)
    yield
      assertEquals((human.kind, human.name), (PlayerKind.Human, None))
      assertEquals((bot.kind, bot.name), (PlayerKind.Bot, Some("acme foo")))

  test("status requires the creator's secret"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      wrong     <- l.status(seek.id, "nope")
    yield assertEquals(wrong, None)

  test("accepting seats a game and gives each side its own seat token; the seek leaves the open list"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      accepted       <- l.accept(seek.id, bob)
      creatorStatus  <- l.status(seek.id, secret)
      open           <- l.list
    yield
      val accepterMatch = accepted.toOption.get
      assert(accepterMatch.gameId.nonEmpty)
      assert(accepterMatch.token.nonEmpty)
      creatorStatus match
        case Some(Lobby.SeekStatus.Matched(gameId, creatorToken, creatorSeat)) =>
          assertEquals(gameId, accepterMatch.gameId) // both players join the same game
          assert(creatorToken.nonEmpty)
          assertNotEquals(creatorToken, accepterMatch.token) // but hold different seats
          assertNotEquals(creatorSeat, accepterMatch.seat)   // opposite seats, whichever way the coin fell
        case other => fail(s"expected Matched, got $other")
      assertEquals(open, Nil)

  test("a second accept is refused, and accepting an unknown seek is NotFound"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
      first     <- l.accept(seek.id, bob)
      second    <- l.accept(seek.id, Principal.Guest("carol"))
      unknown   <- l.accept("seek-999", bob)
    yield
      assert(first.isRight)
      assertEquals(second, Left(Lobby.Rejected.AlreadyTaken))
      assertEquals(unknown, Left(Lobby.Rejected.NotFound))

  test("a creator cannot accept its own seek"):
    for
      l         <- lobby
      (seek, _) <- mustCreate(l, botFoo, TimeControl.Unlimited)
      own       <- l.accept(seek.id, botFoo)
      stillOpen <- l.list
    yield
      assertEquals(own, Left(Lobby.Rejected.OwnSeek))
      assertEquals(stillOpen.map(_.id), List(seek.id)) // the failed accept must not claim the seek

  test("a bot's open seeks are capped; a claimed seek frees the slot"):
    for
      reg      <- GameRegistry.create()
      l        <- Lobby.create(reg, maxOpenSeeksPerBot = 2)
      _        <- mustCreate(l, botFoo, TimeControl.Unlimited)
      (s2, _)  <- mustCreate(l, botFoo, TimeControl.Unlimited)
      overflow <- l.create(botFoo, TimeControl.Unlimited)
      // Guests are never capped.
      _     <- mustCreate(l, alice, TimeControl.Unlimited)
      _     <- l.accept(s2.id, bob)
      retry <- l.create(botFoo, TimeControl.Unlimited)
    yield
      assertEquals(overflow, Left(Lobby.CreateRejected.TooManyOpenSeeks))
      assert(retry.isRight, s"a matched seek must free the cap slot: $retry")

  test("cancel removes the seek only with the right secret"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      badCancel      <- l.cancel(seek.id, "nope")
      stillThere     <- l.list
      goodCancel     <- l.cancel(seek.id, secret)
      gone           <- l.list
    yield
      assertEquals(badCancel, false)
      assertEquals(stillThere.map(_.id), List(seek.id))
      assertEquals(goodCancel, true)
      assertEquals(gone, Nil)

  test("a matched seek can't be cancelled (its game already exists); the creator can still read its token"):
    for
      l              <- lobby
      (seek, secret) <- mustCreate(l, alice, TimeControl.Unlimited)
      _              <- l.accept(seek.id, bob)
      cancelled      <- l.cancel(seek.id, secret)
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(cancelled, false)
      assert(status.exists {
        case Lobby.SeekStatus.Matched(_, _, _) => true
        case _                                 => false
      })

  test("the sweep drops a seek whose creator has gone quiet past the TTL"):
    for
      reg  <- GameRegistry.create()
      l    <- Lobby.create(reg, ttl = 50.millis)
      _    <- mustCreate(l, alice, TimeControl.Unlimited)
      _    <- IO.sleep(120.millis)
      _    <- l.sweep
      gone <- l.list
    yield assertEquals(gone, Nil)

  test("a bot seek outlives the guest TTL — sized for a poll-only bot on a lazy timer"):
    for
      reg  <- GameRegistry.create()
      l    <- Lobby.create(reg, ttl = 50.millis, botTtl = 10.seconds)
      _    <- mustCreate(l, alice, TimeControl.Unlimited)
      _    <- mustCreate(l, botFoo, TimeControl.Unlimited)
      _    <- IO.sleep(120.millis)
      _    <- l.sweep
      left <- l.list
    yield
      // The quiet guest's seek is swept; the bot's standing offer survives its longer TTL.
      assertEquals(left.map(_.kind), List(PlayerKind.Bot))

  test("accepting open seeks results in random color assignment, correctly reported to the accepter"):
    val trials = 20
    for
      reg     <- GameRegistry.create()
      results <- List.fill(trials)(()).traverse { _ =>
        for
          l         <- Lobby.create(reg)
          (seek, _) <- mustCreate(l, alice, TimeControl.Unlimited)
          accepted  <- l.accept(seek.id, bob)
          reportedAccepterSeat = accepted.toOption.get.seat
          gameId               = accepted.toOption.get.gameId
          games <- reg.gamesFor(alice)
          room = games.find(_._1.value == gameId).get._2
          seating <- room.seating
          aliceSeat = seating.find(_._2 == alice).get._1
          bobSeat   = seating.find(_._2 == bob).get._1
        yield (aliceSeat, reportedAccepterSeat, bobSeat)
      }
    yield
      val aliceSeats = results.map(_._1)
      assert(aliceSeats.contains(Seat.White), "Should assign creator to White in at least one trial")
      assert(aliceSeats.contains(Seat.Black), "Should assign creator to Black in at least one trial")
      // The seat named in the accept response must match the room's own seating, every trial —
      // not just "sometimes White, sometimes Black" but correctly reported each specific time.
      results.foreach { case (_, reported, bobSeat) =>
        assertEquals(reported, bobSeat, "SeekMatch.seat must match the accepter's actual room seat")
      }
