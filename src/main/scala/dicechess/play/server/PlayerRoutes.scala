package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Principal, PublicPlayer, Seat}
import dicechess.play.store.{GameResultRow, GameResultsStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.HttpRoutes

import java.time.Instant

/** One finished game from the requesting guest's own point of view — the same POV reframing and opponent anonymisation
  * as `LeaderboardRoutes.RecentGame`, generalised to any participant instead of only registered bots.
  */
final case class PlayerGame(
    gameId: String,
    seat: Seat,
    opponent: PublicPlayer,
    result: String, // "win" | "draw" | "loss" | "unknown", from the requesting guest's POV
    rated: Boolean,
    termination: String,
    timeControl: String,
    finishedAt: Instant
) derives Codec.AsObject

final case class PlayerGames(games: List[PlayerGame]) derives Codec.AsObject

/** Public, unauthenticated read API for a visitor's own finished games (#151).
  *
  * "My Games" on the play site today lists only client-authoritative `/play` bot games (read from the browser's
  * IndexedDB), because the live surface (`/lobby` + `/live/[id]`) is server-authoritative and never writes locally — so
  * a visitor's lobby/live games, though durably recorded and ingested to analytics, never show up there. This exposes
  * the same recent-games read `LeaderboardRoutes` already serves for a registered bot's profile, keyed by a guest's own
  * external id instead.
  *
  * Identity: the path segment is the BARE uuid, matching the convention `POST /games` and `/lobby/seeks` already use
  * (`Principal.guest` wraps it into `guest:<uuid>` internally) — not the prefixed `external_id`, per #14. A guest uuid
  * is unguessable (uuidv7) and doubles as its holder's restore code (see the play SPA's `guestIdentity.ts`), so direct
  * lookup by the full id IS the visitor's own identity check: there is deliberately no listing/enumeration surface, and
  * an unknown-but-valid uuid returns an empty list (200), not 404 — the two are indistinguishable by design, so this
  * endpoint itself leaks no signal about which guest ids have ever played.
  *
  * Deliberately out of scope: per-game replay (turns/snapshot). `GET /games/{id}` cannot serve a finished game either
  * (`GameRegistry` evicts a room from memory once it ends) — replay for a finished game has no endpoint anywhere today,
  * live or otherwise, and adding one is a separate, larger feature.
  *
  * Mounted only when persistence is configured, like `LeaderboardRoutes`: without a database there is no `game_results`
  * projection to read.
  */
object PlayerRoutes:

  /** Default page size — same bound `GameResultsStore.recentResultsFor` already applies for bot profiles. */
  private val DefaultLimit = GameResultsStore.DefaultRecentLimit

  /** Hard cap on a client-requested `limit`: an unauthenticated endpoint must not let a caller demand an arbitrarily
    * expensive page.
    */
  private val MaxLimit = 200

  private object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")

  def apply(results: GameResultsStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "players" / guestId / "games" :? LimitParam(limit) =>
        Principal.guest(guestId) match
          case Left(error)  => BadRequest(error)
          case Right(guest) =>
            val bounded = limit.fold(DefaultLimit)(requested => math.max(1, math.min(requested, MaxLimit)))
            results
              .recentResultsFor(guest.externalId, bounded)
              .flatMap(games => Ok(PlayerGames(games.map(playerGame(guest.externalId, _)))))

  /** Reframe a stored white-POV row from the requesting guest's point of view — the same transform as
    * `LeaderboardRoutes.recentGame`, generalised to any principal instead of only bots.
    */
  private def playerGame(requesterExternalId: String, row: GameResultRow): PlayerGame =
    val requesterIsWhite   = row.whiteExternalId == requesterExternalId
    val (seat, opponentId) =
      if requesterIsWhite then (Seat.White, row.blackExternalId) else (Seat.Black, row.whiteExternalId)
    val opponent = Principal.fromBotExternalId(opponentId) match
      case Some(bot) => PublicPlayer.of(bot)
      case None      => PublicPlayer.of(Principal.Guest("")) // any non-bot renders as the anonymous human face
    val result = row.result match
      case Some(0)                       => "draw"
      case Some(1) if requesterIsWhite   => "win"
      case Some(-1) if !requesterIsWhite => "win"
      case Some(_)                       => "loss"
      case None                          => "unknown"
    PlayerGame(
      gameId = row.gameId.value,
      seat = seat,
      opponent = opponent,
      result = result,
      rated = row.rated,
      termination = row.termination,
      timeControl = row.timeControl,
      finishedAt = row.finishedAt
    )
