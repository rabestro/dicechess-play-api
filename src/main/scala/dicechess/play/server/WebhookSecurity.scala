package dicechess.play.server

import cats.effect.IO
import org.http4s.Uri

import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The security primitives of webhook delivery (F.2, #104; design: ADR-0013) — pure of any game or store concern so
  * both are testable without a network:
  *
  *   - '''Signing''': every outbound POST carries `X-DiceChess-Signature: HMAC-SHA256(secret, "ts.body")` (hex) and
  *     `X-DiceChess-Timestamp: ts` (epoch seconds). The bot recomputes the MAC with its copy of the secret and rejects
  *     stale timestamps (±5 minutes is the documented window) — authenticity and replay resistance in two headers.
  *   - '''SSRF guard''': play-api POSTs to owner-supplied URLs, which is an outbound request forgery surface — the
  *     policy is HTTPS-only to a host NONE of whose freshly-resolved addresses is non-public (loopback, RFC1918,
  *     link-local — the `169.254.169.254` metadata endpoint lives there — IPv6 ULA, CGNAT, multicast/broadcast,
  *     unspecified). Resolution happens AT SEND TIME on every delivery, never cached, so a DNS record cannot be
  *     re-pointed after registration and trusted from a stale check. The residual DNS-rebinding window between this
  *     resolve and the client's own connect is closed by HTTPS itself: a rebound connection lands on a server that
  *     cannot present a valid certificate for the registered hostname, so the handshake fails before any bytes of the
  *     request leave. Redirects are not followed at all (the plain Ember client has no redirect middleware), so the
  *     check cannot be laundered through a public 302.
  */
object WebhookSecurity:

  val SignatureHeader = "X-DiceChess-Signature"
  val TimestampHeader = "X-DiceChess-Timestamp"

  /** Hex HMAC-SHA256 of `"<timestampEpochSeconds>.<body>"` under `secret` — the `X-DiceChess-Signature` value. */
  def sign(secret: String, timestampEpochSeconds: Long, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(s"$timestampEpochSeconds.$body".getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** `n` random bytes, hex-encoded — webhook secrets (32 bytes) and verification nonces (16 bytes). */
  def randomHex(n: Int): IO[String] = IO:
    val bytes = new Array[Byte](n)
    SecureRandom().nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /** The production URL policy: parse, require https, resolve the host NOW and require every address public. Errors are
    * values (the caller answers 422 with the reason); the only effect is the blocking DNS lookup.
    */
  def checkPublicHttps(url: String): IO[Either[String, Uri]] =
    Uri.fromString(url) match
      case Left(_)    => IO.pure(Left("not a valid URL"))
      case Right(uri) =>
        if !uri.scheme.contains(Uri.Scheme.https) then IO.pure(Left("webhook URL must use https"))
        else
          uri.host.map(_.value) match
            case None       => IO.pure(Left("webhook URL must have a host"))
            case Some(host) =>
              IO.blocking(InetAddress.getAllByName(host)).attempt.map {
                case Left(_)          => Left(s"host does not resolve: $host")
                case Right(addresses) =>
                  addresses.find(a => !isPublic(a)) match
                    case Some(bad) => Left(s"host resolves to a non-public address: ${bad.getHostAddress}")
                    case None      => Right(uri)
              }

  /** Whether an address is routable-public. Java's `isSiteLocalAddress` covers RFC1918 for IPv4 (and the deprecated
    * IPv6 fec0::/10); IPv6 ULA (fc00::/7), CGNAT (100.64.0.0/10) and the limited broadcast address need their own
    * checks. IPv4-mapped IPv6 literals need none: `InetAddress` parses `::ffff:a.b.c.d` into an `Inet4Address`, so they
    * take the IPv4 branch naturally.
    */
  private[server] def isPublic(address: InetAddress): Boolean =
    val bytes       = address.getAddress
    val uniqueLocal = bytes.length == 16 && (bytes(0) & 0xfe) == 0xfc
    val cgnat       = bytes.length == 4 && (bytes(0) & 0xff) == 100 && (bytes(1) & 0xc0) == 64
    val broadcast   = bytes.length == 4 && bytes.forall(_ == -1)
    !(address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress || uniqueLocal || cgnat || broadcast)
