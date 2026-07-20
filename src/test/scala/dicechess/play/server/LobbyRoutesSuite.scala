package dicechess.play.server

import cats.effect.IO
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

/** `/lobby/seeks` and `/lobby/seeks/{id}/accept`: the guest-id validation gate added for dicechess-play-api#14 (a
  * malformed or colon-containing creator/accepter id must never reach a game's `Principal.externalId`). The happy-path
  * lobby flow itself (seek → poll → accept → match) is exercised by LobbySuite (the domain class) and BotRoutesSuite
  * (human vs bot, end to end); this suite is scoped to the route-level validation only.
  */
class LobbyRoutesSuite extends munit.CatsEffectSuite:

  private val ValidCreator  = "55555555-5555-5555-5555-555555555555"
  private val ValidAccepter = "66666666-6666-6666-6666-666666666666"

  private def app: IO[HttpApp[IO]] =
    for
      registry <- GameRegistry.create()
      lobby    <- Lobby.create(registry)
    yield LobbyRoutes(lobby).orNotFound

  test("POST /lobby/seeks rejects a non-UUID creator with 400 (dicechess-play-api#14)"):
    app.flatMap: service =>
      for
        empty <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek("")))
          .map(_.status)
        // A value containing `:` would otherwise produce an ambiguous, colon-joined external_id.
        colonJoined <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek("guest:not-a-uuid")))
          .map(_.status)
        valid <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(ValidCreator)))
          .map(_.status)
      yield
        assertEquals(empty, Status.BadRequest)
        assertEquals(colonJoined, Status.BadRequest)
        assertEquals(valid, Status.Created)

  test("POST /lobby/seeks/{id}/accept rejects a non-UUID accepter with 400 (dicechess-play-api#14)"):
    app.flatMap: service =>
      for
        created <- service
          .run(Request[IO](Method.POST, uri"/lobby/seeks").withEntity(CreateSeek(ValidCreator)))
          .flatMap(_.as[CreatedSeek])
        garbage <- service
          .run(
            Request[IO](Method.POST, uri"/lobby/seeks" / created.seekId / "accept")
              .withEntity(AcceptSeek("not-a-uuid"))
          )
          .map(_.status)
        accepted <- service
          .run(
            Request[IO](Method.POST, uri"/lobby/seeks" / created.seekId / "accept")
              .withEntity(AcceptSeek(ValidAccepter))
          )
          .map(_.status)
      yield
        assertEquals(garbage, Status.BadRequest)
        assertEquals(accepted, Status.Created)
