package dicechess.play.core

/** Why a game ended. Maps to the analytics `game_termination_enum` at ingest time.
  *
  * `Aborted` is a server-side abort (the writer fiber failed or was cancelled, e.g. on shutdown); the game has no
  * sporting result. `Timeout` is the player to move failing to act within the turn deadline (a forfeit). Their
  * analytics mappings are finalized with the analytics handoff.
  */
enum Termination:
  case KingCaptured, Resign, Draw, Aborted, Timeout

enum GameResult:
  case Win(side: Side)
  case Draw

final case class GameOver(result: GameResult, termination: Termination)

enum GameStatus:
  case Active
  case Ended(over: GameOver)

/** A game's time control, chosen at creation. `Unlimited` is today's behavior (only the anti-abandonment turn deadline
  * applies). The timed variants are **not enforced yet** — recorded forward-compat so the creation API and wire are
  * stable before clocks land (see the "Time control / clocks" milestone). Distinct from the engine's move-search
  * TimeManager, which budgets a bot's own thinking rather than the authoritative game clock.
  */
enum TimeControl:
  case Unlimited
  case SuddenDeath(initialSeconds: Int)
  case Fischer(initialSeconds: Int, incrementSeconds: Int)
  case PerMove(secondsPerMove: Int)

/** Remaining time per side, in **milliseconds**, as of the event that carries it. The side to move is still ticking, so
  * a client counts its clock down locally between server updates; the other side's value is exact until its next turn.
  * `Unlimited` games carry no clocks (the field is absent), so it appears only on timed games.
  */
final case class Clocks(white: Long, black: Long)

/** A wire-safe snapshot of a game, sufficient for a (re)joining client or bot to act. */
final case class PublicGameState(
    version: Long,
    dfen: String,
    activeSeat: Seat,
    dicePending: Boolean,
    status: GameStatus,
    timeControl: TimeControl,
    clocks: Option[Clocks]
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
  case DiceRolled(v: Long, seat: Seat, dice: List[Int], dfen: String, clocks: Option[Clocks])
  case TurnPlayed(v: Long, seat: Seat, moves: List[String], fenAfter: String)
  // `seed` is the revealed server seed (hex): SHA-256(seed) equals the `commit` published at creation, so anyone can
  // open the dice commitment after the game.
  case GameEnded(v: Long, over: GameOver, seed: String)
  case Rejected(v: Long, seat: Seat, reason: String)
