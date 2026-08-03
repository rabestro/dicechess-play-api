package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.{GameId, Principal}
import dicechess.play.store.BotStore
import dicechess.play.store.{
  GameResultRow,
  GameResultsStore,
  GuestLink,
  NicknameUpdate,
  OpponentAggregateRow,
  OpponentFilter,
  PovResultFilter,
  UserAccount,
  UserRating,
  UserStore
}
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, RequestCookie, Status}

import java.time.Instant
import java.util.UUID

/** The signed-in player's own surface (#236): claiming guest identities, and the merged history that produces.
  *
  * The store seams are stubbed so this stays Docker-free; the SQL that actually unions over several identities is
  * covered against real Postgres in `PgGameStoreSuite`. What is under test here is the route contract — who may claim
  * what, and which identity set a read is scoped to.
  */
class MeRoutesSuite extends munit.CatsEffectSuite:

  private val Secret = "test-session-secret"
  private val GuestA = "11111111-1111-1111-1111-111111111111"
  private val GuestB = "22222222-2222-2222-2222-222222222222"
  private val at     = Instant.parse("2026-08-03T10:00:00Z")

  /** Accounts plus their claims, and one deliberate "already taken" guest id. */
  final private class StubUsers(
      ref: Ref[IO, Map[String, UserAccount]],
      links: Ref[IO, Map[String, String]] // guestId -> userId
  ) extends UserStore:
    def upsertOnLogin(
        provider: String,
        subject: String,
        email: Option[String],
        freshNickname: IO[String]
    ): IO[UserAccount] =
      (freshNickname, IO.realTimeInstant).flatMapN { (nickname, now) =>
        ref.modify { users =>
          users.get(subject) match
            case Some(existing) => (users, existing)
            case None           =>
              val user = UserAccount(UUID.randomUUID().toString, nickname, now, Some(now), isActive = true)
              (users.updated(subject, user), user)
        }
      }
    def userById(id: String): IO[Option[UserAccount]]         = ref.get.map(_.values.find(_.id == id))
    def byNickname(nickname: String): IO[Option[UserAccount]] =
      ref.get.map(_.values.find(_.nickname.equalsIgnoreCase(nickname)))
    def ratingOf(userId: String): IO[Option[UserRating]] =
      ref.get.map(_.values.find(_.id == userId).map(_ => UserRating.initial))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            =
      ref.get.flatMap { users =>
        if !users.values.exists(_.id == userId) then IO.pure(GuestLink.UserNotFound)
        else
          links.modify { current =>
            current.get(guestId) match
              case Some(owner) if owner != userId => (current, GuestLink.ClaimedByAnother)
              case _                              => (current.updated(guestId, userId), GuestLink.Linked)
          }
      }
    def guestsOf(userId: String): IO[List[String]] =
      links.get.map(_.collect { case (guestId, owner) if owner == userId => guestId }.toList.sorted)
    def deleteUser(userId: String): IO[Boolean] = IO.raiseError(AssertionError("unused"))

  /** Records the identity set each read was scoped to — that scoping IS the feature under test. */
  final private class SpyResults(seen: Ref[IO, List[List[String]]], rows: List[GameResultRow]) extends GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] = IO.pure(Nil)
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]]               = IO.pure(Nil)
    def playerGamesPage(
        externalIds: List[String],
        before: Option[Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] =
      seen.update(_ :+ externalIds).as(GameResultsStore.Page(rows, hasMore = false))
    def opponentsFor(externalIds: List[String]): IO[List[OpponentAggregateRow]] =
      seen.update(_ :+ externalIds).as(Nil)

  private def resultRow(white: String, black: String): GameResultRow =
    GameResultRow(
      GameId(UUID.randomUUID().toString),
      white,
      black,
      Some(1),
      "resign",
      false,
      "300+3",
      "seed",
      None,
      false,
      at
    )

  private def fixture(
      rows: List[GameResultRow] = Nil
  ): IO[(HttpApp[IO], UserAccount, String, Ref[IO, List[List[String]]], StubUsers)] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      links    <- Ref.of[IO, Map[String, String]](Map.empty)
      seen     <- Ref.of[IO, List[List[String]]](Nil)
      users   = StubUsers(accounts, links)
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-me", None, IO.pure("MeNick"))
      token <- session.sign(user)
    yield (MeRoutes(session, users, SpyResults(seen, rows)).orNotFound, user, token, seen, users)

  private def signedIn(method: Method, path: String, token: String): Request[IO] =
    Request[IO](method, org.http4s.Uri.unsafeFromString(path))
      .addCookie(RequestCookie(AuthSession.SessionCookieName, token))

  /** A real in-memory `BotAuth` — the ownership routes are about the interaction of two credentials, so stubbing the
    * bot half away would test nothing.
    */
  private def withBots: IO[(HttpApp[IO], UserAccount, String, BotAuth, StubUsers)] =
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      links    <- Ref.of[IO, Map[String, String]](Map.empty)
      seen     <- Ref.of[IO, List[List[String]]](Nil)
      store    <- BotStore.inMemory
      auth     <- BotAuth.fromSpec("", store)
      users   = StubUsers(accounts, links)
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-owner", None, IO.pure("OwnerNick"))
      token <- session.sign(user)
    yield (MeRoutes(session, users, SpyResults(seen, Nil), bots = Some(auth)).orNotFound, user, token, auth, users)

  private def claim(token: Option[String], botToken: Option[String]): Request[IO] =
    val base        = Request[IO](Method.POST, uri"/me/bots/claim")
    val withSession = token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t)))
    botToken.fold(withSession)(b =>
      withSession.putHeaders(
        org.http4s.headers.Authorization(org.http4s.Credentials.Token(org.http4s.AuthScheme.Bearer, b))
      )
    )

  test("claiming a bot requires BOTH a session and the bot's own token"):
    for
      (app, _, session, auth, _) <- withBots
      registered                 <- auth.register("acme", "alice")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      noSession <- app.run(claim(None, Some(botToken)))
      noBot     <- app.run(claim(Some(session), None))
      both      <- app.run(claim(Some(session), Some(botToken)))
      mine      <- both.as[MyBots]
    yield
      assertEquals(noSession.status, Status.Unauthorized, "a bot token alone has nobody to claim it for")
      assertEquals(noBot.status, Status.Unauthorized, "a session alone would let anyone claim any bot")
      assertEquals(both.status, Status.Ok)
      assertEquals(mine.bots.map(b => (b.team, b.name)), List(("acme", "alice")))

  test("re-claiming your own bot is idempotent; another account's bot is a 409, never a takeover"):
    for
      (app, _, session, auth, users) <- withBots
      registered                     <- auth.register("acme", "mine")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      _     <- app.run(claim(Some(session), Some(botToken)))
      again <- app.run(claim(Some(session), Some(botToken)))
      // A second account, holding the very same bot token: possession is not enough to steal attribution.
      rival      <- users.upsertOnLogin("google", "sub-rival-owner", None, IO.pure("RivalOwner"))
      rivalToken <- AuthSession(users, Secret).sign(rival)
      stolen     <- app.run(claim(Some(rivalToken), Some(botToken)))
    yield
      assertEquals(again.status, Status.Ok, "a retry must not be an error")
      assertEquals(stolen.status, Status.Conflict)

  test("an anonymous bot cannot be owned — it has no row"):
    for
      (app, _, session, auth, _) <- withBots
      anon                       <- auth.mintAnon(Some("scratch"))
      res                        <- app.run(claim(Some(session), Some(anon._1)))
    yield assertEquals(res.status, Status.NotFound, "only a registered identity can be owned")

  test("releasing makes the bot claimable by another account, and only its owner may release it"):
    for
      (app, _, session, auth, users) <- withBots
      registered                     <- auth.register("acme", "handover")
      botToken = registered.toOption.map(_._1).getOrElse(fail("registration failed"))
      _          <- app.run(claim(Some(session), Some(botToken)))
      rival      <- users.upsertOnLogin("google", "sub-next-owner", None, IO.pure("NextOwner"))
      rivalToken <- AuthSession(users, Secret).sign(rival)
      // Not yours: one answer for "not yours" and "not there", so this cannot probe who owns what.
      notYours <- app.run(
        Request[IO](Method.DELETE, uri"/me/bots/acme/handover")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, rivalToken))
      )
      released <- app.run(
        Request[IO](Method.DELETE, uri"/me/bots/acme/handover")
          .addCookie(RequestCookie(AuthSession.SessionCookieName, session))
      )
      empty     <- released.as[MyBots]
      reclaimed <- app.run(claim(Some(rivalToken), Some(botToken)))
    yield
      assertEquals(notYours.status, Status.NotFound)
      assertEquals(released.status, Status.Ok)
      assertEquals(empty.bots, Nil, "the released bot leaves the owner's list")
      assertEquals(reclaimed.status, Status.Ok, "release is the explicit half of a transfer")

  test("GET /me/bots lists only the caller's bots"):
    for
      (app, _, session, auth, users) <- withBots
      a                              <- auth.register("acme", "first")
      b                              <- auth.register("acme", "second")
      _                              <- app.run(claim(Some(session), a.toOption.map(_._1)))
      rival                          <- users.upsertOnLogin("google", "sub-other-owner", None, IO.pure("OtherOwner"))
      rivalToken                     <- AuthSession(users, Secret).sign(rival)
      _                              <- app.run(claim(Some(rivalToken), b.toOption.map(_._1)))
      mine                           <- app
        .run(Request[IO](Method.GET, uri"/me/bots").addCookie(RequestCookie(AuthSession.SessionCookieName, session)))
        .flatMap(_.as[MyBots])
      other <- app
        .run(Request[IO](Method.GET, uri"/me/bots").addCookie(RequestCookie(AuthSession.SessionCookieName, rivalToken)))
        .flatMap(_.as[MyBots])
    yield
      assertEquals(mine.bots.map(_.name), List("first"))
      assertEquals(other.bots.map(_.name), List("second"))

  test("every /me route answers 401 without a session"):
    for
      (app, _, _, _, _) <- fixture()
      claim             <- app.run(Request[IO](Method.POST, uri"/auth/me/guests").withEntity(ClaimGuest(GuestA)))
      list              <- app.run(Request[IO](Method.GET, uri"/auth/me/guests"))
      games             <- app.run(Request[IO](Method.GET, uri"/me/games"))
      opps              <- app.run(Request[IO](Method.GET, uri"/me/opponents"))
    yield
      assertEquals(claim.status, Status.Unauthorized)
      assertEquals(list.status, Status.Unauthorized)
      assertEquals(games.status, Status.Unauthorized)
      assertEquals(opps.status, Status.Unauthorized)

  test("claiming a guest id is idempotent for its owner and returns the full claim set"):
    for
      (app, _, token, _, _) <- fixture()
      first                 <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      body                  <- first.as[ClaimedGuests]
      again                 <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      second                <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestB)))
      both                  <- second.as[ClaimedGuests]
      listed                <- app.run(signedIn(Method.GET, "/auth/me/guests", token)).flatMap(_.as[ClaimedGuests])
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(body.guests, List(GuestA))
      assertEquals(again.status, Status.Ok, "re-claiming your own id is idempotent, not a conflict")
      assertEquals(both.guests, List(GuestA, GuestB))
      assertEquals(listed.guests, List(GuestA, GuestB))

  test("a guest id already claimed by another account answers 409"):
    for
      (app, _, token, _, users) <- fixture()
      rival                     <- users.upsertOnLogin("google", "sub-rival", None, IO.pure("RivalNick"))
      _                         <- users.linkGuest(rival.id, GuestA)
      taken <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
    yield assertEquals(taken.status, Status.Conflict)

  test("a malformed guest id is rejected before it can reach the store's uuid cast"):
    for
      (app, _, token, _, _) <- fixture()
      bad      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest("not-a-uuid")))
      body     <- bad.bodyText.compile.string
      prefixed <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(s"guest:$GuestA")))
      listed   <- app.run(signedIn(Method.GET, "/auth/me/guests", token)).flatMap(_.as[ClaimedGuests])
    yield
      assertEquals(bad.status, Status.BadRequest)
      assert(body.contains("guestId"), body)
      assertEquals(prefixed.status, Status.BadRequest, "the bare uuid is the contract — never the prefixed form")
      assertEquals(listed.guests, Nil, "a rejected claim stores nothing")

  test("/me/games and /me/opponents are scoped to the account plus every claimed guest id"):
    for
      (app, user, token, seen, _) <- fixture()
      _      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestA)))
      _      <- app.run(signedIn(Method.POST, "/auth/me/guests", token).withEntity(ClaimGuest(GuestB)))
      _      <- app.run(signedIn(Method.GET, "/me/games", token))
      _      <- app.run(signedIn(Method.GET, "/me/opponents", token))
      scopes <- seen.get
    yield
      val expected = Set(
        Principal.User(user.id).externalId,
        Principal.Guest(GuestA).externalId,
        Principal.Guest(GuestB).externalId
      )
      assertEquals(scopes.length, 2)
      scopes.foreach(scope => assertEquals(scope.toSet, expected))

  test("/me/games reframes each row from whichever of my identities held White"):
    val bot = Principal.Bot("acme", "alice").externalId
    // Built inline rather than via `fixture`: this case needs rows that involve BOTH identities, on opposite sides.
    for
      accounts <- Ref.of[IO, Map[String, UserAccount]](Map.empty)
      links    <- Ref.of[IO, Map[String, String]](Map.empty)
      seen     <- Ref.of[IO, List[List[String]]](Nil)
      users   = StubUsers(accounts, links)
      session = AuthSession(users, Secret)
      me    <- users.upsertOnLogin("google", "sub-pov", None, IO.pure("PovNick"))
      myTok <- session.sign(me)
      _     <- users.linkGuest(me.id, GuestA)
      rows = List(
        resultRow(Principal.User(me.id).externalId, bot),  // I was White as the account
        resultRow(bot, Principal.Guest(GuestA).externalId) // I was Black as my claimed guest
      )
      app2 = MeRoutes(session, users, SpyResults(seen, rows)).orNotFound
      games <- app2.run(signedIn(Method.GET, "/me/games", myTok)).flatMap(_.as[PlayerGames])
    yield
      assertEquals(games.games.map(_.seat.toString), List("White", "Black"))
      assertEquals(games.games.map(_.result), List("win", "loss"), "the POV follows whichever identity was mine")
      assert(games.games.forall(_.opponent.name.contains("acme alice")), games.games.map(_.opponent).toString)
