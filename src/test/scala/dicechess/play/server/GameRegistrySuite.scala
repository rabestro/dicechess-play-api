package dicechess.play.server

import cats.effect.{IO, Ref}
import dicechess.play.core.*
import dicechess.play.store.{GameSnapshot, GameStore}

/** The rated/casual policy (#97): a game is rated only when both requested AND every participant is non-anonymous.
  * `isRated` itself is checked directly as a pure matrix; the `create` tests confirm the policy actually reaches the
  * persisted snapshot, not just the in-memory decision.
  */
class GameRegistrySuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")

  private def capturingStore(written: Ref[IO, Vector[GameSnapshot]]): GameStore = new GameStore:
    def save(id: GameId, snapshot: GameSnapshot): IO[Unit] = written.update(_ :+ snapshot)
    def loadActive: IO[List[(GameId, GameSnapshot)]]       = IO.pure(Nil)

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
              assert(snaps.headOption.exists(_.rated), "the creation snapshot must be marked rated")
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
              assert(snaps.headOption.exists(!_.rated), "a guest participant must force the snapshot casual")
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
              assert(snaps.headOption.exists(!_.rated), "omitting requestedRated must default to casual")
            }
        }
      }
    }
