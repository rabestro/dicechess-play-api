package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.core.*
import dicechess.play.store.{GuestLink, NicknameUpdate, UserAccount, UserStore}
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.{Header, Headers, HttpApp, Method, Request, RequestCookie, Status, Uri}
import org.typelevel.ci.*

import java.util.UUID
import scala.concurrent.duration.*

/** Identity on the game-start paths (#235, ADR-0017): **the session wins, the body is only ever a guest fallback.** The
  * property that matters most here is negative — a signed-in caller cannot be made to act as someone else by anything
  * in the request body, and no body field can ever name a `user:` principal.
  */
class AuthenticatedPlaySuite extends munit.CatsEffectSuite:

  private val Secret  = "test-session-secret"
  private val GuestId = "77777777-7777-7777-7777-777777777777"
  private val OtherId = "88888888-8888-8888-8888-888888888888"

  final private class StubUsers(ref: Ref[IO, Map[String, UserAccount]]) extends UserStore:
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
    def userById(id: String): IO[Option[UserAccount]]                        = ref.get.map(_.values.find(_.id == id))
    def updateNickname(userId: String, nickname: String): IO[NicknameUpdate] = IO.raiseError(AssertionError("unused"))
    def linkGuest(userId: String, guestId: String): IO[GuestLink]            = IO.raiseError(AssertionError("unused"))
    def guestsOf(userId: String): IO[List[String]]                           = IO.raiseError(AssertionError("unused"))
    def deleteUser(userId: String): IO[Boolean]                              = IO.raiseError(AssertionError("unused"))

  /** A registry plus routes wired with a real `AuthSession`, and one signed-in account's cookie. */
  private def fixture: IO[(GameRegistry, HttpApp[IO], HttpApp[IO], UserAccount, String)] =
    for
      registry <- GameRegistry.create()
      lobby    <- Lobby.create(registry)
      users    <- Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_))
      session = AuthSession(users, Secret)
      user  <- users.upsertOnLogin("google", "sub-play", None, IO.pure("PlayNick"))
      token <- session.sign(user)
      // PlayRoutes needs a WebSocketBuilder2, which only exists inside a server; the HTTP half of the route set is
      // exercised through a plain HttpApp here and the WS seat-resolve path in PlayRoutesSuite's real server.
      lobbyApp = LobbyRoutes(lobby, Some(session)).orNotFound
      seekApp  = LobbyRoutes(lobby, None).orNotFound
    yield (registry, lobbyApp, seekApp, user, token)

  private def createSeek(app: HttpApp[IO], token: Option[String], creator: Option[String]) =
    val base = Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(creator))
    app.run(token.fold(base)(t => base.addCookie(RequestCookie(AuthSession.SessionCookieName, t))))

  /** `Seek` is anonymized on the wire by design (`core/Seek.scala`: humans never leak an id), so the creator's actual
    * principal is asserted where it becomes observable — the seating of the game the accept produces.
    */
  private def creatorOf(registry: GameRegistry, app: HttpApp[IO], seekId: String, accepter: String): IO[Principal] =
    app
      .run(Request[IO](Method.POST, uri"/lobby/seeks" / seekId / "accept").withEntity(AcceptSeek(Some(accepter))))
      .flatMap(_.as[SeekMatch])
      .flatMap { m =>
        registry.get(GameId(m.gameId)).flatMap {
          case None       => IO.raiseError(AssertionError(s"game ${m.gameId} not found"))
          case Some(room) =>
            room.seating.map { seats =>
              seats.values.find(_ != Principal.Guest(accepter)).getOrElse(fail("no creator seat"))
            }
        }
      }

  test("a signed-in caller seeks as their account, and a body guest id cannot override it"):
    for
      (registry, app, _, user, token) <- fixture
      // The body names a completely different identity; the session must win.
      res     <- createSeek(app, Some(token), Some(GuestId))
      seek    <- res.as[CreatedSeek]
      creator <- creatorOf(registry, app, seek.seekId, OtherId)
    yield
      assertEquals(res.status, Status.Created)
      assertEquals(
        creator,
        Principal.User(user.id),
        "the seat must belong to the session's account, not the body's guest id"
      )

  test("without a session the body guest id is still honoured, and its absence is a 400"):
    for
      (registry, app, _, _, _) <- fixture
      anonymous                <- createSeek(app, None, Some(GuestId))
      seek                     <- anonymous.as[CreatedSeek]
      missing                  <- createSeek(app, None, None)
      creator                  <- creatorOf(registry, app, seek.seekId, OtherId)
    yield
      assertEquals(anonymous.status, Status.Created)
      assertEquals(missing.status, Status.BadRequest, "no session and no creator id is unusable")
      assertEquals(creator, Principal.Guest(GuestId))

  test("a deployment without sign-in configured behaves exactly as before"):
    for
      (registry, _, noAuth, _, token) <- fixture
      // The cookie is valid, but this route set was built without a session: it must fall back to the body.
      res     <- createSeek(noAuth, Some(token), Some(GuestId))
      seek    <- res.as[CreatedSeek]
      creator <- creatorOf(registry, noAuth, seek.seekId, OtherId)
    yield
      assertEquals(res.status, Status.Created)
      assertEquals(creator, Principal.Guest(GuestId))

  // The rated-eligibility matrix for `Principal.User` (user-vs-user, user-vs-registered-bot, and the guest/anon-bot
  // exclusions) is already asserted exhaustively in `GameRegistrySuite`'s isRated section — the policy lit up as-is
  // when accounts arrived, which is exactly what #235 predicted, so it is verified there rather than duplicated here.

  // ── Tokenless WebSocket seat resolve (the lost-?seat= fix) ───────────────────

  /** An Ember server whose `PlayRoutes` carry a real session, plus one signed-in account's cookie. A live server is
    * required because seat resolution happens at the WebSocket upgrade, which needs a `WebSocketBuilder2`.
    */
  private def wsServer: Resource[IO, (Int, GameRegistry, UserAccount, String)] =
    for
      registry <- Resource.eval(GameRegistry.create(disconnectGrace = 500.millis))
      users    <- Resource.eval(Ref.of[IO, Map[String, UserAccount]](Map.empty).map(StubUsers(_)))
      session = AuthSession(users, Secret)
      user  <- Resource.eval(users.upsertOnLogin("google", "sub-ws", None, IO.pure("WsNick")))
      token <- Resource.eval(session.sign(user))
      srv   <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(1.second)
        .withHttpWebSocketApp(wsb => PlayRoutes(registry, wsb, Some(session)).orNotFound)
        .build
    yield (srv.address.getPort, registry, user, token)

  private def sessionWs(uri: Uri, token: String): WSRequest =
    WSRequest(uri).withHeaders(Headers(Header.Raw(ci"Cookie", s"${AuthSession.SessionCookieName}=$token")))

  private def statusOf(registry: GameRegistry, id: String): IO[GameStatus] =
    registry.get(GameId(id)).flatMap {
      case None       => IO.raiseError(AssertionError(s"game $id vanished"))
      case Some(room) => room.snapshot.map(_.status)
    }

  test("a signed-in player reconnects to their seat with no join token — the lost-URL fix"):
    (wsServer, JdkWSClient.simple[IO]).tupled.use { case ((port, registry, user, token), ws) =>
      val wsBase = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      for
        created <- registry.create(Principal.User(user.id), Principal.Guest(GuestId))
        gameId  <- IO.fromEither(created.left.map(e => AssertionError(e))).map(_._1.value)
        // No ?seat= token at all — only the session cookie. A spectator could not resign.
        _ <- ws
          .connectHighLevel(sessionWs(wsBase / "games" / gameId / "ws", token))
          .use(_.send(WSFrame.Text(GameCommand.Resign.asJson.noSpaces)))
        ended <- statusOf(registry, gameId)
          .iterateUntil(_ != GameStatus.Active)
          .timeoutTo(10.seconds, IO.raiseError(AssertionError("the session never resolved to a playable seat")))
      yield assert(ended != GameStatus.Active, "the resign was accepted, so the seat was resolved from the session")
    }

  test("a signed-in player holding BOTH seats stays a spectator without a token"):
    (wsServer, JdkWSClient.simple[IO]).tupled.use { case ((port, registry, user, token), ws) =>
      val wsBase = Uri.unsafeFromString(s"ws://127.0.0.1:$port")
      val me     = Principal.User(user.id)
      for
        // The friend-by-link shape: the creator owns both seats until the share link is used, so the session cannot
        // name one seat — the join token stays the only way in.
        created <- registry.create(me, me)
        gameId  <- IO.fromEither(created.left.map(e => AssertionError(e))).map(_._1.value)
        _       <- ws
          .connectHighLevel(sessionWs(wsBase / "games" / gameId / "ws", token))
          .use(_.send(WSFrame.Text(GameCommand.Resign.asJson.noSpaces)))
        // Poll durable state rather than sleeping on a stream: the resign must have been ignored outright.
        status <- statusOf(registry, gameId)
          .iterateUntil(_ != GameStatus.Active)
          .timeoutTo(2.seconds, statusOf(registry, gameId))
      yield assertEquals(status, GameStatus.Active, "an ambiguous session must not be able to resign either seat")
    }
