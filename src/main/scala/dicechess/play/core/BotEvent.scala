package dicechess.play.core

/** A pending challenge from one bot to another. */
final case class Challenge(
    id: String,
    challenger: Principal,
    target: Principal,
    timeControl: TimeControl = TimeControl.Unlimited
)

/** Events pushed to a bot's account stream (`GET /bot/stream/event`), Lichess-shaped. */
enum BotEvent:
  case ChallengeReceived(id: String, challenger: Principal)
  case ChallengeDeclined(id: String)
  case GameStart(gameId: String)
