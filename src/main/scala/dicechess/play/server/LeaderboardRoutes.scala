package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{PublicPlayer, Principal, Seat}
import dicechess.play.rating.Glicko2
import dicechess.play.store.{BotStore, GameResultRow, GameResultsStore, LeaderboardStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

import java.time.Instant

/** One public leaderboard row: rank is 1-based within this response. W-D-L counts rated, decided games only — the
  * ladder record, not lifetime activity.
  */
final case class LeaderRow(
    rank: Int,
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int
) derives Codec.AsObject

/** The public board. Provisional bots (RD above the convergence threshold) are absent by policy (#119). */
final case class Leaderboard(leaders: List[LeaderRow]) derives Codec.AsObject

/** One recent game from the profiled bot's point of view. `opponent` is a public face — bots by team-qualified name,
  * humans anonymous — NEVER a raw external id: a guest's stable uuid would let anyone correlate an anonymous player
  * across games, which the rest of the public wire deliberately prevents (see `PublicPlayer`).
  */
final case class RecentGame(
    gameId: String,
    seat: Seat,
    opponent: PublicPlayer,
    result: String, // "win" | "draw" | "loss" | "unknown", from the profiled bot's POV
    rated: Boolean,
    termination: String,
    finishedAt: Instant
) derives Codec.AsObject

/** A bot's public profile: the rating summary plus its recent games. Unlike the board, a provisional bot IS visible
  * here (flagged) — hiding it entirely would make `POST /bot/ladder/join` feel like a black hole for a fresh bot's
  * owner checking on their entrant.
  */
final case class BotProfile(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    onLadder: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    recent: List[RecentGame]
) derives Codec.AsObject

/** Public, unauthenticated read API over the rating ladder (D.2, #103): the leaderboard and per-bot profiles. Pure
  * reads — the data is produced elsewhere (scheduler #102 plays the games, rating batch #119 maintains
  * `bots.glicko_*`). Mounted only when persistence is configured: without the database there is neither a bots table
  * nor a `game_results` projection to read.
  */
object LeaderboardRoutes:

  /** Recent games shown on a profile — a glance at current form, not a full history. */
  val RecentGamesShown: Int = 20

  def apply(bots: BotStore, board: LeaderboardStore, results: GameResultsStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "leaderboard" =>
        board
          .leaderboard(maxRd = Glicko2.ProvisionalDeviationThreshold)
          .flatMap { entries =>
            val rows = entries.zipWithIndex.map { (entry, index) =>
              LeaderRow(
                rank = index + 1,
                team = entry.team,
                name = entry.name,
                rating = entry.rating,
                rd = entry.rd,
                onLadder = entry.onLadder,
                games = entry.tally.games,
                wins = entry.tally.wins,
                draws = entry.tally.draws,
                losses = entry.tally.losses
              )
            }
            Ok(Leaderboard(rows))
          }

      case GET -> Root / "bots" / team / name =>
        bots
          .ratingOf(team, name)
          .flatMap:
            case None         => NotFound()
            case Some(rating) =>
              val externalId = Principal.Bot(team, name).externalId
              (board.resultTallyFor(externalId), results.recentResultsFor(externalId, RecentGamesShown)).flatMapN {
                (tally, recent) =>
                  Ok(
                    BotProfile(
                      team = team,
                      name = name,
                      rating = rating.glickoRating,
                      rd = rating.glickoRd,
                      provisional = rating.glickoRd > Glicko2.ProvisionalDeviationThreshold,
                      onLadder = rating.onLadder,
                      games = tally.games,
                      wins = tally.wins,
                      draws = tally.draws,
                      losses = tally.losses,
                      recent = recent.map(recentGame(externalId, _))
                    )
                  )
              }

  /** Reframe a stored white-POV row from the profiled bot's point of view. */
  private def recentGame(profiledExternalId: String, row: GameResultRow): RecentGame =
    val profiledIsWhite    = row.whiteExternalId == profiledExternalId
    val (seat, opponentId) =
      if profiledIsWhite then (Seat.White, row.blackExternalId) else (Seat.Black, row.whiteExternalId)
    val opponent = Principal.fromBotExternalId(opponentId) match
      case Some(bot) => PublicPlayer.of(bot)
      case None      => PublicPlayer.of(Principal.Guest("")) // any non-bot renders as the anonymous human face
    val result = row.result match
      case Some(0)                      => "draw"
      case Some(1) if profiledIsWhite   => "win"
      case Some(-1) if !profiledIsWhite => "win"
      case Some(_)                      => "loss"
      case None                         => "unknown"
    RecentGame(
      gameId = row.gameId.value,
      seat = seat,
      opponent = opponent,
      result = result,
      rated = row.rated,
      termination = row.termination,
      finishedAt = row.finishedAt
    )
