package dicechess.play.server

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{GameResultsStore, GuestLink, OwnerClaim, UserAccount, UserStore}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, ParseFailure, QueryParamDecoder, Request, Response, Status}

import java.time.Instant

/** The body of a guest claim (#236): the bare uuid the SPA keeps in `localStorage`. */
final case class ClaimGuest(guestId: String) derives Codec.AsObject

/** The guest identities this account has claimed, oldest first. */
final case class ClaimedGuests(guests: List[String]) derives Codec.AsObject

/** One of the account's own bots (#253) — identity, rating, and the flags its owner can act on. */
final case class MyBot(
    team: String,
    name: String,
    rating: Double,
    rd: Double,
    onLadder: Boolean,
    openToHumans: Boolean,
    ratedForHumans: Boolean
) derives Codec.AsObject

final case class MyBots(bots: List[MyBot]) derives Codec.AsObject

/** The signed-in player's own history (#236, ADR-0017).
  *
  * Claiming is how anonymous play survives signing up: `game_results` and `game_archive` keep the `guest:` external ids
  * they were written with — nothing is ever rewritten — and these reads simply union over the account plus every guest
  * id it has claimed. That keeps immutable records immutable and the already-delivered analytics rows untouched, and it
  * is reversible in the only direction that matters (a link is a row).
  *
  * Proof of ownership is possession of the id, the same trust model the SPA's restore code already relies on: a guest
  * uuid is unguessable and is shown only to its own browser. The claim is first-writer-wins and terminal, because
  * `user_guest_links.guest_id` is a primary key — one guest identity belongs to at most one account, ever.
  *
  * Privacy: the claim set is visible only to its owner. The public `GET /players/{guestId}/…` reads deliberately do NOT
  * learn about linkage — a guest id must never resolve to a nickname there, or signing up would retroactively
  * deanonymise every anonymous game that id ever played.
  */
object MeRoutes:

  private val DefaultLimit = GameResultsStore.DefaultRecentLimit
  private val MaxLimit     = 200

  // Validating (not plain) for the same reason PlayerRoutes documents: a plain matcher's decode failure fails the whole
  // route match and 404s instead of reporting the bad request.
  private object LimitParam extends OptionalValidatingQueryParamDecoderMatcher[Int]("limit")

  private given QueryParamDecoder[Instant] = QueryParamDecoder[String].emap: s =>
    try Right(Instant.parse(s))
    catch case e: java.time.format.DateTimeParseException => Left(ParseFailure(s"invalid instant '$s'", e.getMessage))
  private object BeforeParam extends OptionalValidatingQueryParamDecoderMatcher[Instant]("before")

  private object VsParam     extends OptionalQueryParamDecoderMatcher[String]("vs")
  private object ResultParam extends OptionalQueryParamDecoderMatcher[String]("result")

  private val notSignedIn: Response[IO] = Response[IO](Status.Unauthorized).withEntity("Not signed in")

  def apply(
      session: AuthSession,
      users: UserStore,
      results: GameResultsStore,
      bots: Option[BotAuth] = None
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "auth" / "me" / "guests" =>
        withUser(session, req): user =>
          req
            .attemptAs[ClaimGuest]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                // The same validation gate every guest id passes at the identity-issuance boundary — the store casts
                // to uuid without re-validating (see UserStore's id contract).
                Principal.guest(body.guestId) match
                  case Left(error)  => BadRequest(s"guestId: $error")
                  case Right(guest) =>
                    users.linkGuest(user.id, guest.id).flatMap {
                      case GuestLink.Linked           => guestsOf(users, user)
                      case GuestLink.ClaimedByAnother =>
                        IO.pure(Response[IO](Status.Conflict).withEntity("that guest id belongs to another account"))
                      // The account vanished between the session check and the write — "no longer signed in", as
                      // everywhere else in this surface.
                      case GuestLink.UserNotFound => IO.pure(notSignedIn)
                    }

      case req @ GET -> Root / "auth" / "me" / "guests" =>
        withUser(session, req)(guestsOf(users, _))

      case req @ GET -> Root / "me" / "games" :? LimitParam(limit) +& BeforeParam(before) +& VsParam(vs) +&
          ResultParam(result) =>
        withUser(session, req): user =>
          val validated = for
            parsed    <- parseValidated(limit, "limit")
            beforeAt  <- parseValidated(before, "before")
            vsFilter  <- PlayerRoutes.parseVs(vs)
            povResult <- PlayerRoutes.parseResult(result)
          yield (parsed, beforeAt, vsFilter, povResult)
          validated match
            case Left(error)                                    => BadRequest(error)
            case Right((parsed, beforeAt, vsFilter, povResult)) =>
              val bounded = parsed.fold(DefaultLimit)(requested => math.max(1, math.min(requested, MaxLimit)))
              identitiesOf(users, user).flatMap { ids =>
                results
                  .playerGamesPage(ids.toList, beforeAt, vsFilter, povResult, bounded)
                  .flatMap { page =>
                    Ok(PlayerGames(page.games.map(PlayerRoutes.playerGame(ids, _)), page.hasMore))
                  }
              }

      // Claiming a bot needs BOTH credentials: the session says who is claiming, the bot's Bearer token proves
      // control of it (#253). Neither alone is enough — a session without the token would let anyone claim any bot,
      // and the token without a session has nobody to claim it for.
      case req @ POST -> Root / "me" / "bots" / "claim" =>
        withUser(session, req): user =>
          bots match
            case None       => IO.pure(Response[IO](Status.NotFound))
            case Some(auth) =>
              BotRoutes.asBot(auth, req).flatMap {
                case None      => IO.pure(Response[IO](Status.Unauthorized).withEntity("bot token required"))
                case Some(bot) =>
                  auth
                    .claimOwner(bot, Principal.User(user.id).externalId)
                    .flatMap {
                      case OwnerClaim.Claimed          => myBots(auth, user)
                      case OwnerClaim.ClaimedByAnother =>
                        IO.pure(Response[IO](Status.Conflict).withEntity("that bot already belongs to another account"))
                      // A static-roster or anonymous caller: authenticated, but with no row to own.
                      case OwnerClaim.NotRegistered =>
                        IO.pure(Response[IO](Status.NotFound).withEntity("only a registered bot can be owned"))
                    }
              }

      case req @ GET -> Root / "me" / "bots" =>
        withUser(session, req): user =>
          bots.fold(IO.pure(Response[IO](Status.NotFound)))(myBots(_, user))

      // Releasing is the explicit half of a transfer: the bot becomes claimable again, so handing it over does not
      // depend on who calls claim last. Session-only — the owner does not need the bot's token to let it go.
      case req @ DELETE -> Root / "me" / "bots" / team / name =>
        withUser(session, req): user =>
          bots match
            case None       => IO.pure(Response[IO](Status.NotFound))
            case Some(auth) =>
              auth.releaseOwner(team, name, Principal.User(user.id).externalId).flatMap {
                case true => myBots(auth, user)
                // Not yours (or not there at all) — one answer for both, so this cannot be used to probe which bots
                // exist or who owns them.
                case false => IO.pure(Response[IO](Status.NotFound).withEntity("you do not own that bot"))
              }

      case req @ GET -> Root / "me" / "opponents" =>
        withUser(session, req): user =>
          identitiesOf(users, user).flatMap { ids =>
            results.opponentsFor(ids.toList).flatMap(rows => Ok(PlayerOpponents(rows.map(playerOpponent))))
          }

  private def myBots(auth: BotAuth, user: UserAccount): IO[Response[IO]] =
    auth.botsOwnedBy(Principal.User(user.id).externalId).flatMap { owned =>
      Ok(
        MyBots(
          owned.map(bot =>
            MyBot(bot.team, bot.name, bot.rating, bot.rd, bot.onLadder, bot.openToHumans, bot.ratedForHumans)
          )
        )
      )
    }

  /** Every external id that IS this account: its own, plus each claimed guest id (#236). */
  private def identitiesOf(users: UserStore, user: UserAccount): IO[Set[String]] =
    users.guestsOf(user.id).map { guests =>
      guests.map(id => Principal.Guest(id).externalId).toSet + Principal.User(user.id).externalId
    }

  private def guestsOf(users: UserStore, user: UserAccount): IO[Response[IO]] =
    users.guestsOf(user.id).flatMap(guests => Ok(ClaimedGuests(guests)))

  private def withUser(session: AuthSession, req: Request[IO])(f: UserAccount => IO[Response[IO]]): IO[Response[IO]] =
    session.userFor(req).flatMap(_.fold(IO.pure(notSignedIn))(f))

  private def parseValidated[A](param: Option[ValidatedNel[ParseFailure, A]], name: String): Either[String, Option[A]] =
    param match
      case None             => Right(None)
      case Some(Valid(v))   => Right(Some(v))
      case Some(Invalid(e)) => Left(s"$name: ${e.head.sanitized}")
