package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{PublicPlayer, Principal, Seat}
import dicechess.play.rating.Glicko2
import dicechess.play.store.{
  BotStore,
  GameResultRow,
  GameResultsStore,
  LeaderboardEntry,
  LeaderboardStore,
  PlayerLeaderboardEntry,
  UserAccount,
  UserRating,
  UserStore
}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

import java.time.Instant

/** One public leaderboard row: rank is 1-based within this response. W-D-L counts rated, decided games only — the
  * ladder record, not lifetime activity.
  */
final case class LeaderRow(
    rank: Int,
    // "bot" | "player" (#249). Bots and accounts share ONE Glicko scale, so a merged board is honest — but the default
    // response stays bots-only and byte-compatible apart from this added field, so the SPA is not broken by the change.
    kind: String,
    // Absent for an account: a person has no team, only the nickname carried in `name`.
    team: Option[String],
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int
) derives Codec.AsObject

/** The public board. Provisional entrants (RD above the convergence threshold) are absent by policy (#119) — the same
  * rule for accounts as for bots, since it is one scale.
  */
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

/** A bot's public profile: the rating summary, its recent games, and its aggregate record against every opponent it has
  * played (#182) — one row per other bot (head-to-head) plus one collapsed row for every human/guest opponent combined
  * ("record vs humans"). Unlike `games`/`wins`/`draws`/`losses` above (rated, decided games only — the ladder record),
  * `opponents` counts every game, rated or casual: a guest game is always casual (`GameRegistry.isRated`), so a
  * rated-only tally would always read zero against humans. Unlike the board, a provisional bot IS visible here
  * (flagged) — hiding it entirely would make `POST /bot/ladder/join` feel like a black hole for a fresh bot's owner
  * checking on their entrant.
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
    opponents: List[PlayerOpponent],
    recent: List[RecentGame]
) derives Codec.AsObject

/** An account's PUBLIC profile (#249) — deliberately the same shape as [[BotProfile]] so the SPA renders both with one
  * component, minus what only a bot has (`team`, `onLadder`).
  *
  * What is absent is the point: no email, no account uuid, and no trace of which guest identities this player claimed.
  * `/me/games` merges that history for its owner; folding it in HERE would retroactively deanonymise every anonymous
  * game those ids ever played, which is exactly the promise #236 made. So the record below counts `user:` games only. A
  * provisional player IS visible here (flagged), matching the bot profile: hiding a fresh account from its own page
  * would make signing up feel like a black hole.
  */
final case class PlayerProfile(
    nickname: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    games: Int,
    wins: Int,
    draws: Int,
    losses: Int,
    opponents: List[PlayerOpponent],
    recent: List[RecentGame]
) derives Codec.AsObject

/** Public, unauthenticated read API over the rating ladder (D.2, #103): the leaderboard and per-bot profiles. Pure
  * reads — the data is produced elsewhere (scheduler #102 plays the games, rating batch #119 maintains
  * `bots.glicko_*`). Mounted only when persistence is configured: without the database there is neither a bots table
  * nor a `game_results` projection to read.
  */
object LeaderboardRoutes:

  /** Which populations a board response covers. */
  final private[server] case class Populations(bots: Boolean, players: Boolean)

  /** `?kind=` → populations. Default `bots` keeps the existing response for the existing caller; an unrecognised value
    * is a 400 rather than a silent fallback, so a typo cannot look like an empty board.
    */
  private[server] def parseKind(kind: Option[String]): Either[String, Populations] =
    kind match
      case None | Some("bots") => Right(Populations(bots = true, players = false))
      case Some("players")     => Right(Populations(bots = false, players = true))
      case Some("all")         => Right(Populations(bots = true, players = true))
      case Some(other)         => Left(s"kind: '$other' must be 'bots', 'players', or 'all'")

  /** Rank is assigned after merging, so both builders emit 0 and the caller overwrites it. */
  private def botRow(entry: LeaderboardEntry): LeaderRow =
    LeaderRow(
      rank = 0,
      kind = "bot",
      team = Some(entry.team),
      name = entry.name,
      rating = entry.rating,
      rd = entry.rd,
      onLadder = entry.onLadder,
      games = entry.tally.games,
      wins = entry.tally.wins,
      draws = entry.tally.draws,
      losses = entry.tally.losses
    )

  private def playerRow(entry: PlayerLeaderboardEntry): LeaderRow =
    LeaderRow(
      rank = 0,
      kind = "player",
      team = None,
      name = entry.nickname,
      // A person is never "on the ladder": that flag is the bot scheduler's, and there is no scheduler for people.
      onLadder = false,
      rating = entry.rating,
      rd = entry.rd,
      games = entry.tally.games,
      wins = entry.tally.wins,
      draws = entry.tally.draws,
      losses = entry.tally.losses
    )

  /** Recent games shown on a profile — a glance at current form, not a full history. */
  val RecentGamesShown: Int = 20

  /** `?kind=` selects which populations the board covers (#249). The default is `bots`, NOT `all`: the SPA already
    * calls `/leaderboard` and must keep getting exactly what it does today, so the merged view is opt-in.
    */
  private object KindParam extends OptionalQueryParamDecoderMatcher[String]("kind")

  def apply(
      bots: BotStore,
      board: LeaderboardStore,
      results: GameResultsStore,
      users: Option[UserStore] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "leaderboard" :? KindParam(kind) =>
        LeaderboardRoutes.parseKind(kind) match
          case Left(error)      => BadRequest(error)
          case Right(wantedFor) =>
            val maxRd    = Glicko2.ProvisionalDeviationThreshold
            val botRows  = if wantedFor.bots then board.leaderboard(maxRd) else IO.pure(Nil)
            val playRows = if wantedFor.players then board.playerLeaderboard(maxRd) else IO.pure(Nil)
            (botRows, playRows).flatMapN: (botEntries, playerEntries) =>
              // Ranked across both populations by rating, because they ARE one scale — a separate rank per kind would
              // imply two currencies. Ties break exactly as each single-population query already orders.
              val merged = botEntries.map(LeaderboardRoutes.botRow) ++ playerEntries.map(LeaderboardRoutes.playerRow)
              val ranked = merged
                .sortBy(row => (-row.rating, row.rd, row.name))
                .zipWithIndex
                .map((row, index) => row.copy(rank = index + 1))
              Ok(Leaderboard(ranked))

      // The account counterpart of `GET /bots/{team}/{name}`, keyed by the only public handle a person has. Not
      // `/players/{something}`: that shape is taken by the guest-history reads, whose path segment is a bare uuid.
      case GET -> Root / "players" / "by-nickname" / nickname =>
        users match
          // Without persistence there are no accounts at all; the whole route set is unmounted in that mode anyway
          // (see Main), so this only guards a caller that wired the routes without a user store.
          case None        => NotFound()
          case Some(store) =>
            store.byNickname(nickname).flatMap {
              case Some(account) if account.isActive => playerProfile(store, board, results, account)
              // A deactivated account is indistinguishable from a missing one, deliberately: the public API must not
              // confirm that a given nickname exists but is blocked.
              case _ => NotFound()
            }

      case GET -> Root / "bots" / team / name =>
        bots
          .ratingOf(team, name)
          .flatMap:
            case None         => NotFound()
            case Some(rating) =>
              val externalId = Principal.Bot(team, name).externalId
              (
                board.resultTallyFor(externalId),
                results.recentResultsFor(externalId, RecentGamesShown),
                results.opponentsFor(List(externalId))
              ).flatMapN: (tally, recent, opponents) =>
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
                    opponents = opponents.map(playerOpponent),
                    recent = recent.map(recentGame(externalId, _))
                  )
                )

  /** The account profile's own reads, scoped to the account's `user:` id ONLY — never its claimed guest ids (see
    * [[PlayerProfile]] for why that scoping is the privacy promise, not an oversight).
    */
  private def playerProfile(
      users: UserStore,
      board: LeaderboardStore,
      results: GameResultsStore,
      account: UserAccount
  ): IO[Response[IO]] =
    val externalId = Principal.User(account.id).externalId
    (
      users.ratingOf(account.id),
      board.resultTallyFor(externalId),
      results.recentResultsFor(externalId, RecentGamesShown),
      results.opponentsFor(List(externalId))
    ).flatMapN: (rating, tally, recent, opponents) =>
      val state = rating.getOrElse(UserRating.initial)
      Ok(
        PlayerProfile(
          nickname = account.nickname,
          rating = state.glickoRating,
          rd = state.glickoRd,
          provisional = state.glickoRd > Glicko2.ProvisionalDeviationThreshold,
          games = tally.games,
          wins = tally.wins,
          draws = tally.draws,
          losses = tally.losses,
          opponents = opponents.map(playerOpponent),
          recent = recent.map(recentGame(externalId, _))
        )
      )

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
