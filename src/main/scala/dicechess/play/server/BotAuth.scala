package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Authenticates third-party bots by an opaque Bearer token. This is a single-author admin gate: tokens are configured
  * in the environment, each bound to a `Principal.Bot(team, name)`. There is no user system and no persistence yet —
  * durable, dynamically-issued tokens arrive with the durability slice. `isBot` is implied: holding a bot token *is*
  * the identity, so a guest/user can never masquerade as a bot.
  */
final class BotAuth private (byToken: Map[String, Principal]):

  /** The principal a token identifies, if any (always a `Principal.Bot`). Tokens are compared in constant time so one
    * can't be recovered by timing.
    */
  def authenticate(token: String): Option[Principal] =
    val presented = token.getBytes(UTF_8)
    byToken.collectFirst {
      case (configured, bot) if MessageDigest.isEqual(configured.getBytes(UTF_8), presented) => bot
    }

object BotAuth:

  /** Env var holding the bot roster: comma-separated `team|name|token` entries. */
  private val EnvVar = "PLAY_BOT_TOKENS"

  def fromEnv: IO[BotAuth] = IO(sys.env.getOrElse(EnvVar, "")).map(parse)

  /** Parse a roster spec — `team|name|token` entries separated by commas. Malformed or empty entries are ignored. */
  def parse(spec: String): BotAuth =
    val byToken: Map[String, Principal] = spec
      .split(',')
      .toList
      .flatMap { entry =>
        entry.split('|').toList match
          case team :: name :: token :: Nil if team.nonEmpty && name.nonEmpty && token.nonEmpty =>
            Some(token -> Principal.Bot(team, name))
          case _ => None
      }
      .toMap
    new BotAuth(byToken)
