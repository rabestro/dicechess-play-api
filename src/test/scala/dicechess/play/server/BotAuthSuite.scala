package dicechess.play.server

import dicechess.play.core.Principal

class BotAuthSuite extends munit.FunSuite:

  test("parses a roster and authenticates known tokens"):
    val auth = BotAuth.parse("acme|greedy|tok-1,acme|mcts|tok-2")
    assertEquals(auth.authenticate("tok-1"), Some(Principal.Bot("acme", "greedy")))
    assertEquals(auth.authenticate("tok-2"), Some(Principal.Bot("acme", "mcts")))
    assertEquals(auth.authenticate("unknown"), None)

  test("ignores malformed or empty entries"):
    val auth = BotAuth.parse("bad-entry,acme|greedy|tok,||,team|name|")
    assertEquals(auth.authenticate("tok"), Some(Principal.Bot("acme", "greedy")))
    assertEquals(auth.authenticate(""), None)

  test("an empty roster authenticates nothing"):
    assertEquals(BotAuth.parse("").authenticate("anything"), None)
