package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.{ArchivedGame, GameArchive, GameArchiveStore, TurnRecord}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import org.http4s.{CacheDirective, HttpRoutes}

import java.time.Instant
import scala.concurrent.duration.*

/** One archived turn on the public wire — the same fields `GameArchive` persisted, `activeColor` promoted from its
  * stored "w"/"b" convention to the `Seat` the rest of this resource family (`GET /games/{id}`) already uses.
  */
final case class HistoryTurn(
    turnNumber: Long,
    activeColor: Seat,
    dice: List[Int],
    moves: List[String],
    fenAfter: String
) derives Codec.AsObject

/** The dice-fairness reveal: `commit` is always present (published at creation, never secret); `seed` and `clientSeeds`
  * are populated once the game has ended — always, for a game recorded here at all, since the archive only ever holds
  * finished games.
  */
final case class HistoryFairness(commit: Option[String], seed: Option[String], clientSeeds: Option[ClientSeeds])
    derives Codec.AsObject

/** `GET /games/{id}/history`'s response (#178): a finished game's full replay, players anonymized exactly like the live
  * wire's `Players`. `result`/`termination` are white-POV/neutral — there is no requester identity here, unlike
  * `PlayerRoutes`'s own POV-reframed `PlayerGame`. `timeControl` is the structured ADT (matching the live
  * `PublicGameState`, NOT `PlayerGame`'s stringified `game_results` column) so a replay page can reuse the exact same
  * rendering the live board already has for the same game.
  */
final case class GameHistory(
    gameId: String,
    players: Players,
    rated: Boolean,
    timeControl: TimeControl,
    result: Int,
    termination: String,
    finishedAt: Instant,
    initialDfen: String,
    turns: List[HistoryTurn],
    fairness: HistoryFairness
) derives Codec.AsObject

/** Public, unauthenticated replay for a finished game (#178) — the read side of the durable archive `GameArchive`
  * writes (#177). Mounted only when persistence is configured, like `PlayerRoutes`/`LeaderboardRoutes`: without a
  * database there is no `game_archive` to read.
  *
  * '''Caching.''' Every record here is a finished game and can never change again — cached as
  * `public, max-age=1y, immutable`.
  */
object HistoryRoutes:

  private val RevealedMaxAge: FiniteDuration = 365.days

  def apply(archive: GameArchiveStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "games" / id / "history" =>
        // `game_archive.game_id` is a `uuid` column, so a non-UUID path segment would otherwise reach the store's
        // `::uuid` cast and blow up as a database error instead of the plain "no such game" this route means to say —
        // same non-distinction PlayRoutes' in-memory lookup already makes for free (a garbage id just misses the map).
        Either.catchOnly[IllegalArgumentException](java.util.UUID.fromString(id)) match
          case Left(_)  => NotFound()
          case Right(_) =>
            archive
              .archiveFor(GameId(id))
              .flatMap:
                case None                                    => NotFound()
                case Some(ArchivedGame(payload, finishedAt)) =>
                  GameArchive.decode(payload) match
                    // The row was written by this same server — a decode failure here means the write/read shapes
                    // have drifted, a bug to surface loudly, not a client-facing 404 (the game plainly did finish
                    // and archive).
                    case Left(failure) => InternalServerError(s"corrupt archive row for $id: ${failure.getMessage}")
                    case Right(record) =>
                      val body = GameHistory(
                        gameId = id,
                        players =
                          Players(publicPlayerOf(record.whiteExternalId), publicPlayerOf(record.blackExternalId)),
                        rated = record.rated,
                        timeControl = record.timeControl,
                        result = record.result,
                        termination = record.termination,
                        finishedAt = finishedAt,
                        initialDfen = record.initialDfen,
                        turns = record.turns.map(historyTurn),
                        fairness = HistoryFairness(
                          commit = record.commit,
                          seed = Some(record.serverSeed),
                          clientSeeds = Some(ClientSeeds(record.clientSeedWhite, record.clientSeedBlack))
                        )
                      )
                      val cacheControl =
                        `Cache-Control`(
                          CacheDirective.public,
                          CacheDirective.`max-age`(RevealedMaxAge),
                          CacheDirective("immutable")
                        )
                      Ok(body).map(_.putHeaders(cacheControl))

  private def publicPlayerOf(externalId: String): PublicPlayer =
    Principal.fromBotExternalId(externalId).fold(AnonymousHuman)(PublicPlayer.of)

  private def historyTurn(t: TurnRecord): HistoryTurn =
    HistoryTurn(t.turnNumber, seatOf(t.activeColor), t.dice, t.moves, t.fenAfter)

  /** `TurnRecord.activeColor` is always exactly "w" or "b" (see its own doc) — this is the one place that convention
    * gets promoted to the public `Seat` the rest of the wire uses.
    */
  private def seatOf(activeColor: String): Seat = if activeColor == "w" then Seat.White else Seat.Black
