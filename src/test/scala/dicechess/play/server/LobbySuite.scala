package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Principal, TimeControl}

import scala.concurrent.duration.*

class LobbySuite extends munit.CatsEffectSuite:

  private def lobby: IO[Lobby] = GameRegistry.create().flatMap(Lobby.create(_))

  private val alice = Principal.Guest("alice")
  private val bob   = Principal.Guest("bob")

  test("a created seek appears in the open list and reports Open to its creator"):
    for
      l              <- lobby
      (seek, secret) <- l.create(alice, TimeControl.Fischer(300, 3))
      open           <- l.list
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(open.map(_.id), List(seek.id))
      assertEquals(open.head.timeControl, TimeControl.Fischer(300, 3))
      assertEquals(status, Some(Lobby.SeekStatus.Open))

  test("status requires the creator's secret"):
    for
      l         <- lobby
      (seek, _) <- l.create(alice, TimeControl.Unlimited)
      wrong     <- l.status(seek.id, "nope")
    yield assertEquals(wrong, None)

  test("accepting seats a game and gives each side its own seat token; the seek leaves the open list"):
    for
      l              <- lobby
      (seek, secret) <- l.create(alice, TimeControl.Unlimited)
      accepted       <- l.accept(seek.id, bob)
      creatorStatus  <- l.status(seek.id, secret)
      open           <- l.list
    yield
      val accepterMatch = accepted.toOption.get
      assert(accepterMatch.gameId.nonEmpty)
      assert(accepterMatch.token.nonEmpty)
      creatorStatus match
        case Some(Lobby.SeekStatus.Matched(gameId, creatorToken)) =>
          assertEquals(gameId, accepterMatch.gameId) // both players join the same game
          assert(creatorToken.nonEmpty)
          assertNotEquals(creatorToken, accepterMatch.token) // but hold different seats
        case other => fail(s"expected Matched, got $other")
      assertEquals(open, Nil)

  test("a second accept is refused, and accepting an unknown seek is NotFound"):
    for
      l         <- lobby
      (seek, _) <- l.create(alice, TimeControl.Unlimited)
      first     <- l.accept(seek.id, bob)
      second    <- l.accept(seek.id, Principal.Guest("carol"))
      unknown   <- l.accept("seek-999", bob)
    yield
      assert(first.isRight)
      assertEquals(second, Left(Lobby.Rejected.AlreadyTaken))
      assertEquals(unknown, Left(Lobby.Rejected.NotFound))

  test("cancel removes the seek only with the right secret"):
    for
      l              <- lobby
      (seek, secret) <- l.create(alice, TimeControl.Unlimited)
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
      (seek, secret) <- l.create(alice, TimeControl.Unlimited)
      _              <- l.accept(seek.id, bob)
      cancelled      <- l.cancel(seek.id, secret)
      status         <- l.status(seek.id, secret)
    yield
      assertEquals(cancelled, false)
      assert(status.exists {
        case Lobby.SeekStatus.Matched(_, _) => true
        case _                              => false
      })

  test("the sweep drops a seek whose creator has gone quiet past the TTL"):
    for
      reg  <- GameRegistry.create()
      l    <- Lobby.create(reg, ttl = 50.millis)
      _    <- l.create(alice, TimeControl.Unlimited)
      _    <- IO.sleep(120.millis)
      _    <- l.sweep
      gone <- l.list
    yield assertEquals(gone, Nil)
