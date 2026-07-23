package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Principal, Seat, Side, TimeControl}
import dicechess.play.rating.Glicko2
import dicechess.play.store.{BotCatalogListing, BotCatalogStore, BotStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`
import org.http4s.{Request, Response}

import java.security.SecureRandom

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

/** `POST /lobby/play-bot` body (ADR-0014, E4): start a guest-vs-bot game from the catalog. `guestId` is the SPA's
  * stable per-browser identity (a UUID — same convention `POST /lobby/seeks` uses for `creator`). `timeControl` is
  * mandatory: a catalog game is never unlimited. `preferredColor` absent means a random seat (the default);
  * `Some(side)` seats the guest there and the bot on the other side.
  */
final case class PlayBot(
    guestId: String,
    team: String,
    name: String,
    timeControl: TimeControl,
    preferredColor: Option[Side] = None
) derives Codec.AsObject

/** Public, unauthenticated read of the human-facing bot catalog (ADR-0014, E2), the wake probe that precedes starting a
  * game against one (E3), and the start itself (E4): the bots that opened themselves to human games via
  * `POST /bot/open-to-humans`. Pure reads plus one write; mounted only when persistence is configured — without the
  * database there is no `bots` table to list, same spirit as [[LeaderboardRoutes]]. Wake and play-bot are bundled under
  * the same gate for one feature flag, even though neither strictly needs Postgres itself ([[BotStore]], [[Webhooks]],
  * and [[GameRegistry]] all have in-memory fallbacks) — without a listing to click from, neither has anything to be
  * called for.
  */
object CatalogRoutes:

  def apply(
      catalog: BotCatalogStore,
      bots: BotStore,
      webhooks: Option[Webhooks],
      registry: GameRegistry,
      wakeLimiter: AnonMintLimiter,
      playBotLimiter: AnonMintLimiter
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "lobby" / "bots" =>
        catalog.catalogBots.flatMap(listings => Ok(BotCatalog(listings.map(card))))

      // A visitor clicks a catalog card to start a game (ADR-0014): this wakes a scale-to-zero endpoint and reports
      // whether it answered, so the SPA knows whether to offer the game-config panel. 404 for a name outside the
      // catalog (not eligible to be woken here, whatever its webhook state); 200 alive:false covers "no webhook" and
      // "webhook didn't answer" alike — the caller only needs yes/no. The rate limit (an in-memory check) gates
      // BEFORE the catalog-membership read (a database query, in Pg mode) — this endpoint is fully unauthenticated,
      // so the cheapest defense must run first, rather than letting anonymous spam reach the database at all.
      case req @ POST -> Root / "lobby" / "bots" / team / name / "wake" =>
        wakeLimiter
          .attempt(BotRoutes.clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("wake rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              bots.openToHumansBots.flatMap { open =>
                if !open.contains(Principal.Bot(team, name)) then NotFound()
                else
                  webhooks match
                    case None          => ServiceUnavailable("webhooks are not enabled on this server")
                    case Some(service) => service.wake(Principal.Bot(team, name)).flatMap(alive => Ok(Wake(alive)))
              }

      case req @ POST -> Root / "lobby" / "play-bot" =>
        playBot(req, bots, registry, playBotLimiter)

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

  /** `POST /lobby/play-bot` (E4). Checks run cheapest-first — the ordering the E3 review established applies here too:
    * the per-IP rate limit before any registry/store read, the guest's own active-game count (in-memory) before catalog
    * membership (a database query in Pg mode), and only then is a game actually created. A guest with an unfinished
    * catalog game is refused a second one (409) — the ADR's "limit 1 concurrent game for now" policy; switching between
    * several is a later feature. No liveness re-check here: `wake` already confirmed the endpoint moments earlier, and
    * if it has since gone away, the game clock forfeits it exactly as it would mid-game — the same "reliability is the
    * clock" model `Webhooks` documents, not a reason to duplicate the probe.
    */
  private def playBot(
      req: Request[IO],
      bots: BotStore,
      registry: GameRegistry,
      limiter: AnonMintLimiter
  ): IO[Response[IO]] =
    limiter
      .attempt(BotRoutes.clientIp(req))
      .flatMap:
        case Left(retryAfter) =>
          TooManyRequests("play-bot rate limit exceeded — retry later")
            .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
        case Right(()) =>
          req
            .attemptAs[PlayBot]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                Principal.guest(body.guestId) match
                  case Left(err)    => BadRequest(s"guestId: $err")
                  case Right(guest) =>
                    if body.timeControl == TimeControl.Unlimited then
                      BadRequest("a catalog game must have a time control")
                    else startAgainstBot(guest, body, bots, registry)

  /** Guest identity and time control are already validated; from here: the 1-active-game gate, catalog membership, seat
    * assignment, and the actual `registry.create`.
    */
  private def startAgainstBot(
      guest: Principal.Guest,
      body: PlayBot,
      bots: BotStore,
      registry: GameRegistry
  ): IO[Response[IO]] =
    registry.gamesFor(guest).flatMap(_.traverse((_, room) => room.hasEnded)).map(_.exists(!_)).flatMap {
      case true  => Conflict("you already have an active game — finish it before starting another")
      case false =>
        val target: Principal.Bot = Principal.Bot(body.team, body.name)
        bots.openToHumansBots.flatMap { open =>
          if !open.contains(target) then NotFound()
          else
            seatAssignment(body.preferredColor, guest, target).flatMap { (white, black, guestSeat) =>
              registry
                .create(white, black, body.timeControl, requestedRated = false)
                .flatMap:
                  case Left(error)           => BadRequest(error)
                  case Right((gameId, room)) =>
                    room.joinTokens.get(guestSeat) match
                      case Some(token) => Created(SeekMatch(gameId.value, token, guestSeat))
                      case None        => InternalServerError("missing seat token")
            }
        }
    }

  /** `(white, black, guestSeat)`: the guest's chosen side if given, otherwise a coin flip — the ADR's "random by
    * default" policy.
    */
  private def seatAssignment(
      preferredColor: Option[Side],
      guest: Principal.Guest,
      bot: Principal.Bot
  ): IO[(Principal, Principal, Seat)] =
    preferredColor match
      case Some(Side.White) => IO.pure((guest, bot, Seat.White))
      case Some(Side.Black) => IO.pure((bot, guest, Seat.Black))
      case None             =>
        IO(SecureRandom().nextBoolean()).map(guestIsWhite =>
          if guestIsWhite then (guest, bot, Seat.White) else (bot, guest, Seat.Black)
        )
