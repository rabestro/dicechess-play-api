package dicechess.play.server

import cats.effect.IO
import org.http4s.implicits.*
import org.http4s.{Header, Headers, Method, Request, Status}
import org.typelevel.ci.*

class CorsSuite extends munit.CatsEffectSuite:

  private def app(spec: String) = Cors.policy(spec).apply(HealthRoutes("1.2.3").orNotFound)

  /** The browser-supplied `Origin` request header, set raw so the middleware parses it as a real request would. */
  private def origin(value: String): Header.Raw = Header.Raw(ci"Origin", value)

  private def allowOrigin(headers: Headers): Option[String] =
    headers.get(ci"Access-Control-Allow-Origin").map(_.head.value)

  test("a normal GET from any origin gets Access-Control-Allow-Origin: * by default"):
    app("")
      .run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      .map(resp => assertEquals(allowOrigin(resp.headers), Some("*")))

  test("an OPTIONS preflight is answered with the CORS headers"):
    val preflight = Request[IO](Method.OPTIONS, uri"/games")
      .putHeaders(origin("https://play.jc.id.lv"), Header.Raw(ci"Access-Control-Request-Method", "POST"))
    app("")
      .run(preflight)
      .map: resp =>
        assert(
          resp.status == Status.Ok || resp.status == Status.NoContent,
          s"unexpected preflight status ${resp.status}"
        )
        assertEquals(allowOrigin(resp.headers), Some("*"))
        assert(
          resp.headers.get(ci"Access-Control-Allow-Methods").isDefined,
          "preflight is missing Access-Control-Allow-Methods"
        )

  test("an allow-list echoes a configured origin and omits the header for others"):
    val restricted = app("https://play.jc.id.lv,http://localhost:5173")
    for
      allowed <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://play.jc.id.lv")))
      denied  <- restricted.run(Request[IO](Method.GET, uri"/health").putHeaders(origin("https://evil.example")))
    yield
      assertEquals(allowOrigin(allowed.headers), Some("https://play.jc.id.lv"))
      assertEquals(allowOrigin(denied.headers), None)
