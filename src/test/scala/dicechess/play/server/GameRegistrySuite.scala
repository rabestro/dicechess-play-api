package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.engine.search.{BotRegistry, SearchAlgorithm}
import dicechess.play.core.*
import dicechess.play.game.{BotConnection, GameRoom}
import dicechess.play.store.{GameSnapshot, GameStore}

import scala.concurrent.duration.*

/** The rated/casual policy (#97): a game is rated only when both requested AND every participant is non-anonymous.
  * `isRated` itself is checked directly as a pure matrix; the `create` tests confirm the policy actually reaches the
  * persisted snapshot, not just the in-memory decision.
  */
class GameRegistrySuite extends munit.CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private def capturingStore(written: Ref[IO, Vector[GameSnapshot]]): GameStore = new GameStore:
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = written.update(_ :+ snapshot)
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

  /** Keyed by game id, since a mirrored pair writes two independent games through the same store. */
  private def capturingStoreById(written: Ref[IO, Map[GameId, GameSnapshot]]): GameStore = new GameStore:
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = written.update(_.updated(id, snapshot))
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

  /** Drive a room to completion with a bot on each seat — same idiom as `GameRoomPersistenceSuite`'s full-game test.
    * `BotConnection.run` submits its own dice seed first, but the mirrored pair has already preset both seats' seeds,
    * and the room's own `SubmitSeed` gate is idempotent-per-seat — so the bot's seed is silently a no-op and the fixed
    * seed stands.
    */
  private def playToEnd(room: GameRoom, white: Principal, black: Principal, algorithm: SearchAlgorithm): IO[Unit] =
    val whiteConn = BotConnection(white, Seat.White, algorithm)
    val blackConn = BotConnection(black, Seat.Black, algorithm)
    (whiteConn.run(room).background, blackConn.run(room).background).tupled
      .use(_ => room.start *> room.result)
      .void
      .timeoutTo(20.seconds, IO.raiseError(RuntimeException("mirrored game did not finish in time")))

  // ── isRated: the pure policy matrix ──────────────────────────────────────────

  test("two registered bots, rated requested -> rated"):
    assert(GameRegistry.isRated(alice, bob, requested = true))

  test("two registered bots, rated NOT requested -> casual"):
    assert(!GameRegistry.isRated(alice, bob, requested = false))

  test("a guest on either side forces casual even when rated was requested"):
    assert(!GameRegistry.isRated(Principal.Guest("g1"), bob, requested = true))
    assert(!GameRegistry.isRated(alice, Principal.Guest("g2"), requested = true))

  test("an anonymous (team=anon) bot on either side forces casual even when rated was requested"):
    assert(!GameRegistry.isRated(Principal.Bot(BotAuth.AnonTeam, "x"), bob, requested = true))
    assert(!GameRegistry.isRated(alice, Principal.Bot(BotAuth.AnonTeam, "y"), requested = true))

  test("two guests are always casual, requested or not"):
    assert(!GameRegistry.isRated(Principal.Guest("g1"), Principal.Guest("g2"), requested = true))
    assert(!GameRegistry.isRated(Principal.Guest("g1"), Principal.Guest("g2"), requested = false))

  test("two registered human accounts (User) can be rated"):
    assert(GameRegistry.isRated(Principal.User("u1"), Principal.User("u2"), requested = true))

  test("a registered human and a registered bot can be rated together"):
    assert(GameRegistry.isRated(Principal.User("u1"), alice, requested = true))

  // ── create: the policy actually reaches the persisted snapshot ──────────────

  test("create with requestedRated=true between two registered bots persists a rated snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(alice, bob, requestedRated = true).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(snaps.headOption.exists(_.rated.contains(true)), "the creation snapshot must be marked rated")
            }
        }
      }
    }

  test("create with requestedRated=true is silently downgraded to casual when one side is a guest"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(Principal.Guest("g1"), bob, requestedRated = true).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(
                snaps.headOption.exists(_.rated.contains(false)),
                "a guest participant must force the snapshot casual"
              )
            }
        }
      }
    }

  test("create without requestedRated defaults to a casual snapshot even between two registered bots"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRegistry.create(store = capturingStore(written)).flatMap { registry =>
        registry.create(alice, bob).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"create failed: $error"))
          case Right(_)    =>
            written.get.map { snaps =>
              assert(
                snaps.headOption.exists(_.rated.contains(false)),
                "omitting requestedRated must default to casual"
              )
            }
        }
      }
    }

  // ── createMirroredPair: CRN (#101) ───────────────────────────────────────────

  test("createMirroredPair: identical dice sequence per ply, colours swapped, sharing one pairing id"):
    val greedy = BotRegistry.getAlgorithm("greedy").get
    Ref.of[IO, Map[GameId, GameSnapshot]](Map.empty).flatMap { written =>
      GameRegistry.create(store = capturingStoreById(written)).flatMap { registry =>
        registry.createMirroredPair(alice, bob, TimeControl.Unlimited).flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"createMirroredPair failed: $error"))
          case Right(pair) =>
            for
              roomA <- registry.get(pair.gameAWhite).map(_.getOrElse(fail("game A not registered")))
              roomB <- registry.get(pair.gameBWhite).map(_.getOrElse(fail("game B not registered")))
              _     <- playToEnd(roomA, alice, bob, greedy) // A = White, B = Black
              _     <- playToEnd(roomB, bob, alice, greedy) // mirror: B = White, A = Black
              snaps <- written.get
            yield
              val snapA = snaps.getOrElse(pair.gameAWhite, fail("game A snapshot missing"))
              val snapB = snaps.getOrElse(pair.gameBWhite, fail("game B snapshot missing"))
              assertEquals(snapA.pairingId, Some(pair.pairingId))
              assertEquals(snapB.pairingId, Some(pair.pairingId))
              val diceA     = snapA.turns.map(_.dice)
              val diceB     = snapB.turns.map(_.dice)
              val commonLen = math.min(diceA.size, diceB.size)
              assert(commonLen > 0, s"expected at least one completed turn in both games, got $diceA / $diceB")
              assertEquals(
                diceA.take(commonLen),
                diceB.take(commonLen),
                "dice must be identical ply-for-ply once colours are swapped"
              )
        }
      }
    }

  test("createMirroredPair: neither game's reveal becomes public before both have concluded (#115)"):
    GameRegistry.create().flatMap { registry =>
      registry.createMirroredPair(alice, bob, TimeControl.Unlimited).flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"createMirroredPair failed: $error"))
        case Right(pair) =>
          for
            roomA <- registry.get(pair.gameAWhite).map(_.getOrElse(fail("game A not registered")))
            roomB <- registry.get(pair.gameBWhite).map(_.getOrElse(fail("game B not registered")))
            // End game A only; game B is still active.
            _      <- roomA.submit(Seat.White, GameCommand.Resign)
            _      <- roomA.result.timeoutTo(5.seconds, IO.raiseError(RuntimeException("game A never ended")))
            snapA1 <- roomA.snapshot
            _ = assertEquals(snapA1.seed, None, "game A must withhold its reveal while its partner is still active")
            _ = assertEquals(snapA1.clientSeeds, None, "and withhold the client seeds too — same secret")
            // Now end game B too.
            _      <- roomB.submit(Seat.White, GameCommand.Resign)
            _      <- roomB.result.timeoutTo(5.seconds, IO.raiseError(RuntimeException("game B never ended")))
            snapB  <- roomB.snapshot
            snapA2 <- roomA.snapshot // re-poll game A now that its partner has also ended
          yield
            assert(
              snapB.seed.nonEmpty,
              "game B (the second to end) must reveal immediately — no partner left to protect"
            )
            assert(snapA2.seed.nonEmpty, "game A must reveal on a later poll, now that its partner has ended too")
            assertEquals(snapA2.seed, snapB.seed, "both games share the same server seed")
            assertEquals(snapA2.clientSeeds, snapB.clientSeeds, "and the same fixed client-seed pair")
      }
    }
