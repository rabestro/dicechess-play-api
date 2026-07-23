package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.rating.Glicko2
import dicechess.play.store.{BotCatalogListing, BotCatalogStore, BotStore}
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`

/** One catalog card: a bot a visitor can start a game against, plus the rating summary to show. `provisional` flags a
  * bot whose rating has not converged (RD above the threshold) — shown, not hidden, so a freshly opened bot still
  * appears (the opposite of the leaderboard's hide-until-converged policy).
  */
final case class CatalogBot(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    provisional: Boolean,
    description: Option[String]
) derives Codec.AsObject

/** The human-facing bot catalog. */
final case class BotCatalog(bots: List[CatalogBot]) derives Codec.AsObject

/** `POST /lobby/bots/{team}/{name}/wake` response: whether the bot's webhook answered the liveness probe. */
final case class Wake(alive: Boolean) derives Codec.AsObject

/** Public, unauthenticated read of the human-facing bot catalog (ADR-0014, E2) and the wake probe that precedes
  * starting a game against one (E3): the bots that opened themselves to human games via `POST /bot/open-to-humans`.
  * Pure reads; mounted only when persistence is configured — without the database there is no `bots` table to list,
  * same spirit as [[LeaderboardRoutes]]. Wake is bundled under the same gate for one feature flag, even though the
  * probe itself only needs [[BotStore]] and [[Webhooks]] (both of which have in-memory fallbacks) — without a listing
  * to click from, a wake endpoint has nothing to be called for.
  */
object CatalogRoutes:

  def apply(
      catalog: BotCatalogStore,
      bots: BotStore,
      webhooks: Option[Webhooks],
      wakeLimiter: AnonMintLimiter
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "lobby" / "bots" =>
        catalog.catalogBots.flatMap(listings => Ok(BotCatalog(listings.map(card))))

      // A visitor clicks a catalog card to start a game (ADR-0014): this wakes a scale-to-zero endpoint and reports
      // whether it answered, so the SPA knows whether to offer the game-config panel. 404 for a name outside the
      // catalog (not eligible to be woken here, whatever its webhook state); 200 alive:false covers "no webhook" and
      // "webhook didn't answer" alike — the caller only needs yes/no.
      case req @ POST -> Root / "lobby" / "bots" / team / name / "wake" =>
        bots.openToHumansBots.flatMap { open =>
          if !open.contains(Principal.Bot(team, name)) then NotFound()
          else
            webhooks match
              case None          => ServiceUnavailable("webhooks are not enabled on this server")
              case Some(service) =>
                wakeLimiter
                  .attempt(BotRoutes.clientIp(req))
                  .flatMap:
                    case Left(retryAfter) =>
                      TooManyRequests("wake rate limit exceeded — retry later")
                        .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
                    case Right(()) =>
                      service.wake(Principal.Bot(team, name)).flatMap(alive => Ok(Wake(alive)))
        }

  /** Derive the catalog card from a stored listing, flagging (not hiding) a not-yet-converged rating — the same RD
    * threshold the leaderboard uses to hide provisional bots.
    */
  private def card(listing: BotCatalogListing): CatalogBot =
    CatalogBot(
      team = listing.team,
      name = listing.name,
      rating = listing.rating,
      rd = listing.rd,
      provisional = listing.rd > Glicko2.ProvisionalDeviationThreshold,
      description = listing.description
    )
