package dicechess.play.server

import cats.effect.IO
import org.http4s.headers.Origin
import org.http4s.server.middleware.{CORS, CORSPolicy}

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

  /** Build a policy from a comma-separated origin allow-list. An empty/blank spec allows any origin without
    * credentials; a non-empty list restricts origins AND lets responses carry credentials (the session cookie).
    */
  def policy(spec: String): CORSPolicy =
    val base    = CORS.policy.withAllowMethodsAll.withAllowHeadersAll
    val allowed = spec.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
    if allowed.isEmpty then base.withAllowOriginAll
    else base.withAllowOriginHeader(o => allowed.contains(render(o))).withAllowCredentials(true)

  /** Render an `Origin` to its header form (`scheme://host[:port]`) for matching against the allow-list. */
  private def render(origin: Origin): String = Origin.headerInstance.value(origin)
