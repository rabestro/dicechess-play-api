package dicechess.play.server

import dicechess.play.core.Principal
import dicechess.play.store.{BotCatalogState, BotStore}

/** Parsing edge cases for the env roster, and that applying it opens exactly the listed registered bots (with their
  * descriptions) while skipping an unregistered identity — the admin gate for bots that cannot self-flag (ADR-0014).
  */
class CatalogRosterSuite extends munit.CatsEffectSuite:

  import CatalogRoster.{Entry, Result}

  test("parse handles bare entries, descriptions with commas, whitespace, and junk"):
    assertEquals(CatalogRoster.parse(""), Nil)
    assertEquals(CatalogRoster.parse("   "), Nil)
    assertEquals(CatalogRoster.parse("gcp|scala-monte-carlo"), List(Entry("gcp", "scala-monte-carlo", None)))
    assertEquals(
      CatalogRoster.parse(" gcp | expectimax-onnx-3 | ONNX expectimax v3, with book "),
      List(Entry("gcp", "expectimax-onnx-3", Some("ONNX expectimax v3, with book")))
    )
    // stray separators, an empty entry, a name-less entry, and a bare token are all ignored
    assertEquals(
      CatalogRoster.parse("a|b|first, one;c|d;;garbage;e|"),
      List(Entry("a", "b", Some("first, one")), Entry("c", "d", None))
    )

  test("apply opens exactly the listed registered bots with their descriptions, and skips an unregistered one"):
    for
      store   <- BotStore.inMemory
      _       <- store.register("t", "alpha", "hash-alpha")
      _       <- store.register("t", "beta", "hash-beta")
      results <- CatalogRoster.apply(store, "t|alpha|Aggressive, with book;t|beta;t|ghost")
      pool    <- store.openToHumansBots
    yield
      assertEquals(
        results,
        List(
          Result.Opened(
            Entry("t", "alpha", Some("Aggressive, with book")),
            BotCatalogState(openToHumans = true, Some("Aggressive, with book"))
          ),
          Result.Opened(Entry("t", "beta", None), BotCatalogState(openToHumans = true, None)),
          Result.Skipped(Entry("t", "ghost", None))
        )
      )
      assert(pool.contains(Principal.Bot("t", "alpha")), s"alpha must be opened, got $pool")
      assert(pool.contains(Principal.Bot("t", "beta")), s"beta must be opened, got $pool")
      assert(!pool.contains(Principal.Bot("t", "ghost")), s"ghost must be skipped, got $pool")

  test("applyRated marks exactly the listed registered bots as curated, and skips an unregistered one"):
    for
      store   <- BotStore.inMemory
      _       <- store.register("t", "curated", "hash-curated")
      _       <- store.register("t", "plain", "hash-plain")
      results <- CatalogRoster.applyRated(store, "t|curated;t|ghost")
      curated <- store.ratingOf("t", "curated")
      plain   <- store.ratingOf("t", "plain")
    yield
      assertEquals(results, List(Result.Rated(Entry("t", "curated", None)), Result.Skipped(Entry("t", "ghost", None))))
      assertEquals(curated.map(_.ratedForHumans), Some(true))
      assertEquals(plain.map(_.ratedForHumans), Some(false), "a bot absent from the roster stays uncurated")

  test("the two rosters are independent: marking a bot rated never opens it to humans"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "rated-only", "hash-rated-only")
      _     <- CatalogRoster.applyRated(store, "t|rated-only")
      after <- store.ratingOf("t", "rated-only")
      pool  <- store.openToHumansBots
    yield
      assertEquals(after.map(_.ratedForHumans), Some(true))
      assert(
        !pool.contains(Principal.Bot("t", "rated-only")),
        s"eligibility for rating must not advertise the bot in the human catalog, got $pool"
      )

  test("the two rosters are independent: opening a bot to humans never makes its games rated"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "open", "hash-open")
      _     <- CatalogRoster.apply(store, "t|open|Plays anyone")
      after <- store.ratingOf("t", "open")
      pool  <- store.openToHumansBots
    yield
      assert(pool.contains(Principal.Bot("t", "open")))
      assertEquals(
        after.map(_.ratedForHumans),
        Some(false),
        "open_to_humans is self-service; rated_for_humans must never ride along with it"
      )

  // The retirement roster. Both flags default to false (V4, V8), so every test below puts the bot IN service
  // first: retiring a freshly registered bot would pass against a no-op implementation and prove nothing.

  test("applyRetired takes an in-service bot off the ladder AND out of the catalog"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "serving", "hash-serving")
      // In service on both axes — this is what makes the assertions below load-bearing.
      _       <- store.setOnLadder("t", "serving", onLadder = true)
      _       <- store.openToHumans("t", "serving", Some("Still playing"))
      before  <- store.ratingOf("t", "serving")
      pre     <- store.openToHumansBots
      results <- CatalogRoster.applyRetired(store, "t|serving")
      after   <- store.ratingOf("t", "serving")
      pool    <- store.openToHumansBots
    yield
      assertEquals(before.map(_.onLadder), Some(true), "setup must actually put the bot on the ladder")
      assert(pre.contains(Principal.Bot("t", "serving")), s"setup must actually open the bot, got $pre")

      assertEquals(results, List(Result.Retired(Entry("t", "serving", None))))
      assertEquals(after.map(_.onLadder), Some(false), "retirement must take the bot off the ladder")
      assert(!pool.contains(Principal.Bot("t", "serving")), s"retirement must close the bot to humans, got $pool")

  test("applyRetired skips a name that is not a registered bot"):
    for
      store   <- BotStore.inMemory
      results <- CatalogRoster.applyRetired(store, "t|ghost")
    yield assertEquals(results, List(Result.Skipped(Entry("t", "ghost", None))))

  test("applyRetired leaves a bot absent from the roster in service"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "retired", "hash-retired")
      _     <- store.register("t", "keeps-playing", "hash-keeps-playing")
      _     <- store.setOnLadder("t", "retired", onLadder = true)
      _     <- store.setOnLadder("t", "keeps-playing", onLadder = true)
      _     <- store.openToHumans("t", "keeps-playing", None)
      _     <- CatalogRoster.applyRetired(store, "t|retired")
      gone  <- store.ratingOf("t", "retired")
      stays <- store.ratingOf("t", "keeps-playing")
      pool  <- store.openToHumansBots
    yield
      assertEquals(gone.map(_.onLadder), Some(false))
      assertEquals(stays.map(_.onLadder), Some(true), "the roster must only ever touch the names it lists")
      assert(pool.contains(Principal.Bot("t", "keeps-playing")), s"an unlisted bot stays in the catalog, got $pool")

  test("applyRetired does not discard operator curation for rating"):
    // Taking a bot out of service is not a judgement on whether its games would have counted. Clearing this too
    // would silently drop `PLAY_RATED_FOR_HUMANS` curation that only an operator can restore, and it buys nothing:
    // a bot that is off the ladder and closed to humans plays no games to rate.
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "curated", "hash-curated")
      _     <- CatalogRoster.applyRated(store, "t|curated")
      _     <- CatalogRoster.applyRetired(store, "t|curated")
      after <- store.ratingOf("t", "curated")
    yield assertEquals(after.map(_.ratedForHumans), Some(true))

  test("conflicts reports a bot listed as retired and additively, and nothing when the lists are disjoint"):
    assertEquals(
      CatalogRoster.conflicts(openSpec = "t|both|Desc", ratedSpec = "", retiredSpec = "t|both"),
      List(Entry("t", "both", Some("Desc")))
    )
    assertEquals(
      CatalogRoster.conflicts(openSpec = "", ratedSpec = "t|both", retiredSpec = "t|both"),
      List(Entry("t", "both", None))
    )
    // Named in both additive rosters and retired: still one line to fix, not two.
    assertEquals(
      CatalogRoster.conflicts(openSpec = "t|both", ratedSpec = "t|both", retiredSpec = "t|both"),
      List(Entry("t", "both", None))
    )
    assertEquals(CatalogRoster.conflicts("t|open", "t|rated", "t|retired"), Nil)
    assertEquals(CatalogRoster.conflicts("", "", ""), Nil)
    // Same name, different team is a different bot.
    assertEquals(CatalogRoster.conflicts("other|same", "", "t|same"), Nil)
