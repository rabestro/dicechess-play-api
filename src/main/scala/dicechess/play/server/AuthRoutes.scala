package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import dicechess.play.store.{NicknameUpdate, UserStore}
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.{HttpRoutes, Response, Status, Uri}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** The owner's own view of their account. Deliberately minimal for now: email lives in `user_identities` and the store
  * exposes no accessor yet — the fuller profile shape (email, linked guests) arrives with #236, and per ADR-0017 email
  * would appear HERE only, never on any public wire type.
  */
final case class MeResponse(id: String, nickname: String) derives Codec.AsObject

/** `PATCH /auth/me`'s body (#234). One field on purpose — every future profile edit should arrive as its own reviewed
  * field, not ride an anything-goes map.
  */
final case class NicknameChange(nickname: String) derives Codec.AsObject

/** `DELETE /auth/me`'s body (#237): `confirm` must echo the account's own nickname. A typed statement of intent for the
  * one irreversible operation in this surface — see the route for why it is not about CSRF.
  */
final case class DeleteAccount(confirm: String) derives Codec.AsObject

/** Google sign-in (#233, ADR-0017), ported from the hardened dicechess-analytics PR #215 branch:
  *
  *   - `GET /auth/login` → 303 to Google, with a random `state` in a short-lived cookie (CSRF protection for the
  *     round-trip; compared constant-time on return).
  *   - `GET /auth/callback` → code exchange + local `id_token` verification (JWKS signature, issuer, audience — see
  *     `GoogleAuth`), upsert by `(google, sub)`, session cookie, 303 back to the SPA.
  *   - `GET /auth/me` / `POST /auth/logout` — the session's read and its end.
  *
  * Callback failures answer a generic 500 and log the detail server-side: the error chain names Google endpoints and
  * token internals that are diagnostic gold and phishing-page copy in equal measure.
  *
  * Everything here is mounted only when persistence AND the full auth config are present (`Main.scala`) — the
  * DB-only-seam idiom, so an undeployed feature is a 404, not a half-configured 500.
  */
object AuthRoutes:

  private val secureRandom = SecureRandom()

  private def randomState: IO[String] = IO {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private def redirect(target: String): Response[IO] =
    Response[IO](status = Status.SeeOther).putHeaders(Location(Uri.unsafeFromString(target)))

  def apply(
      session: AuthSession,
      google: GoogleIdentityProvider,
      store: UserStore,
      frontendUrl: String
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "auth" / "login" =>
        randomState.map { state =>
          redirect(google.authorizeUrl(state)).addCookie(AuthSession.stateCookie(state))
        }

      case req @ GET -> Root / "auth" / "callback" =>
        val code       = req.uri.query.params.get("code")
        val state      = req.uri.query.params.get("state")
        val savedState = req.cookies.find(_.name == AuthSession.StateCookieName).map(_.content)

        (code, state, savedState) match
          case (Some(c), Some(s), Some(saved)) if constantTimeEquals(s, saved) =>
            val flow = for
              identity <- google.identityFor(c)
              user     <- store.upsertOnLogin("google", identity.subject, identity.email, Nicknames.fresh)
              token    <- session.sign(user)
            yield redirect(frontendUrl)
              .addCookie(session.sessionCookie(token))
              .addCookie(AuthSession.expiredStateCookie)

            flow.handleErrorWith { err =>
              Console[IO].errorln(s"[play][auth] OAuth callback failed: $err") *>
                IO.pure(Response[IO](Status.InternalServerError).withEntity("Authentication failed"))
            }
          case (None, _, _) => BadRequest("Missing authorization code")
          case _            =>
            // Missing or mismatched state ⇒ possible CSRF; refuse and clear the stale cookie.
            BadRequest("Invalid OAuth state").map(_.addCookie(AuthSession.expiredStateCookie))

      case req @ GET -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(Response[IO](Status.Unauthorized).withEntity("Not signed in"))
          case Some(user) => Ok(MeResponse(id = user.id, nickname = user.nickname))
        }

      // Rename (#234). Format rules live in Nicknames.validate; the store enforces only uniqueness. `UserNotFound`
      // maps to 401, not 404 — it means the account vanished between the session check and the write (a racing
      // deletion), which is "you are no longer signed in" from the caller's point of view.
      case req @ PATCH -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(Response[IO](Status.Unauthorized).withEntity("Not signed in"))
          case Some(user) =>
            req
              .attemptAs[NicknameChange]
              .value
              .flatMap {
                case Left(failure) => BadRequest(failure.message)
                case Right(body)   =>
                  Nicknames.validate(body.nickname) match
                    case Left(reason) => BadRequest(reason)
                    case Right(name)  =>
                      store.updateNickname(user.id, name).flatMap {
                        case NicknameUpdate.Updated => Ok(MeResponse(id = user.id, nickname = name))
                        case NicknameUpdate.Taken   =>
                          IO.pure(Response[IO](Status.Conflict).withEntity("nickname already taken"))
                        case NicknameUpdate.UserNotFound =>
                          IO.pure(Response[IO](Status.Unauthorized).withEntity("Not signed in"))
                      }
              }
        }

      // Self-service deletion (#237). GDPR-lite: registration does not ship without a way out.
      //
      // The body must echo the account's own nickname. Not CSRF protection — `SameSite=Lax` plus a non-simple method
      // already means no cross-site page can send this — but a guard against a mis-wired client irreversibly deleting
      // the wrong account: the one thing here that cannot be undone deserves an explicit statement of intent.
      //
      // History is deliberately NOT rewritten. `user_identities` and `user_guest_links` cascade away (V14), so the
      // `user:<uuid>` left in `game_results`/`game_archive` stops resolving to anything — anonymisation without
      // touching immutable records or the analytics rows already delivered. An active game is left to the room's own
      // disconnect grace, exactly as if the player had closed the tab; there is no special case for it.
      case req @ DELETE -> Root / "auth" / "me" =>
        session.userFor(req).flatMap {
          case None       => IO.pure(Response[IO](Status.Unauthorized).withEntity("Not signed in"))
          case Some(user) =>
            req
              .attemptAs[DeleteAccount]
              .value
              .flatMap {
                case Left(failure) => BadRequest(failure.message)
                case Right(body)   =>
                  if !body.confirm.trim.equalsIgnoreCase(user.nickname) then
                    BadRequest("confirm must be your current nickname")
                  else
                    // The store's `false` (already gone — a racing delete) gets the same 204: the caller's goal is met
                    // either way, and a 404 would only invite a pointless retry.
                    store
                      .deleteUser(user.id)
                      .as(Response[IO](Status.NoContent).addCookie(session.expiredSessionCookie))
              }
        }

      case POST -> Root / "auth" / "logout" =>
        Ok("Signed out").map(_.addCookie(session.expiredSessionCookie))
