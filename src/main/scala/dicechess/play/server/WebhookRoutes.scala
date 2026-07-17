package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Retry-After`
import org.http4s.{HttpRoutes, Response}

/** `POST /bot/webhook` body: the callback URL to verify and register. */
final case class RegisterWebhook(url: String) derives Codec.AsObject

/** The successful registration: the per-bot signing secret, shown exactly once (like a registered bot's token). */
final case class WebhookCreated(url: String, secret: String) derives Codec.AsObject

/** `GET /bot/webhook`: the current registration's public face — the secret is never shown again. */
final case class WebhookInfo(url: String, verifiedAt: java.time.Instant) derives Codec.AsObject

/** The webhook registration surface of the Bot API (F.2, #104): register (with the ownership handshake), inspect,
  * remove. A REGISTERED-bot perk like token rotation and the ladder — anonymous and static bots are refused: the
  * callback URL and signing secret belong to a durable identity, not an ephemeral token.
  *
  * The whole surface answers 503 when webhooks are disabled on the server (`WEBHOOK_TIMEOUT_SECONDS` unset) — the
  * endpoints exist so the failure is explicit, but nothing can be registered that would never fire.
  */
object WebhookRoutes:

  def apply(auth: BotAuth, webhooks: Option[Webhooks], limiter: AnonMintLimiter): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            withRegistered(auth, bot):
              // Rate-limited per IP AFTER the auth/registered gates: registration is the one endpoint where the
              // caller makes this server POST outward (the verification handshake), so its budget must not be
              // consumable by anonymous or unregistered callers at all.
              limiter
                .attempt(BotRoutes.clientIp(req))
                .flatMap:
                  case Left(retryAfter) =>
                    TooManyRequests("webhook registration rate limit exceeded — retry later")
                      .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
                  case Right(()) =>
                    req
                      .attemptAs[RegisterWebhook]
                      .value
                      .flatMap:
                        case Left(failure) => BadRequest(failure.message)
                        case Right(body)   =>
                          service
                            .register(bot, body.url)
                            .flatMap:
                              case Right(hook)  => Created(WebhookCreated(hook.url, hook.secret))
                              case Left(reason) => UnprocessableEntity(reason)

      case req @ GET -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            service
              .info(bot)
              .flatMap:
                case Some(hook) => Ok(WebhookInfo(hook.url, hook.verifiedAt))
                case None       => NotFound()

      case req @ DELETE -> Root / "bot" / "webhook" =>
        withService(webhooks): service =>
          BotRoutes.withBot(auth, req): bot =>
            service.remove(bot).flatMap(removed => if removed then NoContent() else NotFound())

  private def withService(webhooks: Option[Webhooks])(f: Webhooks => IO[Response[IO]]): IO[Response[IO]] =
    webhooks match
      case Some(service) => f(service)
      case None          => ServiceUnavailable("webhooks are not enabled on this server")

  /** Registered bots only — the same `ratingOf`-backed distinction `rotate`/`setOnLadder` use: a row in the bot store
    * is what makes an identity durable enough to own a callback URL.
    */
  private def withRegistered(auth: BotAuth, bot: Principal.Bot)(f: => IO[Response[IO]]): IO[Response[IO]] =
    auth
      .ratingOf(bot)
      .flatMap:
        case Some(_) => f
        case None    => Forbidden("only a registered bot can use webhooks")
