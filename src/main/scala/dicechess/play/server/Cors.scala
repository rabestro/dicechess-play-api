package dicechess.play.server

import cats.effect.IO
import org.http4s.Method
import org.http4s.headers.Origin
import org.http4s.server.middleware.{CORS, CORSPolicy}
import org.typelevel.ci.{CIString, CIStringSyntax}

/** Cross-origin policy for the browser play-site.
  *
  * Historically this API was anonymous-first and used no cookies: join/Bearer tokens travel explicitly in the
  * URL/query/`Authorization` header. ADR-0017 (#233) adds the one deliberate exception — the account session cookie
  * (see `AuthSession`) — so an explicit origin allow-list now also enables `Access-Control-Allow-Credentials`, which
  * the SPA's credentialed fetches require.
  *
  * The empty/unset default still allows any origin, and deliberately WITHOUT credentials: a wildcard-plus-credentials
  * policy would let any page on the web read the API as whoever is signed in, which is exactly the leak CORS exists to
  * prevent (`*` precludes it at the spec level too). Allow-all therefore remains safe precisely because it stays
  * credential-less — a deployment that enables sign-in must also pin `PLAY_CORS_ORIGINS` (e.g.
  * `https://play.jc.id.lv,http://localhost:5173`).
  */
object Cors:

  private val EnvVar = "PLAY_CORS_ORIGINS"

  /** Build the policy from `PLAY_CORS_ORIGINS` (empty/unset → allow all, credential-less). */
  def fromEnv: IO[CORSPolicy] = IO(sys.env.getOrElse(EnvVar, "")).map(policy)

  /** The methods and headers a credentialed policy must enumerate. `*` is illegal alongside
    * `Access-Control-Allow-Credentials`, and http4s enforces that by answering a preflight with NO `Access-Control-*`
    * headers at all rather than emitting an invalid combination — see [[policy]].
    *
    * Kept to what the browser client actually sends: the SPA's only custom header is `content-type` (JSON bodies), and
    * the route set uses GET/POST/PATCH/DELETE. `Authorization` is deliberately absent — that is the bot API's Bearer
    * tier, which is server-to-server and never preflighted by a browser. Add to these lists when a new method or
    * request header appears on a browser path, or the preflight for it will be refused.
    */
  private val CredentialedMethods: Set[Method] =
    Set(Method.GET, Method.POST, Method.PATCH, Method.DELETE, Method.OPTIONS)

  private val CredentialedHeaders: Set[CIString] = Set(ci"content-type")

  /** Build a policy from a comma-separated origin allow-list. An empty/blank spec allows any origin without
    * credentials; a non-empty list restricts origins AND lets responses carry credentials (the session cookie).
    *
    * The two branches cannot share one `base`: allow-all may use `*` for methods and headers precisely because it is
    * credential-less, while the credentialed branch must enumerate them (see [[CredentialedMethods]]). Reaching for
    * `withAllowMethodsAll`/`withAllowHeadersAll` in the credentialed branch is what took production down — plain GETs
    * kept their headers, so the API looked healthy while every preflighted POST was blocked by the browser.
    */
  def policy(spec: String): CORSPolicy =
    val allowed = spec.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
    if allowed.isEmpty then CORS.policy.withAllowMethodsAll.withAllowHeadersAll.withAllowOriginAll
    else
      CORS.policy
        .withAllowOriginHeader(o => allowed.contains(render(o)))
        .withAllowMethodsIn(CredentialedMethods)
        .withAllowHeadersIn(CredentialedHeaders)
        .withAllowCredentials(true)

  /** Render an `Origin` to its header form (`scheme://host[:port]`) for matching against the allow-list. */
  private def render(origin: Origin): String = Origin.headerInstance.value(origin)
