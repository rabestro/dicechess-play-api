package dicechess.play.server

import cats.effect.IO
import org.http4s.headers.Origin
import org.http4s.server.middleware.{CORS, CORSPolicy}

/** Cross-origin policy for the browser play-site.
  *
  * The API is anonymous-first and uses no cookies: join/Bearer tokens travel explicitly in the
  * URL/query/`Authorization` header, so they are not ambient credentials and CORS-with-credentials is unnecessary (and
  * `*` precludes it anyway). That makes "allow any origin" safe here — CORS protects a user's browser from a malicious
  * page reading the API with *their* credentials, and we have none to leak; it is not a server-side access control
  * (non-browser clients ignore it).
  *
  * By default any origin may read the API. Set `PLAY_CORS_ORIGINS` to a comma-separated allow-list of full origins
  * (e.g. `https://play.jc.id.lv,http://localhost:5173`) to restrict it.
  */
object Cors:

  private val EnvVar = "PLAY_CORS_ORIGINS"

  /** Build the policy from `PLAY_CORS_ORIGINS` (empty/unset → allow all). */
  def fromEnv: IO[CORSPolicy] = IO(sys.env.getOrElse(EnvVar, "")).map(policy)

  /** Build a policy from a comma-separated origin allow-list. An empty/blank spec allows any origin. */
  def policy(spec: String): CORSPolicy =
    val base    = CORS.policy.withAllowMethodsAll.withAllowHeadersAll
    val allowed = spec.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
    if allowed.isEmpty then base.withAllowOriginAll
    else base.withAllowOriginHeader(o => allowed.contains(render(o)))

  /** Render an `Origin` to its header form (`scheme://host[:port]`) for matching against the allow-list. */
  private def render(origin: Origin): String = Origin.headerInstance.value(origin)
