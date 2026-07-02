package dicechess.play.game

import cats.effect.{Deferred, Fiber, IO, Outcome, Ref, Resource}
import cats.effect.std.{Console, Queue}
import cats.syntax.all.*
import dicechess.engine.domain.GameState
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.store.{GameSnapshot, TurnRecord}
import fs2.Stream

import java.security.SecureRandom
import scala.concurrent.duration.*

/** The authoritative game room. A single consumer fiber drains an inbox and is the only writer of game state (the
  * mailbox-serialization an actor gives, without the framework); all reads are lock-free via `Ref.get`. Events fan out
  * to every subscriber (both players plus spectators) through per-subscriber bounded queues.
  *
  * The room is transport-agnostic: its only surface is `subscribe` / `submit` / `start`, so a human over WebSocket and
  * a bot over HTTP attach as adapters (see PlayerConnection).
  *
  * Fan-out never back-pressures the writer: each subscriber owns a bounded queue and the writer publishes with a
  * non-blocking `tryOffer`. A subscriber that falls `fanOutBuffer` events behind (a paused, half-open, or dead client,
  * including a 24/7 bot) is dropped — its stream is interrupted — rather than blocking the consumer fiber and freezing
  * the game for everyone.
  */
final class GameRoom private (
    stateRef: Ref[IO, GameRoom.Session],
    inbox: Queue[IO, GameRoom.Msg],
    subscribers: Ref[IO, Map[Long, GameRoom.Subscriber]],
    nextSubscriberId: Ref[IO, Long],
    fanOutBuffer: Int,
    seatTokens: Map[Seat, String],
    idleCheck: FiniteDuration,
    done: Deferred[IO, GameOver],
    presence: Ref[IO, Map[Seat, Int]],
    graceFibers: Ref[IO, Map[Seat, Fiber[IO, Throwable, Unit]]],
    disconnectGrace: FiniteDuration,
    seedGrace: FiniteDuration,
    persist: GameSnapshot => IO[Unit]
):
  import GameRoom.*

  /** Current state, then the live event feed — so a late subscriber can still act. The stream completes once the game
    * is over (after emitting the terminal event, or immediately for someone who joins an already-finished game), so the
    * transport can close the connection instead of holding it open forever.
    *
    * A subscriber that registers concurrently with an `emit` may see the same version twice (once in the snapshot, once
    * live); that overlap is intentional — it guarantees at-least-once delivery, and clients de-duplicate by `version`.
    */
  def subscribe: Stream[IO, GameEvent] =
    Stream
      .resource(Resource.make(register)(sub => unregister(sub.id)))
      .flatMap: sub =>
        val snapshot =
          Stream.eval((stateRef.get, IO.monotonic).mapN((s, now) => GameEvent.Snapshot(s.version, s.publicAt(now))))
        val live = Stream.fromQueueUnterminated(sub.queue)
        (snapshot ++ live)
          .interruptWhen(sub.dropped.get.attempt)
          .takeThrough(event => !isTerminal(event))

  private def register: IO[Subscriber] =
    for
      id      <- nextSubscriberId.getAndUpdate(_ + 1)
      queue   <- Queue.bounded[IO, GameEvent](fanOutBuffer)
      dropped <- Deferred[IO, Unit]
      sub = Subscriber(id, queue, dropped)
      _ <- subscribers.update(_.updated(id, sub))
    yield sub

  private def unregister(id: Long): IO[Unit] = subscribers.update(_.removed(id))

  /** Fan out to every subscriber without ever parking the writer: `tryOffer` is non-blocking, and a subscriber whose
    * queue is full has fallen too far behind, so it is dropped (its stream interrupted) instead of stalling the game.
    */
  private def broadcast(event: GameEvent): IO[Unit] =
    subscribers.get.flatMap: subs =>
      subs.values.toList.traverse_ { sub =>
        sub.queue.tryOffer(event).flatMap {
          case true  => IO.unit
          case false => sub.dropped.complete(()).attempt.void
        }
      }

  private def isTerminal(event: GameEvent): Boolean = event match
    case GameEvent.GameEnded(_, _, _, _) => true
    case GameEvent.Snapshot(_, ps)       =>
      ps.status match
        case GameStatus.Ended(_) => true
        case GameStatus.Active   => false
    case _ => false

  /** Hand a command to the writer, stamping it with the receive time so a timed game charges only the player's thinking
    * time — not the queue-wait or the engine validation that follows.
    */
  def submit(seat: Seat, command: GameCommand): IO[Unit] =
    IO.monotonic.flatMap(receivedAt => inbox.offer(Msg.Command(seat, command, receivedAt)))

  /** Begin the game (roll the first turn). Call after subscribers have attached. */
  def start: IO[Unit] = inbox.offer(Msg.Begin)

  // ── player presence / reconnect grace ───────────────────────────────────────

  /** A player's live presence on the room: acquired when a transport (a WebSocket) attaches the seat, released when it
    * detaches. A seat may hold several connections at once (e.g. two tabs); only when the *last* one drops does the
    * seat have `disconnectGrace` to reconnect before it forfeits — so a refresh or a brief network blip no longer ends
    * the game. Spectators hold nothing.
    */
  def connection(seat: Seat): Resource[IO, Unit] =
    seat.side match
      case None    => Resource.unit
      case Some(_) => Resource.make(onConnect(seat))(_ => onDisconnect(seat))

  private def onConnect(seat: Seat): IO[Unit] =
    presence.update(m => m.updated(seat, m.getOrElse(seat, 0) + 1)) *> cancelGrace(seat)

  private def onDisconnect(seat: Seat): IO[Unit] =
    presence
      .updateAndGet(m => m.updated(seat, math.max(0, m.getOrElse(seat, 1) - 1)))
      .flatMap(m => if m.getOrElse(seat, 0) == 0 then scheduleForfeit(seat) else IO.unit)

  /** Start the grace timer for a now-unmanned seat. It forfeits only if the seat is *still* unmanned when the timer
    * elapses — the post-sleep re-check makes a reconnect that races the scheduling safe even if `cancelGrace` missed
    * it. Resigning an already-ended game is a no-op in `process`.
    */
  private def scheduleForfeit(seat: Seat): IO[Unit] =
    val forfeit =
      IO.sleep(disconnectGrace) *>
        presence.get.flatMap(m => if m.getOrElse(seat, 0) == 0 then submit(seat, GameCommand.Resign) else IO.unit)
    forfeit.start.flatMap(fib => swapGrace(seat, Some(fib)))

  private def cancelGrace(seat: Seat): IO[Unit] = swapGrace(seat, None)

  /** Install (or clear) a seat's pending grace fiber, cancelling whatever it displaces. */
  private def swapGrace(seat: Seat, next: Option[Fiber[IO, Throwable, Unit]]): IO[Unit] =
    graceFibers
      .modify { m =>
        val prev = m.get(seat)
        val m2   = next.fold(m.removed(seat))(m.updated(seat, _))
        (m2, prev)
      }
      .flatMap(_.traverse_(_.cancel))

  /** Completes when the game ends. */
  def result: IO[GameOver] = done.get

  /** Who is seated where. */
  def seating: IO[Map[Seat, Principal]] = stateRef.get.map(_.players)

  /** Per-seat join tokens minted at creation; the creator distributes them so each player can claim its seat. */
  def joinTokens: Map[Seat, String] = seatTokens

  /** The seat a join token grants, if any — so a WebSocket upgrade is authorized by a secret, not a trusted query
    * param. The tokens are high-entropy random, so a plain comparison is fine.
    */
  def seatFor(token: String): Option[Seat] =
    seatTokens.collectFirst { case (seat, t) if t == token => seat }

  /** The dice commitment (SHA-256 of the server seed), published at game start. */
  def diceCommit: IO[String] = stateRef.get.map(_.dice.commit)

  /** Current public state (for a REST snapshot or a freshly-joining client). */
  def snapshot: IO[PublicGameState] = (stateRef.get, IO.monotonic).mapN((s, now) => s.publicAt(now))

  // ── consumer fiber ─────────────────────────────────────────────────────────

  /** The writer fiber, supervised: if `consume` ever fails (an engine invariant throws) or is cancelled (shutdown), the
    * game would otherwise be wedged forever — `done` never completes, the registry never evicts the room, and every
    * subscriber stream hangs. So on any non-success outcome we abort the game: publish a terminal `GameEnded` (to close
    * subscriber streams) and complete `done`, exactly once.
    */
  private def supervisedConsume: IO[Unit] =
    consume.guaranteeCase:
      case Outcome.Succeeded(_) => IO.unit // normal end already completed `done` in endGame
      case _                    => abortIfActive

  private def abortIfActive: IO[Unit] =
    stateRef.get.flatMap: s =>
      if s.ended then IO.unit
      else
        val over = GameOver(GameResult.Draw, Termination.Aborted)
        emit(
          s.copy(pending = false, status = GameStatus.Ended(over)),
          v => GameEvent.GameEnded(v, over, s.dice.reveal, s.clientSeedsRevealed)
        ).flatTap(_ => done.complete(over).attempt.void).void

  private def consume: IO[Unit] =
    // No command within the deadline while a turn is pending => the player to move forfeits. The deadline is the mover's
    // remaining clock for a timed game, or the fixed anti-abandonment `idleCheck` for an unlimited one. The timeout is
    // part of the single-writer loop (not a separate fiber), so it can't race the state it acts on.
    stateRef.get
      .flatMap(deadlineFor)
      .flatMap: deadline =>
        inbox.take
          .timeoutTo(deadline, IO.pure(Msg.Timeout))
          .flatMap:
            case Msg.Begin =>
              stateRef.get.flatMap { s =>
                // Idempotent: a Begin never re-rolls (ply > 0) or revives an ended game. Otherwise it marks the game
                // started and rolls as soon as both client seeds are in — which also kicks a game resumed from a
                // snapshot that had started but not yet rolled. If seeds are still missing, `deadlineFor`/`onTimeout`
                // force-start once the seed grace elapses (so a game never stalls).
                if s.ply > 0L || s.ended then IO.unit
                else
                  IO.monotonic.flatMap { now =>
                    val started = if s.started then s else s.copy(started = true, startedAt = Some(now))
                    // Persist `started` (and any seeds already in) before the opening roll, so a roll failure aborts
                    // from current state rather than from stale state (dropping seeds / re-starting).
                    stateRef.set(started) *>
                      (if started.hasAllSeeds then beginTurn(started).flatMap(stateRef.set) else IO.unit)
                  }
              } *> continue
            case Msg.Command(seat, command, receivedAt) =>
              stateRef.get.flatMap(s => process(s, seat, command, receivedAt)).flatMap(stateRef.set) *> continue
            case Msg.Timeout =>
              stateRef.get.flatMap(onTimeout).flatMap(stateRef.set) *> continue

  /** A turn deadline elapsed: if a turn is genuinely pending, the side to move forfeits; otherwise (idle between turns,
    * or already over) it is a no-op.
    */
  private def onTimeout(s: Session): IO[Session] =
    if s.ended then IO.pure(s)
    // A started game whose opening roll hasn't happened yet: the seed grace elapsed (force-start; a missing seat
    // falls back to its external id, see `seedFor`), or the game was resumed from a snapshot taken in that window.
    else if s.started && s.ply == 0L then beginTurn(s)
    else if !s.pending then IO.pure(s)
    else
      val seat   = EngineOps.activeSeat(s.state)
      val toMove = EngineOps.activeSide(s.state)
      // Flag-fall (timed) or anti-abandonment forfeit (unlimited): both are a loss on time. Zero the flagged seat's
      // clock so a later snapshot reads accurately.
      val flagged = s.copy(remaining = s.remaining.updated(seat, Duration.Zero), turnStartedAt = None)
      endGame(flagged, GameOver(GameResult.Win(toMove.opponent), Termination.Timeout))

  // ── clocks ───────────────────────────────────────────────────────────────────

  /** How long to wait for the mover before forfeiting: the mover's remaining clock for a timed game (minus the time
    * already spent on this turn — so repeated illegal submits can't refill it), or the fixed `idleCheck` cap otherwise.
    * Unlimited games, and the brief windows when no turn is pending, always use `idleCheck`.
    */
  private def deadlineFor(s: Session): IO[FiniteDuration] =
    if s.ended then IO.pure(idleCheck)
    else if s.awaitingSeeds then
      // Time left in the opening seed grace before we force-start with whatever seeds have arrived.
      IO.monotonic.map(now => floorZero(seedGrace - s.startedAt.fold(Duration.Zero: FiniteDuration)(now - _)))
    else if !s.pending then IO.pure(idleCheck)
    else
      turnBudget(s, EngineOps.activeSeat(s.state)) match
        case None         => IO.pure(idleCheck)
        case Some(budget) =>
          IO.monotonic.map: now =>
            val elapsed = s.turnStartedAt.fold(Duration.Zero)(now - _)
            floorZero(budget - elapsed)

  /** The time available to `mover` for the current turn: their bank (SuddenDeath/Fischer), a fresh per-move budget
    * (PerMove), or `None` for Unlimited (no chess clock — the `idleCheck` cap applies instead).
    */
  private def turnBudget(s: Session, mover: Seat): Option[FiniteDuration] =
    s.timeControl match
      case TimeControl.Unlimited      => None
      case TimeControl.PerMove(spm)   => Some(spm.seconds)
      case TimeControl.SuddenDeath(_) => Some(s.remaining.getOrElse(mover, Duration.Zero))
      case TimeControl.Fischer(_, _)  => Some(s.remaining.getOrElse(mover, Duration.Zero))

  /** Start the mover's clock for a turn that has a real decision (a legal move). Forced passes never reach here, so
    * they cost nothing. No-op for Unlimited.
    */
  private def startClock(s: Session): IO[Session] =
    s.timeControl match
      case TimeControl.Unlimited => IO.pure(s)
      case _                     => IO.monotonic.map(now => s.copy(turnStartedAt = Some(now)))

  /** Debit the time `mover` spent on the turn just completed, then apply the control's bonus: Fischer adds its
    * increment; SuddenDeath only debits; PerMove keeps no bank (each turn is independent). Clears the turn timer.
    */
  /** Charge `mover` for the turn just completed, stopping the clock at `stoppedAt` — the moment the move was *received*
    * (sampled in `submit`), so neither queue-wait nor engine validation is billed to the player. Fischer then adds its
    * increment; SuddenDeath only debits; PerMove keeps no bank.
    */
  private def debit(s: Session, mover: Seat, stoppedAt: FiniteDuration): Session =
    val elapsed = s.turnStartedAt.fold(Duration.Zero: FiniteDuration)(stoppedAt - _)
    s.timeControl match
      case TimeControl.Unlimited | TimeControl.PerMove(_) => s.copy(turnStartedAt = None)
      case TimeControl.SuddenDeath(_)                     =>
        val left = floorZero(s.remaining.getOrElse(mover, Duration.Zero) - elapsed)
        s.copy(remaining = s.remaining.updated(mover, left), turnStartedAt = None)
      case TimeControl.Fischer(_, inc) =>
        val left = floorZero(s.remaining.getOrElse(mover, Duration.Zero) - elapsed) + inc.seconds
        s.copy(remaining = s.remaining.updated(mover, left), turnStartedAt = None)

  private def continue: IO[Unit] =
    stateRef.get.flatMap(s => if s.ended then IO.unit else consume)

  /** Advance the session, write it, persist it, THEN broadcast — so the Ref always reflects the latest published event,
    * and anything a player has seen is already durable (a fixed roll must never un-roll on a crash). A subscriber that
    * registers just after a broadcast reads a current Snapshot (and acts), and one that registers just before catches
    * the live event; broadcasting before the write would let a subscriber in that window miss both and hang.
    */
  private def emit(s: Session, make: Long => GameEvent): IO[Session] =
    val s2 = s.copy(version = s.version + 1)
    stateRef.set(s2) *> persistQuietly(s2) *> broadcast(make(s2.version)).as(s2)

  /** Persistence is availability-first: a store failure is logged and the game plays on in memory (degrading to exactly
    * what the storeless mode offers) rather than wedging the writer fiber mid-game.
    */
  private def persistQuietly(s: Session): IO[Unit] =
    persist(snapshotOf(s)).handleErrorWith(e => Console[IO].errorln(s"[play][persist] snapshot write failed: $e"))

  /** The durable image of the session — enough to resume the room after a restart and, once ended, to hand the game to
    * analytics.
    */
  private def snapshotOf(s: Session): GameSnapshot =
    GameSnapshot(
      version = s.version,
      dfen = EngineOps.serialize(s.state),
      players = s.players,
      seatTokens = seatTokens,
      serverSeed = s.dice.reveal,
      clientSeeds = s.clientSeeds,
      started = s.started,
      ply = s.ply,
      pending = s.pending,
      status = s.status,
      timeControl = s.timeControl,
      remainingMs = s.remaining.map((seat, left) => seat -> left.toMillis),
      lastRoll = s.lastRoll,
      turns = s.turns,
      createdAtEpochMs = s.createdAtEpochMs
    )

  /** Roll for the side to move; publish the roll; auto-pass while there is no legal move. */
  private def beginTurn(s0: Session): IO[Session] =
    val dice   = s0.dice.roll(s0.ply, s0.seedFor(Seat.White), s0.seedFor(Seat.Black))
    val rolled = EngineOps.withDice(s0.state, dice)
    val seat   = EngineOps.activeSeat(rolled)
    val s1     = s0.copy(state = rolled, ply = s0.ply + 1, pending = true, lastRoll = dice)
    // The new mover's clock has not started ticking yet (startClock runs below), so these are the banks at turn start —
    // including the increment just credited to the side that completed the previous turn.
    val emitRoll =
      IO.monotonic.flatMap: now =>
        val clocks = liveClocks(s1, now)
        emit(s1, v => GameEvent.DiceRolled(v, seat, dice, EngineOps.serialize(rolled), clocks))
    emitRoll.flatMap: s2 =>
      if EngineOps.legalMovePaths(rolled).nonEmpty then startClock(s2)
      else
        val passed   = rolled.endTurn()
        val passDfen = EngineOps.serialize(passed)
        val s3       = s2.copy(
          state = passed,
          pending = false,
          turns = s2.turns :+ TurnRecord(s2.ply, colorLetter(seat), dice, Nil, passDfen)
        )
        emit(s3, v => GameEvent.TurnPlayed(v, seat, Nil, passDfen))
          .flatMap(advanceOrEnd)

  /** After a completed turn (or a pass), either end on a limit/draw or roll the next turn. */
  private def advanceOrEnd(s: Session): IO[Session] =
    if s.state.halfMoveClock >= FiftyMoveHalfMoves then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else if s.ply >= MaxPlies then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else beginTurn(s)

  private def endGame(s: Session, over: GameOver): IO[Session] =
    emit(
      s.copy(pending = false, status = GameStatus.Ended(over)),
      v => GameEvent.GameEnded(v, over, s.dice.reveal, s.clientSeedsRevealed)
    ).flatTap(_ => done.complete(over).attempt.void)

  private def process(s: Session, seat: Seat, command: GameCommand, receivedAt: FiniteDuration): IO[Session] =
    s.status match
      case GameStatus.Ended(_) => IO.pure(s)
      case GameStatus.Active   =>
        command match
          case GameCommand.Resign =>
            seat.side match
              case Some(loser) => endGame(s, GameOver(GameResult.Win(loser.opponent), Termination.Resign))
              case None        => emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot resign"))

          case GameCommand.SubmitSeed(seed) =>
            seat.side match
              case None    => emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot submit a seed"))
              case Some(_) =>
                // Accept exactly one seed per seat, and only before the opening roll — afterwards the dice are locked.
                if s.ply > 0L || s.clientSeeds.contains(seat) then IO.pure(s)
                else if seed.length < MinSeedChars || seed.length > MaxSeedChars then
                  emit(s, v => GameEvent.Rejected(v, seat, s"seed must be $MinSeedChars..$MaxSeedChars characters"))
                else
                  val seeded = s.copy(clientSeeds = s.clientSeeds.updated(seat, seed))
                  // Persist the accepted seed (no event is emitted for it), then — if both seats are in and the game
                  // already started — open the gate and roll the opening turn from the durable state.
                  if seeded.started && seeded.hasAllSeeds then
                    stateRef.set(seeded) *> persistQuietly(seeded) *> beginTurn(seeded)
                  else persistQuietly(seeded).as(seeded)

          case GameCommand.SubmitTurn(uci) =>
            if !s.pending || seat != EngineOps.activeSeat(s.state) then
              emit(s, v => GameEvent.Rejected(v, seat, "not your turn"))
            else
              EngineOps.findLegalPath(s.state, uci) match
                case None       => emit(s, v => GameEvent.Rejected(v, seat, "illegal turn"))
                case Some(path) =>
                  val (next, winner) = EngineOps.applyPath(s.state, path)
                  val nextDfen       = EngineOps.serialize(next)
                  // Stop the mover's clock at the receive time (not now, after validation) and apply the control's bonus
                  // before the next turn rolls and resets the clock for the other side.
                  val sd = debit(s, seat, receivedAt)
                  emit(
                    sd.copy(
                      state = next,
                      pending = false,
                      turns = sd.turns :+ TurnRecord(sd.ply, colorLetter(seat), sd.lastRoll, uci, nextDfen)
                    ),
                    v => GameEvent.TurnPlayed(v, seat, uci, nextDfen)
                  )
                    .flatMap: s1 =>
                      winner match
                        case Some(w) => endGame(s1, GameOver(GameResult.Win(w), Termination.KingCaptured))
                        case None    => advanceOrEnd(s1)

object GameRoom:

  private val MaxPlies           = 5000L
  private val FiftyMoveHalfMoves = 100

  /** Per-subscriber fan-out buffer. A subscriber this many events behind is dropped, never blocking the writer. */
  private val DefaultFanOutBuffer = 256

  /** Anti-abandonment turn cap for **Unlimited** games (and the brief between-turn windows in any game): if the side to
    * move doesn't act within this, it forfeits. Timed controls (SuddenDeath/Fischer/PerMove) instead enforce the real
    * per-side clock; see `deadlineFor`.
    */
  private val DefaultIdleCheck: FiniteDuration = FiniteDuration(120, "seconds")

  /** How long a seat may be without any connection before it forfeits — long enough to ride out a tab refresh or a
    * brief network blip (paired with client auto-reconnect), short enough that a truly-gone player doesn't strand the
    * opponent for long.
    */
  val DefaultDisconnectGrace: FiniteDuration = FiniteDuration(30, "seconds")

  /** How long the opening roll is held for both seats to submit their post-commit dice seed (provably-fair, #13). A
    * real client/bot seeds within milliseconds of connecting, so this only bites a client that never seeds — after it,
    * the game force-starts and any missing seat falls back to its (already-public) external id, so a game never stalls.
    */
  val DefaultSeedGrace: FiniteDuration = FiniteDuration(5, "seconds")

  /** Accepted bounds for a client dice seed (characters). The lower bound asks for real entropy (≥16 chars, e.g. the
    * hex of 8+ random bytes); the upper bound caps abuse. A weak or absent seed only weakens that seat's own
    * contribution — the committed server seed still makes the dice ungrindable.
    */
  private val MinSeedChars = 16
  private val MaxSeedChars = 256

  /** A live subscriber's mailbox plus a one-shot "you fell behind, disconnect" signal. */
  final private case class Subscriber(id: Long, queue: Queue[IO, GameEvent], dropped: Deferred[IO, Unit])

  private enum Msg:
    case Begin
    case Command(seat: Seat, command: GameCommand, receivedAt: FiniteDuration)
    case Timeout

  final private case class Session(
      state: GameState,
      version: Long,
      players: Map[Seat, Principal],
      dice: DiceSource,
      ply: Long,
      pending: Boolean,
      status: GameStatus,
      timeControl: TimeControl,
      remaining: Map[Seat, FiniteDuration] = Map.empty,
      turnStartedAt: Option[FiniteDuration] = None,
      // Provably-fair dice gate: `started` flips on the first Begin; `startedAt` stamps it (to measure the seed grace);
      // `clientSeeds` collects each seat's post-commit entropy before the opening roll.
      started: Boolean = false,
      startedAt: Option[FiniteDuration] = None,
      clientSeeds: Map[Seat, String] = Map.empty,
      // The dice of the turn in flight (recorded into `turns` when the turn completes) and the completed-turn history
      // — kept for the end-of-game analytics handoff, and persisted so it survives a restart.
      lastRoll: List[Int] = Nil,
      turns: Vector[TurnRecord] = Vector.empty,
      createdAtEpochMs: Option[Long] = None
  ):
    def ended: Boolean = status match
      case GameStatus.Ended(_) => true
      case GameStatus.Active   => false

    /** Every seated player has submitted a client seed. */
    def hasAllSeeds: Boolean = players.keySet.forall(clientSeeds.contains)

    /** The game has begun but is still holding the opening roll, waiting for the seats' client seeds. */
    def awaitingSeeds: Boolean = started && ply == 0L && !ended && !hasAllSeeds

    /** The seed folded in for `seat`: the one it submitted, or — if it never did before the grace elapsed — its
      * external id as a deterministic, already-public fallback (so a missing seed never stalls the game).
      */
    def seedFor(seat: Seat): String = clientSeeds.getOrElse(seat, players.get(seat).fold("")(_.externalId))

    /** The pair of client seeds actually folded into the dice, for the end-of-game reveal. */
    def clientSeedsRevealed: ClientSeeds = ClientSeeds(seedFor(Seat.White), seedFor(Seat.Black))

    /** Public state with clocks live as of `now` (the mover's elapsed-this-turn already subtracted). */
    def publicAt(now: FiniteDuration): PublicGameState =
      // Reveal the seeds only once the game is over (so a late (re)joiner can still verify); secret while active.
      val (revealed, seeds) = status match
        case GameStatus.Ended(_) => (Some(dice.reveal), Some(clientSeedsRevealed))
        case GameStatus.Active   => (None, None)
      PublicGameState(
        version,
        EngineOps.serialize(state),
        EngineOps.activeSeat(state),
        pending,
        status,
        timeControl,
        liveClocks(this, now),
        dice.commit,
        revealed,
        seeds
      )

  /** Create a room, or describe why the initial position is invalid — errors as values. */
  def create(
      players: Map[Seat, Principal],
      dice: DiceSource,
      initialDfen: String = EngineOps.InitialDfen,
      fanOutBuffer: Int = DefaultFanOutBuffer,
      idleCheck: FiniteDuration = DefaultIdleCheck,
      disconnectGrace: FiniteDuration = DefaultDisconnectGrace,
      timeControl: TimeControl = TimeControl.Unlimited,
      seedGrace: FiniteDuration = DefaultSeedGrace,
      persist: GameSnapshot => IO[Unit] = _ => IO.unit
  ): IO[Either[String, GameRoom]] =
    EngineOps.parse(initialDfen) match
      case Left(error)   => IO.pure(Left(error))
      case Right(state0) =>
        for
          createdAt <- IO.realTime
          session0 = Session(
            state0,
            0L,
            players,
            dice,
            0L,
            pending = false,
            GameStatus.Active,
            timeControl,
            remaining = initialRemaining(timeControl, players.keys),
            createdAtEpochMs = Some(createdAt.toMillis)
          )
          seatTokens <- mintTokens(players.keys)
          room       <- build(session0, seatTokens, fanOutBuffer, idleCheck, disconnectGrace, seedGrace, persist)
          // The creation row must be durable before anyone plays: the seat tokens and the dice commitment have been
          // handed out, so a restart in the first seconds must not lose them.
          _ <- room.persistQuietly(session0)
          _ <- room.supervisedConsume.start
        yield Right(room)

  /** Rebuild a room from a durable snapshot after a restart. Tokens, seeds, clocks and turn history come from the
    * snapshot; the caller re-derives the `DiceSource` from the stored server seed, so the committed dice sequence
    * continues (and still opens the published commitment at reveal). The mover's in-flight turn timer restarts —
    * monotonic time is process-scoped — which errs in the player's favour.
    */
  def restore(
      snapshot: GameSnapshot,
      dice: DiceSource,
      fanOutBuffer: Int = DefaultFanOutBuffer,
      idleCheck: FiniteDuration = DefaultIdleCheck,
      disconnectGrace: FiniteDuration = DefaultDisconnectGrace,
      seedGrace: FiniteDuration = DefaultSeedGrace,
      persist: GameSnapshot => IO[Unit] = _ => IO.unit
  ): IO[Either[String, GameRoom]] =
    EngineOps.parse(snapshot.dfen) match
      case Left(error)   => IO.pure(Left(s"corrupt snapshot dfen: $error"))
      case Right(state0) =>
        IO.monotonic.flatMap { now =>
          val session0 = Session(
            state0,
            snapshot.version,
            snapshot.players,
            dice,
            snapshot.ply,
            snapshot.pending,
            snapshot.status,
            snapshot.timeControl,
            remaining = snapshot.remainingMs.map((seat, ms) => seat -> FiniteDuration(ms, "milliseconds")),
            // A pending turn's clock restarts NOW: monotonic time is process-scoped, so the pre-crash start is
            // meaningless — but leaving it unset would let `debit` charge zero for the whole post-restart turn.
            turnStartedAt = Option.when(snapshot.pending)(now),
            started = snapshot.started,
            startedAt = None,
            clientSeeds = snapshot.clientSeeds,
            lastRoll = snapshot.lastRoll,
            turns = snapshot.turns,
            createdAtEpochMs = snapshot.createdAtEpochMs
          )
          build(session0, snapshot.seatTokens, fanOutBuffer, idleCheck, disconnectGrace, seedGrace, persist)
            .flatTap(_.supervisedConsume.start)
            .map(Right(_))
        }

  private def build(
      session0: Session,
      seatTokens: Map[Seat, String],
      fanOutBuffer: Int,
      idleCheck: FiniteDuration,
      disconnectGrace: FiniteDuration,
      seedGrace: FiniteDuration,
      persist: GameSnapshot => IO[Unit]
  ): IO[GameRoom] =
    for
      ref         <- Ref.of[IO, Session](session0)
      inbox       <- Queue.unbounded[IO, Msg]
      subscribers <- Ref.of[IO, Map[Long, Subscriber]](Map.empty)
      nextId      <- Ref.of[IO, Long](0L)
      done        <- Deferred[IO, GameOver]
      presence    <- Ref.of[IO, Map[Seat, Int]](Map.empty)
      graceFibers <- Ref.of[IO, Map[Seat, Fiber[IO, Throwable, Unit]]](Map.empty)
    yield new GameRoom(
      ref,
      inbox,
      subscribers,
      nextId,
      fanOutBuffer,
      seatTokens,
      idleCheck,
      done,
      presence,
      graceFibers,
      disconnectGrace,
      seedGrace,
      persist
    )

  /** Starting clocks for a timed control: both seats get the initial bank (SuddenDeath/Fischer). PerMove keeps no bank
    * (each turn gets a fresh budget) and Unlimited has no clock, so both start empty.
    */
  private def initialRemaining(timeControl: TimeControl, seats: Iterable[Seat]): Map[Seat, FiniteDuration] =
    timeControl match
      case TimeControl.SuddenDeath(init) => seats.map(_ -> init.seconds).toMap
      case TimeControl.Fischer(init, _)  => seats.map(_ -> init.seconds).toMap
      case _                             => Map.empty

  private def floorZero(d: FiniteDuration): FiniteDuration = if d > Duration.Zero then d else Duration.Zero

  /** Analytics colour letter for a *player* seat (turn records are only ever created for the side that moved). */
  private def colorLetter(seat: Seat): String = if seat == Seat.White then "w" else "b"

  /** Remaining time per side as of `now`, in millis — the mover's in-progress turn already subtracted so a snapshot is
    * live, not frozen at the last completed turn. `None` for Unlimited (no clock).
    */
  private def liveClocks(s: Session, now: FiniteDuration): Option[Clocks] =
    s.timeControl match
      case TimeControl.Unlimited => None
      case timeControl           =>
        val mover                          = Option.when(s.pending)(EngineOps.activeSeat(s.state))
        def remainingFor(seat: Seat): Long =
          val bank = timeControl match
            case TimeControl.PerMove(spm) => spm.seconds
            case _                        => s.remaining.getOrElse(seat, Duration.Zero)
          val elapsed =
            if mover.contains(seat) then s.turnStartedAt.fold(Duration.Zero: FiniteDuration)(now - _) else Duration.Zero
          floorZero(bank - elapsed).toMillis
        Some(Clocks(remainingFor(Seat.White), remainingFor(Seat.Black)))

  private def mintTokens(seats: Iterable[Seat]): IO[Map[Seat, String]] =
    seats.toList.traverse(seat => randomToken.map(seat -> _)).map(_.toMap)

  private def randomToken: IO[String] = IO:
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString
