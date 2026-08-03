package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.store.{GuestLink, NicknameUpdate, UserAccount, UserStore}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.{Method, Request, RequestCookie, ResponseCookie, SameSite, Status, Uri}

import java.util.UUID

/** The sign-in surface (#233), exercised without Google or Postgres: a stub identity provider stands in for the OAuth
  * round-trip and an in-memory `UserStore` for persistence, so what's under test is exactly this server's own security
  * behaviour — the CSRF state check, the cookie attributes, and the rule that a session is never trusted without a
  * live, active user row behind it.
  */
class AuthRoutesSuite extends munit.CatsEffectSuite:

  private val FrontendUrl = "https://play.example"
  private val Secret      = "test-session-secret"

  private val stubGoogle: GoogleIdentityProvider = new GoogleIdentityProvider:
    def authorizeUrl(state: String): String           = s"https://google.example/auth?state=$state"
    def identityFor(code: String): IO[GoogleIdentity] =
      if code == "good-code" then IO.pure(GoogleIdentity("sub-1", Some("player@example.com")))
      else IO.raiseError(RuntimeException(s"exchange failed for '$code': secret detail"))

  /** Just enough store for these routes: upsert keyed by (provider, subject), reads by id. The unused members raise — a
    * test reaching them is a test gone wrong, not a fixture gap to paper over.
    */
  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
    def upsertOnLogin(
        provider: String,
        subject: String,
        email: Option[String],
        freshNickname: IO[String]
    ): IO[UserAccount] =
      (freshNickname, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(s"$provider:$subject") match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(s"$provider:$subject", user), user)
        }
      }
    def userById(id: String): IO[Option[UserAccount]]                        = ref.get.map(_.values.find(_.id == id))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] =
      ref.modify { users =>
        users.find(_._2.id == userId) match
          case None => (users, NicknameUpdate.UserNotFound)
          case _ if users.values.exists(u => u.id != userId && u.nickname.equalsIgnoreCase(nickname)) =>
            (users, NicknameUpdate.Taken)
          case Some((key, user)) => (users.updated(key, user.copy(nickname = nickname)), NicknameUpdate.Updated)
      }
    def linkGuest(userId: String, guestId: String): IO[GuestLink] = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                   = IO.raiseError(AssertionError("unused"))

  private def fixture: IO[(StubUsers, AuthSession, org.http4s.HttpApp[IO])] =
    Ref.of[IO, Map[String, UserAccount]](Map.empty).map { ref =>
      val store   = StubUsers(ref)
      val session = AuthSession(store, Secret)
      (store, session, AuthRoutes(session, stubGoogle, store, FrontendUrl).orNotFound)
    }

  private def cookieOf(cookies: List[ResponseCookie], name: String): ResponseCookie =
    cookies.find(_.name == name).getOrElse(fail(s"expected a '$name' cookie, got ${cookies.map(_.name)}"))

  private def patchNickname(token: Option[String], name: String): Request[IO] =
    val base = Request[IO](Method.PATCH, uri"/auth/me").withEntity(NicknameChange(name))
    token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))

  private def callbackRequest(code: String, state: String, cookieState: Option[String]): Request[IO] =
    val base = Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/auth/callback?code=$code&state=$state")
    )
    cookieState.fold(base)(s => base.addCookie(RequestCookie(AuthSession.StateCookieName, s)))

  test("GET /auth/login redirects to Google and plants a hardened state cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/login"))
    yield
      assertEquals(res.status, Status.SeeOther)
      val location = res.headers.get[Location].getOrElse(fail("no Location")).uri.renderString
      assert(location.startsWith("https://google.example/auth?state="), location)
      val state = cookieOf(res.cookies, AuthSession.StateCookieName)
      assert(state.httpOnly && state.secure, "state cookie must be HttpOnly + Secure")
      assertEquals(state.sameSite, Some(SameSite.Lax))
      assert(location.endsWith(state.content), "the redirect state and the cookie state must be the same value")

  test("the callback without a code answers 400"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/callback"))
    yield assertEquals(res.status, Status.BadRequest)

  test("the callback with a mismatched state answers 400 and clears the state cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("good-code", "state-a", Some("state-b")))
    yield
      assertEquals(res.status, Status.BadRequest)
      assertEquals(cookieOf(res.cookies, AuthSession.StateCookieName).maxAge, Some(0L))

  test("the callback with no state cookie at all answers 400"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("good-code", "state-a", None))
    yield assertEquals(res.status, Status.BadRequest)

  test("a successful callback creates the account, signs the session, and returns to the SPA"):
    for
      (store, _, app) <- fixture
      res             <- app.run(callbackRequest("good-code", "state-1", Some("state-1")))
      sessionCookie = cookieOf(res.cookies, AuthSession.SessionCookieName)
      me <- app.run(
        Request[IO](Method.GET, uri"/auth/me")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, sessionCookie.content))
      )
      meResp <- me.as[MeResponse]
      user   <- store.userById(meResp.id)
    yield
      assertEquals(res.status, Status.SeeOther)
      assertEquals(res.headers.get[Location].map(_.uri.renderString), Some(FrontendUrl))
      assert(sessionCookie.httpOnly && sessionCookie.secure, "session cookie must be HttpOnly + Secure")
      assertEquals(sessionCookie.sameSite, Some(SameSite.Lax))
      assertEquals(sessionCookie.path, Some("/"))
      assert(sessionCookie.domain.isEmpty, "host-only on purpose — no Domain attribute")
      assertEquals(cookieOf(res.cookies, AuthSession.StateCookieName).maxAge, Some(0L), "state cookie is cleared")
      assertEquals(me.status, Status.Ok)
      assertEquals(user.map(_.nickname), Some(meResp.nickname))
      assertEquals(
        Nicknames.validate(meResp.nickname),
        Right(meResp.nickname),
        "the auto-generated nickname must self-validate"
      )
      assert(user.exists(_.isActive))

  test("a failed Google exchange answers a generic 500 without the diagnostic detail"):
    for
      (_, _, app) <- fixture
      res         <- app.run(callbackRequest("bad-code", "state-1", Some("state-1")))
      body        <- res.bodyText.compile.string
    yield
      assertEquals(res.status, Status.InternalServerError)
      // The Circe entity codec (same import as every other route here) renders a String body as a JSON string.
      assertEquals(body, "\"Authentication failed\"")
      assert(!body.contains("secret detail"), "upstream error detail must never reach the client")

  test("GET /auth/me without a session answers 401"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.GET, uri"/auth/me"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("GET /auth/me with a token signed by the wrong secret answers 401"):
    for
      (store, _, app) <- fixture
      user            <- store.upsertOnLogin("google", "sub-forged", None, IO.pure("ForgedNick"))
      forged          <- AuthSession(store, "some-other-secret").sign(user)
      res             <- app.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, forged))
      )
    yield assertEquals(res.status, Status.Unauthorized)

  test("a valid session for a vanished or deactivated user answers 401 — the token is never enough"):
    for
      (store, session, _) <- fixture
      user                <- store.upsertOnLogin("google", "sub-gone", None, IO.pure("GoneNick"))
      token               <- session.sign(user)
      // A parallel store that never heard of the user stands in for deletion-after-sign-in.
      empty <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      goneApp = AuthRoutes(AuthSession(empty, Secret), stubGoogle, empty, FrontendUrl).orNotFound
      gone <- goneApp.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
      // And one where the user exists but was deactivated.
      inactive <- Ref
        .of[IO, Map[String, UserAccount]](Map("google:sub-gone" -> user.copy(isActive = false)))
        .map(StubUsers(_))
      inactiveApp = AuthRoutes(AuthSession(inactive, Secret), stubGoogle, inactive, FrontendUrl).orNotFound
      off <- inactiveApp.run(
        Request[IO](Method.GET, uri"/auth/me").addCookie(RequestCookie(AuthSession.SessionCookieName, token))
      )
    yield
      assertEquals(gone.status, Status.Unauthorized)
      assertEquals(off.status, Status.Unauthorized)

  test("PATCH /auth/me without a session answers 401"):
    for
      (_, _, app) <- fixture
      res         <- app.run(patchNickname(None, "FreshNick1"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("PATCH /auth/me validates the format before touching the store"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-patch-format", None, IO.pure("FormatNick"))
      token                 <- session.sign(user)
      short                 <- app.run(patchNickname(Some(token), "ab"))
      reserved              <- app.run(patchNickname(Some(token), "Admin"))
      digits                <- app.run(patchNickname(Some(token), "1stPlace"))
      garbled               <- app.run(
        Request[IO](Method.PATCH, uri"/auth/me")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, token))
          .withEntity("not json")
      )
      kept <- store.userById(user.id)
    yield
      assertEquals(short.status, Status.BadRequest)
      assertEquals(reserved.status, Status.BadRequest)
      assertEquals(digits.status, Status.BadRequest)
      assertEquals(garbled.status, Status.BadRequest)
      assertEquals(kept.map(_.nickname), Some("FormatNick"), "a rejected rename must change nothing")

  test("PATCH /auth/me renames on success and answers 409 when the name is taken"):
    for
      (store, session, app) <- fixture
      user                  <- store.upsertOnLogin("google", "sub-patch-a", None, IO.pure("PatchNickA"))
      _                     <- store.upsertOnLogin("google", "sub-patch-b", None, IO.pure("PatchNickB"))
      token                 <- session.sign(user)
      renamed               <- app.run(patchNickname(Some(token), "PatchNickA2"))
      body                  <- renamed.as[MeResponse]
      taken                 <- app.run(patchNickname(Some(token), "patchnickb"))
      stored                <- store.userById(user.id)
    yield
      assertEquals(renamed.status, Status.Ok)
      assertEquals(body.nickname, "PatchNickA2")
      assertEquals(taken.status, Status.Conflict, "'patchnickb' collides case-insensitively with PatchNickB")
      assertEquals(stored.map(_.nickname), Some("PatchNickA2"))

  test("PATCH /auth/me racing an account deletion answers 401, not 404"):
    for
      (sessionStore, session, _) <- fixture
      user                       <- sessionStore.upsertOnLogin("google", "sub-patch-race", None, IO.pure("RaceNick"))
      token                      <- session.sign(user)
      // The session store still knows the user (the cookie check passes); the routes' store no longer does —
      // the same two-store technique as the GET test, standing in for a deletion between check and write.
      empty <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      app = AuthRoutes(session, stubGoogle, empty, FrontendUrl).orNotFound
      res <- app.run(patchNickname(Some(token), "FreshNick1"))
    yield assertEquals(res.status, Status.Unauthorized)

  test("POST /auth/logout expires the session cookie"):
    for
      (_, _, app) <- fixture
      res         <- app.run(Request[IO](Method.POST, uri"/auth/logout"))
    yield
      assertEquals(res.status, Status.Ok)
      assertEquals(cookieOf(res.cookies, AuthSession.SessionCookieName).maxAge, Some(0L))
