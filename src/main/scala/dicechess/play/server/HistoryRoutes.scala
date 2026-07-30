package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.{ArchivedGame, GameArchive, GameArchiveStore, GameResultsStore, TurnRecord}
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

/** The dice-fairness reveal (#115): `commit` is always present (published at creation, never secret); `seed` and
  * `clientSeeds` are `None` until `revealEligible` — see `HistoryRoutes`'s own doc.
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
  * '''The reveal gate (#115).''' A CRN-paired ladder game shares its dice secret with its mirror partner — revealing
  * early would leak the partner's still-unplayed rolls the instant this game's history is fetched, while the partner
  * may still be live. `commit` (the published commitment) is always shown; `seed`/`clientSeeds` are withheld until the
  * partner has ALSO finished, which this endpoint checks the same way the live wire does (`GameRoom.Session.publicAt`)
  * but simpler: since `game_results` is written in the SAME transaction as a game's own terminal snapshot save, "does a
  * `game_results` row exist for the partner's id" IS "has the partner ended" — no in-memory room lookup needed, only
  * `GameResultsStore.pairFor`, which this route already has a reason to depend on.
  *
  * '''Caching.''' A fully revealed record can never change again — cached as `public, max-age=1y, immutable`. A
  * withheld one is re-checked far sooner, so the reveal shows up for a client that re-fetches once the partner
  * concludes ("self-heals", same phrase `GameRegistry.partnerEndedCheck` uses for the live equivalent).
  */
object HistoryRoutes:

  /** How long a withheld (CRN partner still running) record may be cached — short, since the very next fetch after the
    * partner ends should see the reveal promptly, not after up to a full day of staleness.
    */
  private val WithheldMaxAge: FiniteDuration = 60.seconds

  private val RevealedMaxAge: FiniteDuration = 365.days

  def apply(archive: GameArchiveStore, results: GameResultsStore): HttpRoutes[IO] =
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
                      revealEligible(record, results).flatMap { eligible =>
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
                            seed = Option.when(eligible)(record.serverSeed),
                            clientSeeds =
                              Option.when(eligible)(ClientSeeds(record.clientSeedWhite, record.clientSeedBlack))
                          )
                        )
                        val cacheControl =
                          if eligible then
                            `Cache-Control`(
                              CacheDirective.public,
                              CacheDirective.`max-age`(RevealedMaxAge),
                              CacheDirective("immutable")
                            )
                          else `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(WithheldMaxAge))
                        Ok(body).map(_.putHeaders(cacheControl))
                      }

  /** No partner: always eligible (the ordinary, unpaired case — unchanged from before CRN pairing existed). A CRN
    * partner is eligible once `game_results` carries a row for that SPECIFIC partner id (not merely "2 rows share this
    * pairing" — `pairingId` is a fresh UUID per pair by construction, but checking the exact id is the one guarantee
    * that doesn't rely on that invariant holding forever).
    *
    * `partnerGameId` present without `pairingId` never happens by construction (`GameRegistry.createMirroredPair` sets
    * both together) — but if it somehow did, this fails CLOSED (withheld), not open: the harmful direction for a
    * dice-security gate is revealing early, not withholding an unreachable case a little longer.
    */
  private def revealEligible(record: GameArchive.Record, results: GameResultsStore): IO[Boolean] =
    (record.partnerGameId, record.pairingId) match
      case (None, _)                    => IO.pure(true)
      case (Some(partnerId), Some(pid)) => results.pairFor(pid).map(_.exists(_.gameId.value == partnerId))
      case (Some(_), None)              => IO.pure(false)

  private def publicPlayerOf(externalId: String): PublicPlayer =
    Principal.fromBotExternalId(externalId).fold(AnonymousHuman)(PublicPlayer.of)

  private def historyTurn(t: TurnRecord): HistoryTurn =
    HistoryTurn(t.turnNumber, seatOf(t.activeColor), t.dice, t.moves, t.fenAfter)

  /** `TurnRecord.activeColor` is always exactly "w" or "b" (see its own doc) — this is the one place that convention
    * gets promoted to the public `Seat` the rest of the wire uses.
    */
  private def seatOf(activeColor: String): Seat = if activeColor == "w" then Seat.White else Seat.Black
