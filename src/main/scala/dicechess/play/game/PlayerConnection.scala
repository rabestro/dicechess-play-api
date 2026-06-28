package dicechess.play.game

import cats.effect.{IO, Ref}
import dicechess.engine.search.SearchAlgorithm
import dicechess.play.core.*

/** A player attached to a room. The room knows nothing about transports — a connection is "a principal at a seat that
  * consumes events and submits commands". The website WebSocket and the third-party Bot API are two implementations of
  * this seam.
  */
trait PlayerConnection:
  def principal: Principal
  def seat: Seat

  /** Drive this connection against the room until the game ends. */
  def run(room: GameRoom): IO[Unit]

/** A bot player: when it is handed dice, it computes a turn with an engine `SearchAlgorithm` and submits it. Reacts to
  * either a `DiceRolled` for its seat or a `Snapshot` showing a pending turn for its seat (so it acts even if it joined
  * late), de-duplicating by version.
  */
final class BotConnection(
    val principal: Principal,
    val seat: Seat,
    algorithm: SearchAlgorithm
) extends PlayerConnection:

  def run(room: GameRoom): IO[Unit] =
    Ref
      .of[IO, Long](-1L)
      .flatMap: handled =>
        room.subscribe
          .evalTap(event => act(room, handled, event))
          .collectFirst { case ended: GameEvent.GameEnded => ended }
          .compile
          .drain

  private def act(room: GameRoom, handled: Ref[IO, Long], event: GameEvent): IO[Unit] =
    turnFor(event) match
      case None                  => IO.unit
      case Some((version, dfen)) =>
        handled
          .modify(last => if version > last then (version, true) else (last, false))
          .flatMap(fresh => if fresh then chooseAndSubmit(room, dfen) else IO.unit)

  /** Extracts (version, dfen-with-dice) when it is this seat's turn to move. */
  private def turnFor(event: GameEvent): Option[(Long, String)] = event match
    case GameEvent.DiceRolled(v, s, _, dfen, _) if s == seat                  => Some((v, dfen))
    case GameEvent.Snapshot(v, ps) if ps.dicePending && ps.activeSeat == seat => Some((v, ps.dfen))
    case _                                                                    => None

  private def chooseAndSubmit(room: GameRoom, dfen: String): IO[Unit] =
    EngineOps.parse(dfen) match
      case Left(_)      => IO.unit
      case Right(state) =>
        algorithm.findBestMove(state) match
          case None      => IO.unit // forced pass: the room advances on its own
          case Some(seq) => room.submit(seat, GameCommand.SubmitTurn(seq.moves.map(EngineOps.toUci)))
