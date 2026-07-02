package dicechess.play.store

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{BotConnection, GameRoom}

import scala.concurrent.duration.*

/** The room's persistence contract, storage-agnostic: what gets written, and when — checked through an in-memory
  * `persist` callback (no database involved).
  */
class GameRoomPersistenceSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get
  private def dice   = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
  private def seats  =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  test("the creation row is durable before anyone plays: tokens, seed and version 0 are in the first snapshot"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            written.get.map { snaps =>
              val first = snaps.headOption.getOrElse(fail("the creation snapshot must be written"))
              assertEquals(first.version, 0L)
              assertEquals(first.seatTokens, room.joinTokens)
              assertEquals(first.serverSeed, dice.reveal)
              assert(!first.ended)
            }
        }
    }

  test("every published event is persisted, versions are monotonic, and the pending roll is durable"):
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, seedGrace = 50.millis, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            val rolled = room.subscribe.collectFirst { case r: GameEvent.DiceRolled => r }.compile.lastOrError
            for
              _     <- room.start
              _     <- rolled.timeoutTo(5.seconds, IO.raiseError(RuntimeException("no opening roll")))
              _     <- room.submit(Seat.White, GameCommand.Resign)
              _     <- room.result.timeoutTo(5.seconds, IO.raiseError(RuntimeException("no terminal")))
              snaps <- written.get
            yield
              val versions = snaps.map(_.version)
              assertEquals(versions, versions.sorted, "what a subscriber saw as version N must be durable as N")
              assert(snaps.last.ended, "the terminal snapshot must be marked ended")
              // The roll was persisted while pending: its dice pool sits in the DFEN's 7th field.
              assert(
                snaps.exists(s => s.pending && s.dfen.trim.split("\\s+").length == 7),
                "a snapshot with the rolled dice pool must be durable before the turn is played"
              )
        }
    }

  test("a full game records every completed turn: consecutive numbers, three dice, passes with no moves"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    Ref.of[IO, Vector[GameSnapshot]](Vector.empty).flatMap { written =>
      GameRoom
        .create(seats, dice, persist = snap => written.update(_ :+ snap))
        .flatMap {
          case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
          case Right(room) =>
            (white.run(room).background, black.run(room).background).tupled
              .use(_ => room.start *> room.result)
              .timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time")))
              *> written.get
        }
        .map { snaps =>
          val turns = snaps.last.turns
          assert(turns.nonEmpty, "a finished game must carry its turn history")
          assertEquals(turns.map(_.turnNumber), (1L to turns.size.toLong).toVector, "turns are consecutive from 1")
          assert(turns.forall(_.dice.size == 3), "every turn records its three dice")
          assert(turns.forall(t => t.activeColor == "w" || t.activeColor == "b"))
          assert(turns.forall(_.fenAfter.nonEmpty))
        }
    }
