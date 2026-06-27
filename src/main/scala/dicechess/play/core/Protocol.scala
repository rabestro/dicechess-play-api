package dicechess.play.core

/** Why a game ended. Maps to the analytics `game_termination_enum` at ingest time.
  *
  * `Aborted` is a server-side abort (the writer fiber failed or was cancelled, e.g. on shutdown); the game has no
  * sporting result. Its analytics mapping is finalized with the analytics handoff.
  */
enum Termination:
  case KingCaptured, Resign, Draw, Aborted

enum GameResult:
  case Win(side: Side)
  case Draw

final case class GameOver(result: GameResult, termination: Termination)

enum GameStatus:
  case Active
  case Ended(over: GameOver)

/** A wire-safe snapshot of a game, sufficient for a (re)joining client or bot to act. */
final case class PublicGameState(
    version: Long,
    dfen: String,
    activeSeat: Seat,
    dicePending: Boolean,
    status: GameStatus
)

/** Transport-neutral commands a player submits. NOT WebSocket/HTTP frames — the website WS edge and the Bot API are
  * codecs over this vocabulary. Kept minimal for 3a-core; draw/double/resync arrive in later milestones.
  */
enum GameCommand:
  case SubmitTurn(moves: List[String]) // the turn's micro-moves, in UCI
  case Resign

/** Transport-neutral events the room broadcasts. Each carries a monotonic version `v` so clients can order,
  * de-duplicate, and resync.
  */
enum GameEvent:
  case Snapshot(v: Long, state: PublicGameState)
  case DiceRolled(v: Long, seat: Seat, dice: List[Int], dfen: String)
  case TurnPlayed(v: Long, seat: Seat, moves: List[String], fenAfter: String)
  case GameEnded(v: Long, over: GameOver)
  case Rejected(v: Long, seat: Seat, reason: String)
