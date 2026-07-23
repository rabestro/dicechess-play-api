package dicechess.play.server

import cats.effect.IO
import dicechess.play.rating.Glicko2
import dicechess.play.store.{BotCatalogListing, BotCatalogStore}
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*

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

/** Public, unauthenticated read of the human-facing bot catalog (ADR-0014, E2): the bots that opened themselves to
  * human games via `POST /bot/open-to-humans`. Pure read; mounted only when persistence is configured — without the
  * database there is no `bots` table to list, same spirit as [[LeaderboardRoutes]].
  */
object CatalogRoutes:

  def apply(catalog: BotCatalogStore): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "lobby" / "bots" =>
        catalog.catalogBots.flatMap(listings => Ok(BotCatalog(listings.map(card))))

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
