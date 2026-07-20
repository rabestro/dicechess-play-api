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

/** The two clients' post-commit dice seeds, revealed at game end alongside the server seed so anyone can re-derive
  * every roll. The HMAC message is canonical (length-prefixed), not a delimited string:
  * `HMAC-SHA256(serverSeed, uint32be(len(white)) ++ white ++ uint32be(len(black)) ++ black ++ int64be(ply))` (see
  * `DiceSource.rollMessage`). A seat that never submitted a seed falls back to its external id, shown here.
  */
final case class ClientSeeds(white: String, black: String)

/** The legal turns for a pending roll, as a prefix tree of UCI micro-moves — e.g. `{"e2e4": {"g1f3": {}}}`. Every legal
  * turn uses the maximal number of dice (the Maximum Micro-moves Rule), except a turn that captures the king, which
  * ends the game and is exclusively a leaf — so a node with no children IS a complete legal turn: walk any root-to-leaf
  * path and submit it. An empty tree means the roll has no legal move and the server is about to auto-pass. Computed by
  * the engine on the server, so a bot needs no rules implementation of its own.
  */
final case class MoveTree(children: Map[String, MoveTree])

object MoveTree:
  val empty: MoveTree = MoveTree(Map.empty)

  /** Build the tree from UCI move paths. An empty path (a pass) is not representable and is dropped — the wire signals
    * a pass as the empty tree instead.
    */
  def fromPaths(paths: List[List[String]]): MoveTree =
    MoveTree(paths.filter(_.nonEmpty).groupBy(_.head).map((move, group) => move -> fromPaths(group.map(_.tail))))

/** Whether a participant is a human or a bot — the public taxonomy the lobby and boards render. */
enum PlayerKind:
  case Human, Bot

/** The public face of a participant: enough for a board, lobby, or spectator to say WHO plays, never leaking ids. Bots
  * show their team-qualified display name; guests and users stay anonymous (their ids are private).
  */
final case class PublicPlayer(kind: PlayerKind, name: Option[String])

object PublicPlayer:
  def of(principal: Principal): PublicPlayer = principal match
    case Principal.Bot(team, name) => PublicPlayer(PlayerKind.Bot, Some(s"$team $name"))
    case _                         => PublicPlayer(PlayerKind.Human, None)

/** Both seats' public faces, as carried on the game state. */
final case class Players(white: PublicPlayer, black: PublicPlayer)

/** A wire-safe snapshot of a game, sufficient for a (re)joining client or bot to act. */
final case class PublicGameState(
    version: Long,
    dfen: String,
    activeSeat: Seat,
    dicePending: Boolean,
    status: GameStatus,
    timeControl: TimeControl,
    clocks: Option[Clocks],
    // The dice commitment (SHA-256 of the server seed, hex). Published from creation and constant for the game, so a
    // bot that only joins the game stream (and never saw the create response) can still verify the end-of-game reveal.
    commit: String,
    // The revealed server seed (hex), present only once the game has ended — so a client that (re)joins after the end
    // can still open the dice commitment. `None` while the game is active (the seed stays secret mid-game).
    seed: Option[String],
    // The client seeds folded into the dice, revealed together with `seed` (so both are `None` while active).
    clientSeeds: Option[ClientSeeds],
    // The legal turns for the pending roll. Present while `dicePending`, except when the enumeration exceeds the
    // inline cap — then it is `None` and a client fetches the full tree via `GET /games/{id}/moves`. `None` whenever
    // no roll is pending.
    legalMoves: Option[MoveTree] = None,
    // The public faces of both seats — who a board or spectator is looking at (bots by name, humans anonymous).
    players: Option[Players] = None
)

/** The full legal-move tree for a game's pending roll, served by `GET /games/{id}/moves` — never capped, unlike the
  * inline `legalMoves` on `PublicGameState`/`DiceRolled`. `version` and `dfen` tie the tree to the roll it answers; the
  * tree is empty when `dicePending` is false (between turns / game over) or the roll is a forced pass.
  */
final case class GameMoves(version: Long, dfen: String, dicePending: Boolean, legalMoves: MoveTree)

/** One completed turn, replayed to a (re)joining client in a `Snapshot` so its move history starts at move 1 rather
  * than at connect time. `dice` is the roll; `moves` are the UCI micro-moves played (empty for a forced pass);
  * `fenAfter` is the resulting position (also the next turn's starting position — the client chains from the opening).
  */
final case class SnapshotTurn(seat: Seat, dice: List[Int], moves: List[String], fenAfter: String)

/** Transport-neutral commands a player submits. NOT WebSocket/HTTP frames — the website WS edge and the Bot API are
  * codecs over this vocabulary. Kept minimal for 3a-core; draw/double/resync arrive in later milestones.
  */
enum GameCommand:
  case SubmitTurn(moves: List[String]) // the turn's micro-moves, in UCI
  // Post-commit dice entropy: a client submits a high-entropy seed after the server has locked its commitment. The
  // server folds both seats' seeds into every roll, so neither the server nor a player can grind the dice. Accepted
  // once per seat, before the first roll; ignored afterwards. The room withholds the opening roll until both seats
  // submit (or a short grace elapses, after which a missing seat falls back to its external id).
  case SubmitSeed(seed: String)
  case Resign

/** Transport-neutral events the room broadcasts. Each carries a monotonic version `v` so clients can order,
  * de-duplicate, and resync.
  */
enum GameEvent:
  // `history` is every completed turn so far, so a client that (re)joins mid-game renders the whole move list, not
  // just what happens after it connected. Atomically consistent with `v` — no separate fetch, no version race.
  case Snapshot(v: Long, state: PublicGameState, history: List[SnapshotTurn])
  // `legalMoves` carries the roll's legal turns (see MoveTree); `None` only when the enumeration exceeded the inline
  // cap — fetch `GET /games/{id}/moves` then. The empty tree announces a forced pass the server plays itself.
  case DiceRolled(
      v: Long,
      seat: Seat,
      dice: List[Int],
      dfen: String,
      clocks: Option[Clocks],
      legalMoves: Option[MoveTree]
  )
  case TurnPlayed(v: Long, seat: Seat, moves: List[String], fenAfter: String)
  // `seed` is the revealed server seed encoded as hex. Hex-decode it, then SHA-256 the raw bytes to reproduce the
  // `commit` published at creation (and echoed on every snapshot). With `clientSeeds`, the full roll transcript can be
  // recomputed using the canonical length-prefixed message (see `DiceSource.rollMessage`):
  // `roll(ply) = HMAC-SHA256(seed, uint32be(len(white)) ++ white ++ uint32be(len(black)) ++ black ++ int64be(ply))`.
  // `None` for both ONLY for a CRN-paired ladder game (#101) whose mirror partner hasn't concluded yet — the two
  // games share one secret, so revealing either early would hand away the other's still-unplayed rolls (#115).
  // Poll `GET /games/{id}` again once the partner has also ended; an ordinary (unpaired) game always reveals here.
  case GameEnded(v: Long, over: GameOver, seed: Option[String], clientSeeds: Option[ClientSeeds])
  case Rejected(v: Long, seat: Seat, reason: String)
