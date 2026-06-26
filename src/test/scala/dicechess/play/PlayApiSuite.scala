package dicechess.play

class PlayApiSuite extends munit.FunSuite:

  test("service exposes its name"):
    assertEquals(PlayApi.name, "dicechess-play-api")
