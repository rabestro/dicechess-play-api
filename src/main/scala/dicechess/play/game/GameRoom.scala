package dicechess.play.game

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import dicechess.engine.domain.GameState
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import fs2.Stream
import fs2.concurrent.Topic

/** The authoritative game room. A single consumer fiber drains an inbox and is the only writer of game state (the
  * mailbox-serialization an actor gives, without the framework); all reads are lock-free via `Ref.get`. Events fan out
  * to every subscriber (both players plus spectators) through an fs2 `Topic`.
  *
  * The room is transport-agnostic: its only surface is `subscribe` / `submit` / `start`, so a human over WebSocket and
  * a bot over HTTP attach as adapters (see PlayerConnection).
  */
final class GameRoom private (
    stateRef: Ref[IO, GameRoom.Session],
    inbox: Queue[IO, GameRoom.Msg],
    topic: Topic[IO, GameEvent],
    done: Deferred[IO, GameOver]
):
  import GameRoom.*

  /** Current state, then the live event feed — so a late subscriber can still act. */
  def subscribe: Stream[IO, GameEvent] =
    Stream
      .resource(topic.subscribeAwait(256))
      .flatMap: live =>
        Stream.eval(stateRef.get.map(s => GameEvent.Snapshot(s.version, s.public))) ++ live

  def submit(seat: Seat, command: GameCommand): IO[Unit] = inbox.offer(Msg.Command(seat, command))

  /** Begin the game (roll the first turn). Call after subscribers have attached. */
  def start: IO[Unit] = inbox.offer(Msg.Begin)

  /** Completes when the game ends. */
  def result: IO[GameOver] = done.get

  /** Who is seated where. */
  def seating: IO[Map[Seat, Principal]] = stateRef.get.map(_.players)

  // ── consumer fiber ─────────────────────────────────────────────────────────
  private def consume: IO[Unit] =
    inbox.take.flatMap:
      case Msg.Begin =>
        stateRef.get.flatMap(beginTurn).flatMap(stateRef.set) *> continue
      case Msg.Command(seat, command) =>
        stateRef.get.flatMap(s => process(s, seat, command)).flatMap(stateRef.set) *> continue

  private def continue: IO[Unit] =
    stateRef.get.flatMap(s => if s.ended then IO.unit else consume)

  /** Advance the session, write it, THEN publish — so the Ref always reflects the latest published event. A subscriber
    * that registers just after a publish reads a current Snapshot (and acts), and one that registers just before
    * catches the live event; publishing before the write would let a subscriber in that window miss both and hang.
    */
  private def emit(s: Session, make: Long => GameEvent): IO[Session] =
    val s2 = s.copy(version = s.version + 1)
    stateRef.set(s2) *> topic.publish1(make(s2.version)).void.as(s2)

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

  def create(
      players: Map[Seat, Principal],
      dice: DiceSource,
      initialDfen: String = EngineOps.InitialDfen
  ): IO[GameRoom] =
    for
      state0 <- IO.fromEither(EngineOps.parse(initialDfen).leftMap(msg => RuntimeException(s"bad initial FEN: $msg")))
      ref    <- Ref.of[IO, Session](Session(state0, 0L, players, dice, 0L, pending = false, GameStatus.Active))
      inbox  <- Queue.unbounded[IO, Msg]
      topic  <- Topic[IO, GameEvent]
      done   <- Deferred[IO, GameOver]
      room = new GameRoom(ref, inbox, topic, done)
      _ <- room.consume.start
    yield room
