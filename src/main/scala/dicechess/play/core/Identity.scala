package dicechess.play.core

/** The two playing sides. Decoupled from the engine's `Color`. */
enum Side:
  case White, Black

  def opponent: Side = this match
    case White => Black
    case Black => White

/** A seat at a game: the two players, plus read-only spectators. */
enum Seat:
  case White, Black, Spectator

  def side: Option[Side] = this match
    case White     => Some(Side.White)
    case Black     => Some(Side.Black)
    case Spectator => None

/** A stable participant identity. The game room depends only on this — never on a concrete transport — so humans
  * (WebSocket) and bots (HTTP) plug in as adapters.
  */
enum Principal:
  case Guest(id: String)
  case User(id: String)
  case Bot(team: String, name: String)

  /** Canonical analytics `external_id`. */
  def externalId: String = this match
    case Guest(id)       => s"guest:$id"
    case User(id)        => s"user:$id"
    case Bot(team, name) => s"bot:team:$team:$name"
