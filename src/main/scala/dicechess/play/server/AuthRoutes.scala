package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import dicechess.play.store.UserStore
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

  /** Interim nickname source until #234 lands the dictionary generator: unique-enough hex under the store's own
    * collision retry, and already valid under #234's planned format rules.
    */
  private[server] def placeholderNickname: IO[String] =
    IO {
      val bytes = new Array[Byte](4)
      secureRandom.nextBytes(bytes)
      s"player-${bytes.map("%02x".format(_)).mkString}"
    }

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
              user     <- store.upsertOnLogin("google", identity.subject, identity.email, placeholderNickname)
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

      case POST -> Root / "auth" / "logout" =>
        Ok("Signed out").map(_.addCookie(session.expiredSessionCookie))
