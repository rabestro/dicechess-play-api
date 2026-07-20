package dicechess.play.game

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.GameSnapshot

import java.security.MessageDigest
import scala.concurrent.duration.*

class GameRoomSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get

  /** SHA-256 of a hex-encoded seed, hex-encoded — to check a reveal against its commitment. */
  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private val seedW = "white-client-seed-0001" // ≥16 chars: a valid client dice seed
  private val seedB = "black-client-seed-0001"
  private def seats =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  test("the game-end event reveals the server seed, opening the dice commitment"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Subscribe first, then resign shortly after so the terminal event is the live GameEnded (not a snapshot).
          val ended      = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError
          val resignSoon = IO.sleep(100.millis) *> room.submit(Seat.White, GameCommand.Resign)
          (ended, resignSoon)
            .parMapN((event, _) => event)
            .flatMap(event => (room.diceCommit, room.snapshot).mapN((commit, snap) => (event, commit, snap)))
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no game-end event")))
      }
      .map: (event, commit, snap) =>
        assert(event.seed.nonEmpty, "the game-end event must reveal the seed")
        // The whole point of commit-reveal: the revealed seed hashes to the commitment published at creation.
        assertEquals(sha256Hex(event.seed.getOrElse(fail("expected a revealed seed"))), commit)
        // And the terminal snapshot carries the same reveal, so a client that joins after the end can still verify.
        assertEquals(snap.seed, event.seed)

  test("two bots play a full game through the room to a terminal state"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(Map(Seat.White -> white.principal, Seat.Black -> black.principal), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Bot fibers scoped to the game: cancelled on success, failure, or timeout.
          val play = (white.run(room).background, black.run(room).background).tupled.use: _ =>
            room.start *> room.result
          play.timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time")))
      }
      .map: over =>
        assert(
          over.termination == Termination.KingCaptured || over.termination == Termination.Draw,
          s"unexpected termination: $over"
        )
        over.result match
          case GameResult.Win(_) => assertEquals(over.termination, Termination.KingCaptured)
          case GameResult.Draw   => assertEquals(over.termination, Termination.Draw)

  test("a joining client's snapshot replays the completed-turn history (#129)"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(Map(Seat.White -> white.principal, Seat.Black -> black.principal), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val play = (white.run(room).background, black.run(room).background).tupled.use: _ =>
            room.start *> room.result
          // Play the game out, THEN join fresh: the first event is a Snapshot that must carry the whole game,
          // not an empty history — the fix for a spectator who joins mid-game and sees no prior moves.
          play.timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time"))) *>
            room.subscribe.collectFirst { case s: GameEvent.Snapshot => s }.compile.lastOrError
      }
      .map: snap =>
        assert(snap.history.nonEmpty, "a completed game's snapshot must replay its turn history")
        assert(snap.history.forall(_.fenAfter.nonEmpty), "every history entry carries a resulting position")
        // The last recorded turn lands on the same position the snapshot itself shows (board field of the DFEN).
        assertEquals(snap.history.last.fenAfter.split(" ").head, snap.state.dfen.split(" ").head)

  test("a stalled subscriber is dropped and never freezes the room"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      // Small fan-out buffer so the stalled subscriber overflows well within one game; the two bots
      // never lag (the writer waits for the side to move), so they are unaffected.
      .create(Map(Seat.White -> white.principal, Seat.Black -> black.principal), dice, fanOutBuffer = 16)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Reads one event, then never pulls again. With the old back-pressuring fan-out this would
          // freeze the whole room; now it is dropped once its buffer fills.
          val stalled = room.subscribe.evalMap(_ => IO.never).compile.drain
          // A healthy observer must still receive the terminal event.
          val healthy = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError

          (white.run(room).background, black.run(room).background, stalled.background).tupled
            .use(_ => room.start *> (room.result, healthy).parTupled)
            .timeoutTo(20.seconds, IO.raiseError(RuntimeException("room froze with a stalled subscriber")))
      }
      .map: (over, ended) =>
        assertEquals(ended.over, over)

  test("a writer-fiber failure aborts the game instead of bricking the room"):
    // A dice source that throws the moment the writer rolls: the consumer fiber dies before any
    // terminal. Without supervision `done` would never complete and every subscriber would hang.
    val boom = new DiceSource:
      def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] = throw RuntimeException("boom")
      def commit: String                                                       = "00"
      def reveal: String                                                       = "seed"

    GameRoom
      // Tiny seed grace so the opening roll (which throws) is reached promptly without anyone submitting a seed.
      .create(seats, boom, seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Await BOTH: the game's result and a subscriber's stream. If supervision were missing,
          // either would hang and the timeout would fail the test.
          val drained = room.subscribe.compile.drain
          (room.start *> (room.result, drained).parTupled)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("abort did not complete the game")))
      }
      .map: (over, _) =>
        assertEquals(over.termination, Termination.Aborted)

  test("a seat left with no connection past the grace window forfeits"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")),
        dice,
        disconnectGrace = 200.millis
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // White attaches then drops; nobody reconnects, so the seat forfeits once the grace elapses.
          room.connection(Seat.White).use_ *>
            room.result.timeoutTo(10.seconds, IO.raiseError(RuntimeException("grace forfeit did not fire")))
      }
      .map: over =>
        assertEquals(over.termination, Termination.Resign)
        assertEquals(over.result, GameResult.Win(Side.Black))

  test("reconnecting within the grace window cancels the forfeit"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")),
        dice,
        disconnectGrace = 300.millis
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Drop White (starts the grace), reconnect immediately, and hold the seat well past the window:
          // the forfeit must have been cancelled, so the game is still running (result not completed).
          room.connection(Seat.White).use_ *>
            room.connection(Seat.White).surround(room.result.map(Option(_)).timeoutTo(900.millis, IO.none))
      }
      .map: ended =>
        assertEquals(ended, None, "a reconnect within the grace must cancel the forfeit")

  test("an abandoned pending turn forfeits on the turn deadline"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        seats,
        dice,
        idleCheck = 200.millis,
        seedGrace = 50.millis // force-start quickly (no seeds), then the turn deadline applies
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Nobody ever submits, so the side to move runs out the deadline and forfeits.
          (room.start *> room.result)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("turn deadline did not fire")))
      }
      .map: over =>
        assertEquals(over.termination, Termination.Timeout)
        over.result match
          case GameResult.Win(_) => ()
          case other             => fail(s"a timeout is a forfeit win, got: $other")

  test("the opening roll is withheld until both client seeds arrive, then rolls"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      // A long grace so only the seeds (never a timeout) can open the gate within the test window.
      .create(seats, dice, seedGrace = 10.seconds)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          for
            _    <- room.start
            _    <- IO.sleep(200.millis) // a roll would have happened by now if it were not gated
            held <- room.snapshot
            _ = assert(!held.dicePending, "no turn should be pending before both client seeds arrive")
            _ = assertEquals(held.status, GameStatus.Active)
            // Subscribe first, then submit both seeds shortly after, so the live DiceRolled is observed.
            firstRoll = room.subscribe.collectFirst { case r: GameEvent.DiceRolled => r }.compile.lastOrError
            seedSoon  = IO.sleep(100.millis) *>
              room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
              room.submit(Seat.Black, GameCommand.SubmitSeed(seedB))
            rolled <- (firstRoll, seedSoon)
              .parMapN((r, _) => r)
              .timeoutTo(5.seconds, IO.raiseError(RuntimeException("the roll never came after both seeds")))
          yield assert(rolled.v >= 1L, "the opening roll must follow the seeds")
      }

  test("a game whose clients never seed still force-starts after the grace"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 100.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val rolled = room.subscribe.collectFirst { case r: GameEvent.DiceRolled => r }.compile.lastOrError
          (rolled, room.start)
            .parMapN((r, _) => r)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("the game never rolled without client seeds")))
      }
      .map(rolled => assert(rolled.v >= 1L, "a game with no client seeds must still roll (id fallback)"))

  test("the game-end event reveals the client seeds folded into the dice"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 10.seconds)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val ended = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError
          // Seed both seats (before start, so the game rolls at once), play briefly, then resign to a terminal.
          val drive =
            room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
              room.submit(Seat.Black, GameCommand.SubmitSeed(seedB)) *>
              room.start *>
              IO.sleep(100.millis) *>
              room.submit(Seat.White, GameCommand.Resign)
          (ended, drive)
            .parMapN((event, _) => event)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no game-end event")))
      }
      .map(event => assertEquals(event.clientSeeds, Some(ClientSeeds(seedW, seedB))))

  /** Any root-to-leaf walk of the wire tree — a complete legal turn by construction. */
  private def leafPath(tree: MoveTree): List[String] =
    tree.children.headOption match
      case None              => Nil
      case Some((uci, next)) => uci :: leafPath(next)

  /** The engine's own enumeration for a DFEN, as the wire tree — the independent oracle the events must agree with. */
  private def treeFor(dfen: String): MoveTree =
    EngineOps.parse(dfen) match
      case Left(error)  => fail(s"event dfen must parse: $error")
      case Right(state) => MoveTree.fromPaths(EngineOps.legalMovePaths(state).map(_.map(EngineOps.toUci)))

  final private case class MovableRoll(seat: Seat, dfen: String, legalMoves: Option[MoveTree], v: Long)

  /** Seed both seats (instant roll, deterministic dice), start, and hand back the first roll with a real decision —
    * auto-passes stream by on their own until one arrives.
    */
  private def firstMovableRoll(room: GameRoom): IO[MovableRoll] =
    val movable = room.subscribe
      .collectFirst:
        case GameEvent.Snapshot(v, s, _) if s.dicePending && s.legalMoves.exists(_.children.nonEmpty) =>
          MovableRoll(s.activeSeat, s.dfen, s.legalMoves, v)
        case GameEvent.DiceRolled(v, seat, _, dfen, _, Some(tree)) if tree.children.nonEmpty =>
          MovableRoll(seat, dfen, Some(tree), v)
      .compile
      .lastOrError
    val drive =
      room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
        room.submit(Seat.Black, GameCommand.SubmitSeed(seedB)) *>
        room.start
    (movable, drive)
      .parMapN((roll, _) => roll)
      .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no movable roll arrived")))

  test("DiceRolled carries the legal-move tree, and submits validate against the cached paths"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 10.seconds, maxInlinePaths = Int.MaxValue)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          firstMovableRoll(room).flatMap { roll =>
            // The inline tree is exactly the engine's enumeration for the event's own DFEN...
            assertEquals(roll.legalMoves, Some(treeFor(roll.dfen)))
            val path = leafPath(roll.legalMoves.get)
            // ...and GET /games/{id}/moves serves the same tree, tied to the same roll.
            room.legalMoves.flatMap { full =>
              assert(full.dicePending)
              assertEquals(full.legalMoves, roll.legalMoves.get)
              // An off-tree turn is rejected from the cache; a root-to-leaf walk is accepted — in submit order.
              val outcomes = room.subscribe
                .collect {
                  case e: GameEvent.Rejected   => Left(e.reason)
                  case e: GameEvent.TurnPlayed => Right(e.moves)
                }
                .take(2)
                .compile
                .toList
              // Let the outcome subscription register before submitting (same idiom as the resign tests above).
              val submits =
                IO.sleep(100.millis) *>
                  room.submit(roll.seat, GameCommand.SubmitTurn(List("a1a1"))) *>
                  room.submit(roll.seat, GameCommand.SubmitTurn(path))
              (outcomes, submits)
                .parMapN((seen, _) => seen)
                .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no submit outcomes arrived")))
                .map(seen => assertEquals(seen, List(Left("illegal turn"), Right(path))))
            }
          }
      }

  test("above the inline cap the tree is elided from events but stays fully queryable"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      // Cap 0: every movable roll's tree is elided (a forced pass — zero paths — would still ride inline).
      .create(seats, dice, seedGrace = 10.seconds, maxInlinePaths = 0)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val elided = room.subscribe
            .collectFirst:
              case GameEvent.Snapshot(v, s, _) if s.dicePending && s.legalMoves.isEmpty =>
                MovableRoll(s.activeSeat, s.dfen, None, v)
              case GameEvent.DiceRolled(v, seat, _, dfen, _, None) =>
                MovableRoll(seat, dfen, None, v)
            .compile
            .lastOrError
          val drive =
            room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
              room.submit(Seat.Black, GameCommand.SubmitSeed(seedB)) *>
              room.start
          (elided, drive)
            .parMapN((roll, _) => roll)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no elided roll arrived")))
            .flatMap { roll =>
              (room.legalMoves, room.snapshot).mapN { (full, snap) =>
                // The event elided the tree, but the dedicated endpoint still has all of it...
                assertEquals(full.legalMoves, treeFor(roll.dfen))
                assert(full.legalMoves.children.nonEmpty)
                // ...and the capped Snapshot elides it the same way as the event did.
                assert(snap.dicePending)
                assertEquals(snap.legalMoves, None)
              }
            }
      }

  test("a restored room recomputes the pending roll's paths: tree served, submits validated"):
    val dice = DiceSource.commitReveal("restore-seed-fixture".getBytes("UTF-8"))
    for
      stored <- Ref.of[IO, Option[GameSnapshot]](None)
      room   <- GameRoom
        .create(seats, dice, seedGrace = 10.seconds, maxInlinePaths = Int.MaxValue, persist = s => stored.set(Some(s)))
        .flatMap(made => IO.fromEither(made.left.map(e => RuntimeException(s"room creation failed: $e"))))
      roll <- firstMovableRoll(room)
      // The latest persisted snapshot is the pending movable roll (persist-before-broadcast).
      snap <- stored.get.map(_.getOrElse(fail("no snapshot was persisted")))
      _ = assert(snap.pending, "the persisted snapshot must carry the pending roll")
      restoredDice <- IO.fromEither(DiceSource.fromHexSeed(snap.serverSeed).left.map(RuntimeException(_)))
      restored     <- GameRoom
        .restore(snap, restoredDice)
        .flatMap(made => IO.fromEither(made.left.map(e => RuntimeException(s"restore failed: $e"))))
      moves <- restored.legalMoves
      // The cache is transient, so the restored room must have re-derived the same tree from the stored DFEN...
      _ = assertEquals(moves.legalMoves, roll.legalMoves.get)
      _ = assert(moves.dicePending)
      // ...and a turn picked from it must be accepted by the restored room's validation.
      played <- {
        val outcome = restored.subscribe
          .collectFirst { case e: GameEvent.TurnPlayed => e }
          .compile
          .lastOrError
        // Let the outcome subscription register before submitting (same idiom as the resign tests above).
        val submit =
          IO.sleep(100.millis) *> restored.submit(roll.seat, GameCommand.SubmitTurn(leafPath(moves.legalMoves)))
        (outcome, submit)
          .parMapN((event, _) => event)
          .timeoutTo(10.seconds, IO.raiseError(RuntimeException("the restored room never played the turn")))
      }
    yield assertEquals(played.moves, leafPath(moves.legalMoves))

  test("submitTurn answers the writer's verdict synchronously: refusals, then the applied version"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 10.seconds, maxInlinePaths = Int.MaxValue)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          firstMovableRoll(room).flatMap { roll =>
            val path  = leafPath(roll.legalMoves.get)
            val other = if roll.seat == Seat.White then Seat.Black else Seat.White
            for
              offTurn <- room.submitTurn(other, path)
              illegal <- room.submitTurn(roll.seat, List("a1a1"))
              applied <- room.submitTurn(roll.seat, path)
              // The turn was consumed, so replaying the exact same path is now the opponent's roll — off-turn again.
              replay <- room.submitTurn(roll.seat, path)
            yield
              assertEquals(offTurn, GameRoom.TurnVerdict.Refused("not your turn"))
              assertEquals(illegal, GameRoom.TurnVerdict.Refused("illegal turn"))
              applied match
                case GameRoom.TurnVerdict.Applied(version) =>
                  assert(version > roll.v, s"the applied version ($version) must supersede the roll's (${roll.v})")
                case other => fail(s"expected Applied, got: $other")
              assertEquals(replay, GameRoom.TurnVerdict.Refused("not your turn"))
          }
      }
      .timeoutTo(15.seconds, IO.raiseError(RuntimeException("verdicts did not arrive")))

  test("submitTurn on a finished game answers 'game is over' instead of hanging"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 10.seconds)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          for
            _       <- room.submit(Seat.White, GameCommand.Resign)
            _       <- room.result
            verdict <- room.submitTurn(Seat.Black, List("e2e4"))
          yield assertEquals(verdict, GameRoom.TurnVerdict.Refused("game is over"))
      }
      .timeoutTo(10.seconds, IO.raiseError(RuntimeException("the ended-game verdict did not arrive")))
