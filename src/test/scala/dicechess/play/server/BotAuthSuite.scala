package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal

import scala.concurrent.duration.*

class BotAuthSuite extends munit.CatsEffectSuite:

  test("authenticates known static tokens, rejects unknown"):
    BotAuth
      .fromSpec("acme|greedy|tok-1,acme|mcts|tok-2")
      .flatMap: auth =>
        for
          a <- auth.authenticate("tok-1")
          b <- auth.authenticate("tok-2")
          c <- auth.authenticate("unknown")
        yield
          assertEquals(a, Some(Principal.Bot("acme", "greedy")))
          assertEquals(b, Some(Principal.Bot("acme", "mcts")))
          assertEquals(c, None)

  test("ignores malformed or empty roster entries"):
    BotAuth
      .fromSpec("bad-entry,acme|greedy|tok,||,team|name|")
      .flatMap: auth =>
        for
          ok    <- auth.authenticate("tok")
          empty <- auth.authenticate("")
        yield
          assertEquals(ok, Some(Principal.Bot("acme", "greedy")))
          assertEquals(empty, None)

  test("an empty roster authenticates nothing"):
    BotAuth.fromSpec("").flatMap(_.authenticate("anything")).map(assertEquals(_, None))

  test("a minted anonymous token authenticates as bot:team:anon:*, then expires"):
    BotAuth
      .fromSpec("", anonTtl = 120.millis)
      .flatMap: auth =>
        for
          minted <- auth.mintAnon(Some("Alice!"))
          (token, bot) = minted
          live       <- auth.authenticate(token)
          countLive  <- auth.anonCount
          _          <- IO.sleep(180.millis)
          expired    <- auth.authenticate(token)
          countAfter <- auth.anonCount
        yield
          assertEquals(bot.team, "anon")
          assert(bot.externalId.startsWith("bot:team:anon:alice-"), bot.externalId)
          assertEquals(live, Some(bot))
          assertEquals(countLive, 1)
          assertEquals(expired, None)
          assertEquals(countAfter, 0)

  test("an anonymous token with no label is bot:team:anon:<uuid>, colon-free"):
    BotAuth
      .fromSpec("")
      .flatMap: auth =>
        auth
          .mintAnon(None)
          .map: (_, bot) =>
            assertEquals(bot.team, "anon")
            assert(bot.externalId.startsWith("bot:team:anon:"), bot.externalId)
            assert(!bot.name.contains(":"), s"name must be colon-free: ${bot.name}")
