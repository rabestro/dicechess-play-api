package dicechess.play.game

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import dicechess.engine.domain.GameState
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import fs2.Stream

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
    done: Deferred[IO, GameOver]
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
        val snapshot = Stream.eval(stateRef.get.map(s => GameEvent.Snapshot(s.version, s.public)))
        val live     = Stream.fromQueueUnterminated(sub.queue)
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
    case GameEvent.GameEnded(_, _) => true
    case GameEvent.Snapshot(_, ps) =>
      ps.status match
        case GameStatus.Ended(_) => true
        case GameStatus.Active   => false
    case _ => false

  def submit(seat: Seat, command: GameCommand): IO[Unit] = inbox.offer(Msg.Command(seat, command))

  /** Begin the game (roll the first turn). Call after subscribers have attached. */
  def start: IO[Unit] = inbox.offer(Msg.Begin)

  /** Completes when the game ends. */
  def result: IO[GameOver] = done.get

  /** Who is seated where. */
  def seating: IO[Map[Seat, Principal]] = stateRef.get.map(_.players)

  /** The dice commitment (SHA-256 of the server seed), published at game start. */
  def diceCommit: IO[String] = stateRef.get.map(_.dice.commit)

  /** Current public state (for a REST snapshot or a freshly-joining client). */
  def snapshot: IO[PublicGameState] = stateRef.get.map(_.public)

  // ── consumer fiber ─────────────────────────────────────────────────────────
  private def consume: IO[Unit] =
    inbox.take.flatMap:
      case Msg.Begin =>
        stateRef.get.flatMap { s =>
          // Idempotent: only the first Begin (before any roll) starts the game; a repeated
          // or retried start must not re-roll a pending turn or double-increment the ply.
          if s.ply == 0L && !s.ended then beginTurn(s).flatMap(stateRef.set) else IO.unit
        } *> continue
      case Msg.Command(seat, command) =>
        stateRef.get.flatMap(s => process(s, seat, command)).flatMap(stateRef.set) *> continue

  private def continue: IO[Unit] =
    stateRef.get.flatMap(s => if s.ended then IO.unit else consume)

  /** Advance the session, write it, THEN broadcast — so the Ref always reflects the latest published event. A
    * subscriber that registers just after a broadcast reads a current Snapshot (and acts), and one that registers just
    * before catches the live event; broadcasting before the write would let a subscriber in that window miss both and
    * hang.
    */
  private def emit(s: Session, make: Long => GameEvent): IO[Session] =
    val s2 = s.copy(version = s.version + 1)
    stateRef.set(s2) *> broadcast(make(s2.version)).as(s2)

  /** Roll for the side to move; publish the roll; auto-pass while there is no legal move. */
  private def beginTurn(s0: Session): IO[Session] =
    val dice   = s0.dice.roll(s0.ply)
    val rolled = EngineOps.withDice(s0.state, dice)
    val seat   = EngineOps.activeSeat(rolled)
    val s1     = s0.copy(state = rolled, ply = s0.ply + 1, pending = true)
    emit(s1, v => GameEvent.DiceRolled(v, seat, dice, EngineOps.serialize(rolled))).flatMap: s2 =>
      if EngineOps.legalMovePaths(rolled).nonEmpty then IO.pure(s2)
      else
        val passed = rolled.endTurn()
        val s3     = s2.copy(state = passed, pending = false)
        emit(s3, v => GameEvent.TurnPlayed(v, seat, Nil, EngineOps.serialize(passed)))
          .flatMap(advanceOrEnd)

  /** After a completed turn (or a pass), either end on a limit/draw or roll the next turn. */
  private def advanceOrEnd(s: Session): IO[Session] =
    if s.state.halfMoveClock >= FiftyMoveHalfMoves then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else if s.ply >= MaxPlies then endGame(s, GameOver(GameResult.Draw, Termination.Draw))
    else beginTurn(s)

  private def endGame(s: Session, over: GameOver): IO[Session] =
    emit(s.copy(pending = false, status = GameStatus.Ended(over)), v => GameEvent.GameEnded(v, over))
      .flatTap(_ => done.complete(over).attempt.void)

  private def process(s: Session, seat: Seat, command: GameCommand): IO[Session] =
    s.status match
      case GameStatus.Ended(_) => IO.pure(s)
      case GameStatus.Active   =>
        command match
          case GameCommand.Resign =>
            seat.side match
              case Some(loser) => endGame(s, GameOver(GameResult.Win(loser.opponent), Termination.Resign))
              case None        => emit(s, v => GameEvent.Rejected(v, seat, "spectator cannot resign"))

          case GameCommand.SubmitTurn(uci) =>
            if !s.pending || seat != EngineOps.activeSeat(s.state) then
              emit(s, v => GameEvent.Rejected(v, seat, "not your turn"))
            else
              EngineOps.findLegalPath(s.state, uci) match
                case None       => emit(s, v => GameEvent.Rejected(v, seat, "illegal turn"))
                case Some(path) =>
                  val (next, winner) = EngineOps.applyPath(s.state, path)
                  emit(
                    s.copy(state = next, pending = false),
                    v => GameEvent.TurnPlayed(v, seat, uci, EngineOps.serialize(next))
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

  /** A live subscriber's mailbox plus a one-shot "you fell behind, disconnect" signal. */
  final private case class Subscriber(id: Long, queue: Queue[IO, GameEvent], dropped: Deferred[IO, Unit])

  private enum Msg:
    case Begin
    case Command(seat: Seat, command: GameCommand)

  final private case class Session(
      state: GameState,
      version: Long,
      players: Map[Seat, Principal],
      dice: DiceSource,
      ply: Long,
      pending: Boolean,
      status: GameStatus
  ):
    def ended: Boolean = status match
      case GameStatus.Ended(_) => true
      case GameStatus.Active   => false

    def public: PublicGameState =
      PublicGameState(version, EngineOps.serialize(state), EngineOps.activeSeat(state), pending, status)

  /** Create a room, or describe why the initial position is invalid — errors as values. */
  def create(
      players: Map[Seat, Principal],
      dice: DiceSource,
      initialDfen: String = EngineOps.InitialDfen,
      fanOutBuffer: Int = DefaultFanOutBuffer
  ): IO[Either[String, GameRoom]] =
    EngineOps.parse(initialDfen) match
      case Left(error)   => IO.pure(Left(error))
      case Right(state0) =>
        for
          ref         <- Ref.of[IO, Session](Session(state0, 0L, players, dice, 0L, pending = false, GameStatus.Active))
          inbox       <- Queue.unbounded[IO, Msg]
          subscribers <- Ref.of[IO, Map[Long, Subscriber]](Map.empty)
          nextId      <- Ref.of[IO, Long](0L)
          done        <- Deferred[IO, GameOver]
          room = new GameRoom(ref, inbox, subscribers, nextId, fanOutBuffer, done)
          _ <- room.consume.start
        yield Right(room)
