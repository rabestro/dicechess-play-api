package dicechess.play.store

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.server.GameRegistry
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Persistence against a real PostgreSQL (testcontainers): the store round-trip, and the property the whole feature
  * exists for — a live game, its fixed roll included, survives a "crash" (a brand-new registry over the same store).
  */
class PgGameStoreSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def snapshotFixture(status: GameStatus): GameSnapshot =
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> Principal.Guest("w-1"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map(Seat.White -> "white-seed-0123456789ab"),
      started = true,
      ply = 2L,
      pending = true,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 295000L, Seat.Black -> 300000L),
      lastRoll = List(2, 3, 6),
      turns = Vector(TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-after"))
    )

  /** An ended snapshot with a distinct pair of players — `game_results` (#98) tests need their own participant
    * namespace, since this suite shares one database across every test in the file (`TestContainerForAll`, no per-test
    * reset) and `finishedRatedSince` in particular scans every row, not just a chosen participant's.
    */
  private def endedResultFixture(
      white: Principal,
      black: Principal,
      rated: Boolean = false,
      ladder: Boolean = false,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign
  ): GameSnapshot =
    snapshotFixture(GameStatus.Ended(GameOver(result, termination)))
      .copy(players = Map(Seat.White -> white, Seat.Black -> black), rated = Some(rated), ladder = Some(ladder))

  test("a snapshot round-trips through jsonb, and upserts replace by game id"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Active))
          _   <- db.save(id, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
          all <- db.loadActive
        yield
          val (loadedId, snap) = all.find(_._1.value == id.value).getOrElse(fail("saved game not loaded"))
          assertEquals(loadedId.value, id.value)
          assertEquals(snap, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
      }
    }

  test("bot identities round-trip: register once, authenticate by hash, rotate atomically"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          claimed  <- db.register("dragons", "smaug", "hash-1")
          dupe     <- db.register("dragons", "smaug", "hash-other")
          found    <- db.authenticate("hash-1")
          unknown  <- db.authenticate("hash-none")
          rotated  <- db.rotate("dragons", "smaug", "hash-2")
          oldDead  <- db.authenticate("hash-1")
          newAlive <- db.authenticate("hash-2")
          ghost    <- db.rotate("dragons", "nobody", "hash-3")
        yield
          assert(claimed, "a fresh identity must register")
          assert(!dupe, "the primary key must make the second claim lose")
          assertEquals(found, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assertEquals(unknown, None)
          assert(rotated, "rotation of a registered identity must succeed")
          assertEquals(oldDead, None)
          assertEquals(newAlive, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assert(!ghost, "rotating an unregistered identity must report false")
      }
    }

  test("bot rating state: fresh registration is provisional, on_ladder toggles atomically, unregistered is None"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("dragons", "smaug", "hash-1")
          initial <- db.ratingOf("dragons", "smaug")
          joined  <- db.setOnLadder("dragons", "smaug", true)
          reread  <- db.ratingOf("dragons", "smaug")
          left    <- db.setOnLadder("dragons", "smaug", false)
          ghost   <- db.setOnLadder("dragons", "nobody", true)
          unknown <- db.ratingOf("dragons", "nobody")
        yield
          assertEquals(initial, Some(BotRating.initial))
          assertEquals(joined, Some(BotRating.initial.copy(onLadder = true)))
          assertEquals(reread, joined, "the RETURNING result must match a fresh read, not just the pre-update state")
          assertEquals(left, Some(BotRating.initial))
          assertEquals(ghost, None, "toggling an unregistered identity must report None")
          assertEquals(unknown, None)
      }
    }

  test(
    "onLadderCandidates lists only registered bots currently opted in, each with its declared capacity (#102, #189)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        // A dedicated team/hash namespace: this suite shares one database across all tests (TestContainerForAll,
        // no per-test reset), so a name or token hash reused from another test in this file would collide on the
        // token_hash unique constraint — and a plain equality assertion on the candidate list would be fragile
        // against whatever else in the file happens to be on_ladder. Both are avoided here.
        for
          _        <- db.register("ladder-suite", "on-bot", "hash-ladder-on")
          _        <- db.register("ladder-suite", "off-bot", "hash-ladder-off")
          _        <- db.setOnLadder("ladder-suite", "on-bot", true)
          _        <- db.setMaxConcurrentGames("ladder-suite", "on-bot", 3)
          onLadder <- db.onLadderCandidates
        yield
          val bots = onLadder.map(_.bot)
          assert(bots.contains(Principal.Bot("ladder-suite", "on-bot")), s"expected on-bot in $bots")
          assert(!bots.contains(Principal.Bot("ladder-suite", "off-bot")), s"expected off-bot absent from $bots")
          assertEquals(
            onLadder.find(_.bot == Principal.Bot("ladder-suite", "on-bot")).map(_.maxConcurrentGames),
            Some(3),
            "the candidate pool must carry each bot's declared capacity, not a default"
          )
      }
    }

  test("declared capacity: registration defaults to 1, a declaration round-trips, unregistered -> None (#189)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("capacity-suite", "bot", "hash-capacity")
          initial <- db.seatPolicyOf("capacity-suite", "bot")
          raised  <- db.setMaxConcurrentGames("capacity-suite", "bot", 4)
          reread  <- db.seatPolicyOf("capacity-suite", "bot")
          // Opening to humans must shape the ladder's share of the SAME declaration, not the declaration itself.
          _       <- db.openToHumans("capacity-suite", "bot", None)
          opened  <- db.seatPolicyOf("capacity-suite", "bot")
          ghost   <- db.setMaxConcurrentGames("capacity-suite", "nobody", 2)
          unknown <- db.seatPolicyOf("capacity-suite", "nobody")
        yield
          assertEquals(initial.map(_.maxConcurrentGames), Some(BotSeatPolicy.DefaultMaxConcurrentGames))
          assertEquals(initial.map(_.ladderAllowance), Some(1))
          assertEquals(raised.map(_.maxConcurrentGames), Some(4))
          assertEquals(reread, raised, "the RETURNING result must match a fresh read")
          assertEquals(opened.map(_.maxConcurrentGames), Some(4))
          assertEquals(opened.map(_.ladderAllowance), Some(3), "an open-to-humans bot keeps one slot for a person")
          assertEquals(ghost, None, "declaring for an unregistered identity must report None")
          assertEquals(unknown, None)
      }
    }

  test(
    "openToHumans/closeToHumans round-trip the description atomically; the pool lists only opted-in; unregistered -> None (ADR-0014)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _          <- db.register("catalog-suite", "on-bot", "hash-catalog-on")
          _          <- db.register("catalog-suite", "off-bot", "hash-catalog-off")
          opened     <- db.openToHumans("catalog-suite", "on-bot", Some("aggressive + book"))
          pool       <- db.openToHumansBots
          closed     <- db.closeToHumans("catalog-suite", "on-bot")
          poolAfter  <- db.openToHumansBots
          cleared    <- db.openToHumans("catalog-suite", "on-bot", None)
          ghostOpen  <- db.openToHumans("catalog-suite", "nobody", Some("x"))
          ghostClose <- db.closeToHumans("catalog-suite", "nobody")
        yield
          assertEquals(opened, Some(BotCatalogState(openToHumans = true, Some("aggressive + book"))))
          assert(pool.contains(Principal.Bot("catalog-suite", "on-bot")), s"expected on-bot in $pool")
          assert(!pool.contains(Principal.Bot("catalog-suite", "off-bot")), s"expected off-bot absent from $pool")
          assertEquals(
            closed,
            Some(BotCatalogState(openToHumans = false, Some("aggressive + book"))),
            "close keeps the description"
          )
          assert(!poolAfter.contains(Principal.Bot("catalog-suite", "on-bot")), "a closed bot leaves the pool")
          assertEquals(
            cleared,
            Some(BotCatalogState(openToHumans = true, None)),
            "re-open with None clears description"
          )
          assertEquals(ghostOpen, None, "opening an unregistered identity yields None")
          assertEquals(ghostClose, None, "closing an unregistered identity yields None")
      }
    }

  test("catalogBots lists open bots with their rating summary + description, and omits closed ones (ADR-0014, E2)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("cat2", "shown", "hash-cat2-shown")
          _       <- db.register("cat2", "hidden", "hash-cat2-hidden")
          _       <- db.openToHumans("cat2", "shown", Some("monte-carlo, 3-move book"))
          listing <- db.catalogBots
        yield
          assertEquals(
            listing.find(l => l.team == "cat2" && l.name == "shown"),
            Some(
              BotCatalogListing(
                "cat2",
                "shown",
                1500.0,
                350.0,
                Some("monte-carlo, 3-move book"),
                maxConcurrentGames = BotSeatPolicy.DefaultMaxConcurrentGames
              )
            ),
            "a freshly registered open bot lists at the initial rating, its description, and the default declared capacity"
          )
          assert(!listing.exists(_.name == "hidden"), s"a bot not open to humans must be absent, got $listing")
      }
    }

  test("catalogBots carries a raised declared capacity (#189, #224)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("cat2", "roomy", "hash-cat2-roomy")
          _       <- db.openToHumans("cat2", "roomy", None)
          _       <- db.setMaxConcurrentGames("cat2", "roomy", 5)
          listing <- db.catalogBots
        yield assertEquals(
          listing.find(_.name == "roomy").map(_.maxConcurrentGames),
          Some(5),
          "the listing must reflect a capacity raised after registration, not just the default"
        )
      }
    }

  test("webhook registration round-trips, re-register replaces url+secret, delete reports truth (#104)"):
    withContainers { pg =>
      store(pg).use { db =>
        val at = java.time.Instant.parse("2026-07-17T12:00:00Z")
        for
          // A webhook row requires its bot identity (FK to bots) — dedicated namespace, same reasoning as above.
          _        <- db.register("webhook-suite", "pusher", "hash-webhook-pusher")
          none     <- db.get("webhook-suite", "pusher")
          _        <- db.put(BotWebhook("webhook-suite", "pusher", "https://fn.example/turn", "secret-1", at))
          first    <- db.get("webhook-suite", "pusher")
          _        <- db.put(BotWebhook("webhook-suite", "pusher", "https://fn2.example/turn", "secret-2", at))
          replaced <- db.get("webhook-suite", "pusher")
          removed  <- db.delete("webhook-suite", "pusher")
          gone     <- db.get("webhook-suite", "pusher")
          again    <- db.delete("webhook-suite", "pusher")
        yield
          assertEquals(none, None)
          assertEquals(first, Some(BotWebhook("webhook-suite", "pusher", "https://fn.example/turn", "secret-1", at)))
          assertEquals(
            replaced,
            Some(BotWebhook("webhook-suite", "pusher", "https://fn2.example/turn", "secret-2", at)),
            "a re-register must replace the URL and the secret together"
          )
          assertEquals(removed, true)
          assertEquals(gone, None)
          assertEquals(again, false, "deleting an absent registration must report false, not lie")
      }
    }

  test("recordDelivery upserts the histogram cell, and statsFor splits it into the 24h/7d windows (#225)"):
    withContainers { pg =>
      store(pg).use { db =>
        val now       = Instant.parse("2026-08-02T12:00:00Z")
        val within24h = now.minusSeconds(3600)          // 1h ago — in both windows
        val within7d  = now.minusSeconds(3 * 24 * 3600) // 3 days ago — in the 7d window only
        val outside7d = now.minusSeconds(8 * 24 * 3600) // 8 days ago — in neither
        for
          _ <- db.register("stats-suite", "delivery-bot", "hash-stats-delivery")
          // Two deliveries in the same hour land in the SAME cell — proving the upsert accumulates, not overwrites.
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, within24h)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, within24h)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.TimedOut, 2.seconds, within7d)
          _       <- db.recordDelivery("stats-suite", "delivery-bot", DeliveryOutcome.Applied, 10.millis, outside7d)
          stats   <- db.statsFor("stats-suite", "delivery-bot", now)
          nothing <- db.statsFor("stats-suite", "nobody", now)
        yield
          assertEquals(stats.last24h.totalDeliveries, 2L, "only the two within24h deliveries are in the 24h window")
          assertEquals(stats.last24h.outcomes, List(OutcomeCount("applied", 2)))
          assertEquals(
            stats.last7d.totalDeliveries,
            3L,
            "the 7d window adds the timed_out delivery but still excludes the 8-day-old one"
          )
          assertEquals(
            stats.last7d.outcomes.sortBy(_.outcome),
            List(OutcomeCount("applied", 2), OutcomeCount("timed_out", 1))
          )
          assertEquals(nothing, WebhookStats.empty, "a bot with no recorded deliveries reports the empty windows")
      }
    }

  test(
    "recordDelivery sets last_failure_at/reason on a fault, but a clean Applied/Declined never overwrites it (#225)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        val firstFault  = Instant.parse("2026-08-01T10:00:00Z")
        val secondFault = Instant.parse("2026-08-01T11:00:00Z")
        val laterClean  = Instant.parse("2026-08-01T12:00:00Z")
        for
          _ <- db.register("stats-suite", "failure-bot", "hash-stats-failure")
          // last_failure_at/reason live on the bot_webhooks row itself (V13) — a real delivery only ever happens
          // once a webhook is registered (deliverTurn's own guard), so the test mirrors that precondition.
          _       <- db.put(BotWebhook("stats-suite", "failure-bot", "https://fn.example/turn", "secret", firstFault))
          initial <- db.statsFor("stats-suite", "failure-bot", laterClean)
          _ <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.HttpStatus(503), 50.millis, firstFault)
          oneFault <- db.statsFor("stats-suite", "failure-bot", laterClean)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.TimedOut, 30.seconds, secondFault)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.Applied, 10.millis, laterClean)
          _        <- db.recordDelivery("stats-suite", "failure-bot", DeliveryOutcome.Declined, 10.millis, laterClean)
          finalRow <- db.statsFor("stats-suite", "failure-bot", laterClean)
        yield
          assertEquals(initial.lastFailure, None, "no deliveries yet — nothing to report")
          assertEquals(oneFault.lastFailure, Some(LastFailure(firstFault, "the endpoint answered HTTP 503")))
          assertEquals(
            finalRow.lastFailure,
            Some(LastFailure(secondFault, "the server's own delivery window expired with no response")),
            "the LATEST fault must win, and neither the later Applied nor the later Declined may overwrite it"
          )
      }
    }

  test("a webhook row cannot exist without its bot identity — the FK rejects strangers (#104)"):
    withContainers { pg =>
      store(pg).use { db =>
        val at = java.time.Instant.parse("2026-07-17T12:00:00Z")
        db.put(BotWebhook("webhook-suite", "never-registered", "https://fn.example", "s", at)).attempt.map {
          // Precisely the FK violation (SQLSTATE 23503), not just any store failure (review).
          case Left(e: java.sql.SQLException) => assertEquals(e.getSQLState, "23503", e.toString)
          case Left(other)                    => fail(s"expected a foreign-key SQLException, got $other")
          case Right(()) => fail("a webhook for an unregistered identity must be rejected by the FK")
        }
      }
    }

  test("finishing a game inserts exactly one game_results row with the expected fields (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-white-1")
        val black = Principal.Bot("b2-team", "b2-bot-1")
        for
          id <- GameId.random
          _  <- db.save(
            id,
            endedResultFixture(white, black, rated = true, ladder = true)
          )
          rows <- db.recentResultsFor(white.externalId)
        yield
          val row = rows.find(_.gameId.value == id.value).getOrElse(fail(s"row for $id not found in $rows"))
          assertEquals(row.whiteExternalId, white.externalId)
          assertEquals(row.blackExternalId, black.externalId)
          assertEquals(row.result, Some(1), "white won: white-POV result must be 1")
          assertEquals(row.termination, "resign")
          assert(row.rated)
          assertEquals(row.timeControl, TimeControl.Fischer(300, 3).toString)
          assertEquals(row.serverSeed, "ab12cd34")
          assertEquals(row.ladder, true, "the ladder marker (#190) must round-trip through the database")
          assertEquals(row.pairingId, None, "new rows never set pairing_id — it stays for historical CRN rows only")
      }
    }

  test("an active (not yet ended) game does not get a game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-white-active")
        val black = Principal.Guest("b2-black-active")
        for
          id <- GameId.random
          _  <- db.save(
            id,
            snapshotFixture(GameStatus.Active).copy(players = Map(Seat.White -> white, Seat.Black -> black))
          )
          rows <- db.recentResultsFor(white.externalId)
        yield assert(rows.forall(_.gameId.value != id.value), s"an active game must not appear in game_results: $rows")
      }
    }

  test("recentResultsFor finds a game whichever seat the participant sat, newest first (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b2-recent-participant")
        val opponent1   = Principal.Guest("b2-recent-opp1")
        val opponent2   = Principal.Bot("b2-team", "b2-recent-opp2")
        for
          idAsWhite <- GameId.random
          _         <- db.save(idAsWhite, endedResultFixture(participant, opponent1)) // participant seated White
          // A short, deterministic gap: finished_at defaults to the DB's own now(), and the "newest first" ordering
          // this test checks needs the two inserts to land at genuinely distinguishable timestamps.
          _         <- IO.sleep(20.millis)
          idAsBlack <- GameId.random
          _         <- db.save(idAsBlack, endedResultFixture(opponent2, participant)) // participant seated Black
          rows      <- db.recentResultsFor(participant.externalId)
        yield assertEquals(
          rows.map(_.gameId.value),
          List(idAsBlack.value, idAsWhite.value),
          s"expected newest first: $rows"
        )
      }
    }

  test("finishedRatedSince returns only rated games finished strictly after the cursor (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val before = Principal.Guest("b2-since-w1")
        for
          idBefore <- GameId.random
          _        <- db.save(idBefore, endedResultFixture(before, Principal.Guest("b2-since-b1"), rated = true))
          // The cursor is the row's OWN database-generated finished_at, not a JVM-side Instant.now(): comparing a
          // local clock against Postgres's own now() would make this boundary assertion depend on the two clocks
          // being in sync, which isn't guaranteed (#98 review).
          beforeRow <- db
            .recentResultsFor(before.externalId)
            .map(_.find(_.gameId.value == idBefore.value).getOrElse(fail("row not found right after saving it")))
          cursor = beforeRow.finishedAt
          // A short, deterministic gap so the next inserts' own finished_at lands strictly after the cursor.
          _            <- IO.sleep(20.millis)
          idAfterRated <- GameId.random
          _            <- db.save(
            idAfterRated,
            endedResultFixture(Principal.Guest("b2-since-w2"), Principal.Guest("b2-since-b2"), rated = true)
          )
          idAfterCasual <- GameId.random
          _             <- db.save(
            idAfterCasual,
            endedResultFixture(Principal.Guest("b2-since-w3"), Principal.Guest("b2-since-b3"), rated = false)
          )
          since <- db.finishedRatedSince(cursor)
        yield
          val ids = since.map(_.gameId.value).toSet
          assert(!ids.contains(idBefore.value), "a game AT the cursor must be excluded (strictly after)")
          assert(ids.contains(idAfterRated.value), "a rated game finished after the cursor must be included")
          assert(!ids.contains(idAfterCasual.value), "a casual (non-rated) game must be excluded regardless of timing")
      }
    }

  test("recentResultsFor does not double-count a self-played game (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        // GameRegistry.create itself doesn't forbid seating the same principal on both sides (only its
        // Lobby/Challenges callers do) — a UNION ALL of the white/black subqueries would otherwise return this
        // game twice.
        val soloPlayer = Principal.Guest("b2-self-play")
        for
          id   <- GameId.random
          _    <- db.save(id, endedResultFixture(soloPlayer, soloPlayer))
          rows <- db.recentResultsFor(soloPlayer.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("saving the same ended snapshot twice still inserts exactly one game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-idempotent-white")
        val black = Principal.Guest("b2-idempotent-black")
        for
          id <- GameId.random
          fixture = endedResultFixture(white, black)
          _    <- db.save(id, fixture)
          _    <- db.save(id, fixture) // re-save: same game id, ON CONFLICT (game_id) DO NOTHING must hold
          rows <- db.recentResultsFor(white.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("finishing a game inserts a game_archive row whose payload round-trips (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-white")
        val black = Principal.Bot("b2-team", "b2-archive-bot")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, rated = true))
          archive <- db.archiveFor(id)
        yield
          val payload = archive.getOrElse(fail(s"no game_archive row for $id")).payload
          val c       = payload.hcursor
          assert(c.get[Boolean]("rated").toOption.contains(true))
          assertEquals(c.downField("players").get[String]("white").toOption, Some(white.externalId))
          assertEquals(c.downField("players").get[String]("black").toOption, Some(black.externalId))
          assertEquals(
            c.downField("turns").downN(0).get[List[String]]("moves").toOption,
            Some(List("e2e4")),
            s"the turn recorded on the fixture snapshot must round-trip: $payload"
          )
          assert(
            c.downField("fairness").get[String]("commit").toOption.exists(_.nonEmpty),
            s"the fairness block must be present: $payload"
          )
      }
    }

  test("an active (not yet ended) game does not get a game_archive row (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id      <- GameId.random
          _       <- db.save(id, snapshotFixture(GameStatus.Active))
          archive <- db.archiveFor(id)
        yield assertEquals(archive, None)
      }
    }

  test("an aborted game does not get a game_archive row, unlike game_results (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-aborted-white")
        val black = Principal.Guest("b2-archive-aborted-black")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, termination = Termination.Aborted))
          archive <- db.archiveFor(id)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(archive, None, "an aborted game has no sporting outcome and must not be archived")
          assert(
            results.exists(_.gameId.value == id.value),
            "unlike the archive, game_results DOES keep an aborted game as an operational row"
          )
      }
    }

  test("saving the same ended snapshot twice still inserts exactly one game_archive row (#177)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-archive-idempotent-white")
        val black = Principal.Guest("b2-archive-idempotent-black")
        for
          id <- GameId.random
          fixture = endedResultFixture(white, black)
          _       <- db.save(id, fixture)
          _       <- db.save(id, fixture) // re-save: same game id, ON CONFLICT (game_id) DO NOTHING must hold
          archive <- db.archiveFor(id)
        yield assert(archive.isDefined, "expected exactly one (unconflicted) game_archive row")
      }
    }

  /** A second, unpooled connection to the SAME database, used only to forge the pre-#177 state the backfill exists to
    * repair: an ended game whose snapshot is on disk but whose archive row is missing. No production path ever deletes
    * an archive row, so there is no store method for it — and forging it is the only way to test the repair.
    */
  private def rawXa(pg: PostgreSQLContainer) =
    for
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor
        .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, connectEC)
    yield xa

  test("the backfill stamps finished_at from the game's own finish time, not the backfill time (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-when-white")
        val black = Principal.Bot("b4-team", "b4-backfill-when-bot")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black, rated = true))
          // Forge the pre-#177 state, and age the game a week so "now()" and "the real finish time" cannot be
          // confused for each other — this is the specific bug #199 exists to avoid.
          realFinish = Instant.parse("2026-07-24T10:00:00Z")
          _ <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          _ <- sql"UPDATE play.game_results SET finished_at = $realFinish WHERE game_id = ${id.value}::uuid".update.run
            .transact(xa)
          batch   <- db.backfillArchive(after = None, limit = 500)
          archive <- db.archiveFor(id)
        yield
          assert(batch.inserted >= 1, s"the forged row must be back-filled: $batch")
          val row = archive.getOrElse(fail(s"no game_archive row for $id after the backfill"))
          assertEquals(
            row.finishedAt,
            realFinish,
            "finished_at must come from game_results, NOT the column's DEFAULT now() — GET /games/{id}/history " +
              "serves this field straight to the replay page"
          )
      }
    }

  test("a back-filled payload is identical to the one written natively at game end (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-same-white")
        val black = Principal.Bot("b4-team", "b4-backfill-same-bot")
        for
          id     <- GameId.random
          _      <- db.save(id, endedResultFixture(white, black, rated = true))
          native <- db.archiveFor(id)
          _      <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          _      <- db.backfillArchive(after = None, limit = 500)
          filled <- db.archiveFor(id)
        yield assertEquals(
          filled.map(_.payload),
          native.map(_.payload),
          "the backfill reuses GameArchive.payload, so the row must be byte-identical — no second code path to drift"
        )
      }
    }

  test("the backfill is idempotent: a second pass over the same games inserts nothing (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-twice-white")
        val black = Principal.Guest("b4-backfill-twice-black")
        for
          id     <- GameId.random
          _      <- db.save(id, endedResultFixture(white, black))
          _      <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          first  <- db.backfillArchive(after = None, limit = 500)
          second <- db.backfillArchive(after = None, limit = 500)
        yield
          assert(first.inserted >= 1, s"the first pass must insert the forged row: $first")
          // `inserted`, not `scanned`: a second pass legitimately still SCANS the rows that can never be archived —
          // this suite shares one database across every test, and an aborted game (see the #177 test above) is a
          // permanent, correct skip. Idempotence means writing nothing new, not running out of rows to look at.
          assertEquals(second.inserted, 0, s"a second pass must write nothing new: $second")
      }
    }

  test("the cursor advances past a game it cannot convert, instead of re-scanning it forever (#199)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b4-backfill-corrupt-white")
        val black = Principal.Guest("b4-backfill-corrupt-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          _  <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          // Corrupt the snapshot so `json.as[GameSnapshot]` fails: the row can never be converted, and a loop that
          // re-queried `NOT EXISTS` from the start would spin on it forever.
          _ <- sql"""UPDATE play.games SET snapshot = '{"not":"a snapshot"}'::jsonb
                     WHERE id = ${id.value}::uuid""".update.run.transact(xa)
          batch <- db.backfillArchive(after = None, limit = 500)
          // The batch that saw it must report a cursor at least as far as this game, so the next call starts beyond it.
          next <- db.backfillArchive(batch.lastId, limit = 500)
        yield
          assert(batch.skipped >= 1, s"the corrupt row must be counted as skipped, not inserted: $batch")
          assert(
            batch.lastId.exists(_.value >= id.value),
            s"the cursor must move past the unconvertible row: ${batch.lastId} vs $id"
          )
          assertEquals(next.scanned, 0, s"nothing may remain after the cursor: $next")
      }
    }

  /** Ages a finished game's operational rows past a retention cutoff. Production never back-dates anything, so there is
    * no store method for this — but without it every retention test would have to wait out a real interval.
    */
  private def ageGame(xa: doobie.Transactor[IO], id: GameId, at: Instant): IO[Unit] =
    (
      sql"UPDATE play.games SET updated_at = $at WHERE id = ${id.value}::uuid".update.run,
      sql"UPDATE play.outbox SET delivered_at = $at WHERE game_id = ${id.value}::uuid".update.run
    ).mapN((_, _) => ()).transact(xa)

  private val LongAgo: Instant  = Instant.parse("2020-01-01T00:00:00Z")
  private val PruneCut: Instant = Instant.parse("2020-06-01T00:00:00Z")

  /** Prunes until a batch removes nothing and returns that terminal batch — the same loop `Retention.drain` runs, and
    * the only state in which `RetentionSweep.retainedUnarchived` is measured rather than left at 0.
    */
  private def drainPrune(db: PgGameStore): IO[RetentionSweep] =
    db.pruneOnce(PruneCut, limit = 500)
      .flatMap(sweep => if sweep.removedAnything then drainPrune(db) else IO.pure(sweep))

  test("retention prunes a delivered client report past the cutoff and keeps a parked one (#212)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          deliveredId <- GameId.random
          parkedId    <- GameId.random
          payload = io.circe.Json.obj("id" -> io.circe.Json.fromString("irrelevant"))
          _ <- db.insertClientReport(deliveredId, payload)
          _ <- db.insertClientReport(parkedId, payload)
          _ <- db.clientReports.markDelivered(deliveredId)
          _ <-
            sql"UPDATE play.client_reports SET delivered_at = $LongAgo WHERE report_id = ${deliveredId.value}::uuid".update.run
              .transact(xa)
          _ <- db.clientReports.markParked(parkedId, "422 from the replay gate")
          // Back-date the parked row's delivered_at too (production leaves it NULL): with only the NULL check
          // protecting it, this test would pass even if the NOT failed_permanently guard were dropped.
          _ <-
            sql"UPDATE play.client_reports SET delivered_at = $LongAgo WHERE report_id = ${parkedId.value}::uuid".update.run
              .transact(xa)
          _             <- db.pruneOnce(PruneCut, limit = 500)
          deliveredLeft <- sql"SELECT count(*) FROM play.client_reports WHERE report_id = ${deliveredId.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          parkedLeft <- sql"SELECT count(*) FROM play.client_reports WHERE report_id = ${parkedId.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(deliveredLeft, 0, "a delivered report past the cutoff is dead weight")
          assertEquals(parkedLeft, 1, "a parked report is kept for manual inspection, not pruned")
      }
    }

  test("retention prunes an old ended game's delivered outbox row and its snapshot, keeping the archive (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-prune-white")
        val black = Principal.Bot("b5-team", "b5-prune-bot")
        for
          id           <- GameId.random
          _            <- db.save(id, endedResultFixture(white, black, rated = true))
          _            <- ageGame(xa, id, LongAgo)
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          archive <- db.archiveFor(id)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(outboxLeft, 0, "a delivered outbox row past the cutoff is dead weight")
          assertEquals(snapshotLeft, 0, "the ended snapshot is dead weight once the archive serves its history")
          assert(archive.isDefined, "the archive is permanent by contract and must survive the prune")
          assert(
            results.exists(_.gameId.value == id.value),
            "game_results is the list/rating projection and must survive the prune too"
          )
      }
    }

  test("a pruned game's history is still served from the archive — the whole point of #179"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-served-white")
        val black = Principal.Bot("b5-team", "b5-served-bot")
        for
          id      <- GameId.random
          _       <- db.save(id, endedResultFixture(white, black, rated = true))
          before  <- db.archiveFor(id)
          _       <- ageGame(xa, id, LongAgo)
          _       <- db.pruneOnce(PruneCut, limit = 500)
          after   <- db.archiveFor(id)
          gameRow <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid".query[Int].unique.transact(xa)
        yield
          assertEquals(gameRow, 0, "the snapshot must actually be gone, or this proves nothing")
          assertEquals(after.map(_.payload), before.map(_.payload), "replay must read identically after the prune")
      }
    }

  test("an ACTIVE game is never pruned, however old its row looks (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        for
          id <- GameId.random
          _  <- db.save(id, snapshotFixture(GameStatus.Active))
          // Back-date it far past the cutoff: only `status` may decide this, never age. Pruning a live snapshot would
          // forfeit a real game on the next boot, since resume reads WHERE status='active'.
          _ <- sql"UPDATE play.games SET updated_at = $LongAgo WHERE id = ${id.value}::uuid".update.run.transact(xa)
          _ <- db.pruneOnce(PruneCut, limit = 500)
          active <- db.loadActive
        yield assert(
          active.exists(_._1.value == id.value),
          "an active game must survive retention and still be resumable"
        )
      }
    }

  test("a parked outbox row and its snapshot both survive retention (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-parked-white")
        val black = Principal.Guest("b5-parked-black")
        for
          id         <- GameId.random
          _          <- db.save(id, endedResultFixture(white, black))
          _          <- ageGame(xa, id, LongAgo)
          _          <- db.markParked(id, "422 from the replay gate")
          _          <- db.pruneOnce(PruneCut, limit = 500)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(outboxLeft, 1, "a parked row is kept for manual inspection, not pruned")
          assertEquals(
            snapshotLeft,
            1,
            "and the FK pins its snapshot too — the evidence for that inspection stays whole"
          )
      }
    }

  test("an unarchived ended game is retained and counted, never silently destroyed (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-unarchived-white")
        val black = Principal.Guest("b5-unarchived-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          _  <- ageGame(xa, id, LongAgo)
          // Forge the pre-#177 state: history exists ONLY in this snapshot. Pruning it would recreate exactly the loss
          // #199 had to repair, so the pass must refuse and say so.
          _ <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          // Drain to a terminal batch, exactly as `Retention.drain` does: `retainedUnarchived` is only measured on a
          // batch that removed nothing (see RetentionSweep), so reading it off a single call would depend on whether
          // some other test's aged row happened to still be prunable.
          sweep        <- drainPrune(db)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(snapshotLeft, 1, "the only copy of this game's history must survive")
          assert(sweep.retainedUnarchived >= 1, s"and the refusal must be visible, not silent: $sweep")
      }
    }

  test("an aborted game's snapshot IS pruned — it has no history to preserve by design (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-aborted-white")
        val black = Principal.Guest("b5-aborted-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black, termination = Termination.Aborted))
          _  <- ageGame(xa, id, LongAgo)
          // An aborted game never gets an archive row (GameArchive.payload excludes it), so the archive-exists guard
          // alone would retain it forever. The aborted carve-out is what lets it go.
          archive      <- db.archiveFor(id)
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          assertEquals(archive, None, "precondition: an aborted game is never archived")
          // That its snapshot is gone IS the assertion: had the aborted carve-out been missing, the archive-exists
          // guard would have retained it. `sweep.retainedUnarchived` is deliberately not asserted here — it counts
          // table-wide, and this suite shares one database, so a row another test intentionally left behind (see the
          // unarchived test above) legitimately shows up in it.
          assertEquals(snapshotLeft, 0, "so it must be prunable without the unarchived guard blocking it")
      }
    }

  test("retention leaves anything newer than the cutoff completely alone (#179)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("b5-fresh-white")
        val black = Principal.Guest("b5-fresh-black")
        for
          id <- GameId.random
          _  <- db.save(id, endedResultFixture(white, black))
          // Deliberately NOT aged: a just-finished game is exactly what an operator may still need.
          _            <- db.pruneOnce(PruneCut, limit = 500)
          snapshotLeft <- sql"SELECT count(*) FROM play.games WHERE id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
          outboxLeft <- sql"SELECT count(*) FROM play.outbox WHERE game_id = ${id.value}::uuid"
            .query[Int]
            .unique
            .transact(xa)
        yield
          // Only this game's own rows are asserted, for the same shared-database reason as the aborted test above.
          assertEquals(snapshotLeft, 1, "a fresh snapshot is untouched")
          assertEquals(outboxLeft, 1, "and so is its outbox row")
      }
    }

  test("playerGamesPage keyset-paginates: `before` returns only strictly older games, still newest first (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-page-participant")
        val opponent    = Principal.Bot("b3-team", "b3-page-opponent")
        for
          idOldest  <- GameId.random
          _         <- db.save(idOldest, endedResultFixture(participant, opponent))
          _         <- IO.sleep(20.millis)
          idMiddle  <- GameId.random
          _         <- db.save(idMiddle, endedResultFixture(opponent, participant))
          middleRow <- db
            .playerGamesPage(participant.externalId, None, None, None, limit = 100)
            .map(_.games.find(_.gameId.value == idMiddle.value).getOrElse(fail("middle row not found")))
          _        <- IO.sleep(20.millis)
          idNewest <- GameId.random
          _        <- db.save(idNewest, endedResultFixture(participant, opponent))
          page     <- db.playerGamesPage(participant.externalId, Some(middleRow.finishedAt), None, None, limit = 100)
        yield
          val ids = page.games.map(_.gameId.value)
          assert(!ids.contains(idNewest.value), "newer than `before` excluded")
          assert(!ids.contains(idMiddle.value), "AT `before` excluded (strictly older only)")
          assertEquals(ids, List(idOldest.value), s"expected only the oldest row, got $page")
      }
    }

  test("playerGamesPage reports `hasMore` exactly, without fetching the whole history (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-hasmore-participant")
        val opponent    = Principal.Bot("b3-team", "b3-hasmore-opponent")
        for
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, opponent)))
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(opponent, participant)))
          _         <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, opponent)))
          fullPage  <- db.playerGamesPage(participant.externalId, None, None, None, limit = 3)
          shortPage <- db.playerGamesPage(participant.externalId, None, None, None, limit = 2)
        yield
          assertEquals(fullPage.hasMore, false, "exactly 3 rows fit a limit-3 page")
          assertEquals(shortPage.hasMore, true, "3 rows do not fit a limit-2 page")
      }
    }

  test("playerGamesPage `OpponentFilter.Bot` restricts to games against that one bot (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-vsbot-participant")
        val botA        = Principal.Bot("b3-team", "b3-vsbot-a")
        val botB        = Principal.Bot("b3-team", "b3-vsbot-b")
        for
          idVsA <- GameId.random
          _     <- db.save(idVsA, endedResultFixture(participant, botA))
          idVsB <- GameId.random
          _     <- db.save(idVsB, endedResultFixture(botB, participant))
          page  <- db.playerGamesPage(
            participant.externalId,
            None,
            Some(OpponentFilter.Bot(botA.externalId)),
            None,
            limit = 100
          )
        yield assertEquals(page.games.map(_.gameId.value), List(idVsA.value))
      }
    }

  test("playerGamesPage `OpponentFilter.HumanOnly` restricts to games against non-bot opponents (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-vshuman-participant")
        val bot         = Principal.Bot("b3-team", "b3-vshuman-bot")
        val human       = Principal.Guest("b3-vshuman-opponent")
        for
          idVsBot   <- GameId.random
          _         <- db.save(idVsBot, endedResultFixture(participant, bot))
          idVsHuman <- GameId.random
          _         <- db.save(idVsHuman, endedResultFixture(human, participant))
          page <- db.playerGamesPage(participant.externalId, None, Some(OpponentFilter.HumanOnly), None, limit = 100)
        yield assertEquals(page.games.map(_.gameId.value), List(idVsHuman.value))
      }
    }

  test("playerGamesPage `result` filters by the participant's OWN point of view regardless of seat (#173)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-povresult-participant")
        val opponent    = Principal.Bot("b3-team", "b3-povresult-opponent")
        for
          idWinAsWhite <- GameId.random
          _ <- db.save(idWinAsWhite, endedResultFixture(participant, opponent, result = GameResult.Win(Side.White)))
          idWinAsBlack <- GameId.random
          // Stored white-POV: Black winning is result = -1, even though the PARTICIPANT (seated Black here) won.
          _ <- db.save(idWinAsBlack, endedResultFixture(opponent, participant, result = GameResult.Win(Side.Black)))
          idLoss <- GameId.random
          _      <- db.save(idLoss, endedResultFixture(participant, opponent, result = GameResult.Win(Side.Black)))
          wins   <- db.playerGamesPage(participant.externalId, None, None, Some(PovResultFilter.Win), limit = 100)
        yield assertEquals(
          wins.games.map(_.gameId.value).toSet,
          Set(idWinAsWhite.value, idWinAsBlack.value),
          "both wins returned regardless of which seat the participant sat; the loss excluded"
        )
      }
    }

  test("opponentsFor groups by specific bot and collapses every human opponent into one row (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-opponents-participant")
        val bot         = Principal.Bot("b3-team", "b3-opponents-bot")
        val humanA      = Principal.Guest("b3-opponents-human-a")
        val humanB      = Principal.Guest("b3-opponents-human-b")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, bot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(bot, participant)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, humanA)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(humanB, participant)))
          rows <- db.opponentsFor(participant.externalId)
          byBotKey = rows.map(r => r.botExternalId -> r.games).toMap
        yield
          assertEquals(byBotKey.get(Some(bot.externalId)), Some(2), s"both bot games grouped together: $rows")
          assertEquals(byBotKey.get(None), Some(2), s"both human opponents collapsed into one row: $rows")
          assertEquals(rows.size, 2, "exactly one bot row plus one collapsed human row")
      }
    }

  test("opponentsFor computes W-D-L from the participant's own POV regardless of which seat they sat (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-opppov-participant")
        val bot         = Principal.Bot("b3-team", "b3-opppov-bot")
        for
          _ <- GameId.random.flatMap(
            db.save(_, endedResultFixture(participant, bot, result = GameResult.Win(Side.White)))
          )
          // Participant seated Black and won: stored white-POV result is Black winning, i.e. -1.
          _ <- GameId.random.flatMap(
            db.save(_, endedResultFixture(bot, participant, result = GameResult.Win(Side.Black)))
          )
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, bot, result = GameResult.Draw)))
          rows <- db.opponentsFor(participant.externalId)
          botRow = rows.find(_.botExternalId.contains(bot.externalId)).getOrElse(fail(s"no row for the bot: $rows"))
        yield
          assertEquals(botRow.games, 3)
          assertEquals(botRow.wins, 2, "both wins counted regardless of seat")
          assertEquals(botRow.draws, 1)
          assertEquals(botRow.losses, 0)
      }
    }

  test("opponentsFor excludes self-play — a game against yourself has no opponent to aggregate against (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val soloPlayer = Principal.Guest("b3-selfplay-participant")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(soloPlayer, soloPlayer)))
          rows <- db.opponentsFor(soloPlayer.externalId)
        yield assertEquals(rows, Nil, s"self-play must not appear as an opponent row: $rows")
      }
    }

  test("opponentsFor orders most-played first (#174)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b3-oppsort-participant")
        val busyBot     = Principal.Bot("b3-team", "b3-oppsort-busy")
        val quietBot    = Principal.Bot("b3-team", "b3-oppsort-quiet")
        for
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, quietBot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(participant, busyBot)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(busyBot, participant)))
          rows <- db.opponentsFor(participant.externalId)
        yield assertEquals(
          rows.map(_.botExternalId),
          List(Some(busyBot.externalId), Some(quietBot.externalId)),
          s"busier opponent first: $rows"
        )
      }
    }

  test("opponentsFor is empty for a participant with no games (#174)"):
    withContainers { pg =>
      store(pg).use(db => db.opponentsFor(Principal.Guest("b3-opponents-nobody").externalId).map(assertEquals(_, Nil)))
    }

  test("opponentsFor works the same when the participant is a bot: opponents itemized, humans collapsed (#182)"):
    withContainers { pg =>
      store(pg).use { db =>
        val profiledBot = Principal.Bot("b3-team", "b3-opponents-profiled")
        val otherBot    = Principal.Bot("b3-team", "b3-opponents-other")
        val humanA      = Principal.Guest("b3-opponents-profiled-human-a")
        val humanB      = Principal.Guest("b3-opponents-profiled-human-b")
        for
          // A ladder game against another bot is rated; every guest game is casual (`GameRegistry.isRated`) —
          // mixing both here is the point: a bot's "record vs humans" must count the casual games too.
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(profiledBot, otherBot, rated = true)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(humanA, profiledBot, rated = false)))
          _    <- GameId.random.flatMap(db.save(_, endedResultFixture(profiledBot, humanB, rated = false)))
          rows <- db.opponentsFor(profiledBot.externalId)
          byBotKey = rows.map(r => r.botExternalId -> r.games).toMap
        yield
          assertEquals(byBotKey.get(Some(otherBot.externalId)), Some(1), s"the other bot itemized: $rows")
          assertEquals(byBotKey.get(None), Some(2), s"both unrated human games collapsed into one row: $rows")
          assertEquals(rows.size, 2, "exactly one bot row plus one collapsed human row")
      }
    }

  test("ended games are not resumed"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw))))
          all <- db.loadActive
        yield assert(all.forall(_._1.value != id.value), "an ended game must not appear in loadActive")
      }
    }

  test("the leaderboard lists converged bots best-first with their rated records and hides provisional ones (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val strong: Principal.Bot = Principal.Bot("lb-suite", "strong")
        val weak: Principal.Bot   = Principal.Bot("lb-suite", "weak")
        for
          _ <- db.register("lb-suite", "strong", "hash-lb-strong")
          _ <- db.register("lb-suite", "weak", "hash-lb-weak")
          _ <- db.register("lb-suite", "fresh", "hash-lb-fresh") // untouched: RD 350 = provisional
          _ <- db.setOnLadder("lb-suite", "strong", true)
          // Converge both veterans' ratings. The stamped game id is random and matches no game_results row, so the
          // stamp inside applyRatingUpdate is a no-op — this is purely "set two bots' glicko state atomically".
          fakeId <- GameId.random
          _      <- db.applyRatingUpdate(
            fakeId,
            strong,
            dicechess.play.rating.Glicko(1700.0, 80.0, 0.05),
            weak,
            dicechess.play.rating.Glicko(1400.0, 90.0, 0.05)
          )
          // The rated record: strong beats weak once per colour, plus one draw; one casual win must not count.
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(strong, weak, rated = true)) // strong wins as White
          idB <- GameId.random
          _   <- db.save(
            idB,
            endedResultFixture(weak, strong, rated = true, result = GameResult.Win(Side.Black))
          ) // strong wins as Black
          idC   <- GameId.random
          _     <- db.save(idC, endedResultFixture(strong, weak, rated = true, result = GameResult.Draw))
          idD   <- GameId.random
          _     <- db.save(idD, endedResultFixture(strong, weak, rated = false)) // casual: excluded from the tally
          board <- db.leaderboard(maxRd = 110.0).map(_.filter(_.team == "lb-suite"))
        yield
          assertEquals(board.map(_.name), List("strong", "weak"), "best rating first; provisional 'fresh' hidden")
          val strongRow = board.head
          assertEquals(strongRow.tally, ResultTally(wins = 2, draws = 1, losses = 0))
          assert(strongRow.onLadder, "the on-ladder flag must ride along")
          assertEquals(board(1).tally, ResultTally(wins = 0, draws = 1, losses = 2))
          assert(!board(1).onLadder)
      }
    }

  test("resultTallyFor counts rated decided games from either seat, and is Empty for a stranger (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val a = Principal.Bot("lb-tally", "a")
        val b = Principal.Bot("lb-tally", "b")
        for
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(a, b, rated = true)) // a wins as White
          idB <- GameId.random
          _   <- db.save(idB, endedResultFixture(b, a, rated = true, result = GameResult.Win(Side.Black))) // a as Black
          idC <- GameId.random
          _        <- db.save(idC, endedResultFixture(a, b, rated = false)) // casual: excluded
          tallyA   <- db.resultTallyFor(a.externalId)
          tallyB   <- db.resultTallyFor(b.externalId)
          stranger <- db.resultTallyFor("bot:team:lb-tally:nobody")
        yield
          assertEquals(tallyA, ResultTally(wins = 2, draws = 0, losses = 0))
          assertEquals(tallyA.games, 2)
          assertEquals(tallyB, ResultTally(wins = 0, draws = 0, losses = 2))
          assertEquals(stranger, ResultTally.Empty)
      }
    }

  test("a live game — its fixed roll included — survives a crash and plays on with the same commitment"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          // Life before the crash: create a game, seed both seats, and see the opening roll land.
          registry1 <- GameRegistry.create(store = db)
          created   <- registry1.create(Principal.Guest("w-uuid"), Principal.Guest("b-uuid"))
          (id, room1) = created.toOption.getOrElse(fail("game creation failed"))
          _ <- room1.submit(Seat.White, GameCommand.SubmitSeed("white-client-seed-0001"))
          _ <- room1.submit(Seat.Black, GameCommand.SubmitSeed("black-client-seed-0001"))
          // Poll the public state instead of subscribing: a slow subscriber can miss the live roll event.
          _ <- room1.snapshot
            .flatTap(ps => IO.sleep(20.millis).unlessA(ps.dicePending))
            .iterateUntil(_.dicePending)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no opening roll")))
          before  <- room1.snapshot
          commit1 <- room1.diceCommit
          tokens1 = room1.joinTokens

          // The "crash": a brand-new registry over the same store, as a fresh process would build on boot.
          registry2 <- GameRegistry.create(store = db)
          resumed   <- registry2.resume
          _ = assert(resumed >= 1, "at least our live game must be resumed")
          room2   <- registry2.get(id).map(_.getOrElse(fail("resumed game not found in the registry")))
          after   <- room2.snapshot
          commit2 <- room2.diceCommit

          // The game still ends properly: the resumed room accepts commands and reveals the SAME committed seed.
          // Deterministic handshake: the subscriber's first pulled event (the initial Snapshot) proves registration,
          // so the resign can't race the subscription and the terminal event can't be missed.
          ready <- Deferred[IO, Unit]
          ended = room2.subscribe
            .evalTap(_ => ready.complete(()).void)
            .collectFirst { case e: GameEvent.GameEnded => e }
            .compile
            .lastOrError
          resign = ready.get *> room2.submit(Seat.White, GameCommand.Resign)
          terminal <- (ended, resign)
            .parMapN((e, _) => e)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no end")))
        yield
          assertEquals(after.dfen, before.dfen, "the pending roll (DFEN dice pool) must survive the crash")
          assertEquals(commit2, commit1, "the dice commitment must survive the crash")
          assertEquals(room2.joinTokens, tokens1, "seat tokens must survive so players can reconnect")
          assertEquals(
            sha256Hex(terminal.seed.getOrElse(fail("expected a revealed seed"))),
            commit1,
            "the revealed seed still opens the pre-crash commitment"
          )
          assertEquals(
            terminal.clientSeeds,
            Some(ClientSeeds("white-client-seed-0001", "black-client-seed-0001")),
            "the submitted client seeds survive the crash into the reveal"
          )
      }
    }

  // ── UserStore (#232) — every test uses its own subject/nickname namespace: the suite shares one
  // database across all tests (TestContainerForAll, no per-test reset). ──────────────────────────

  test("first login creates an account with a fresh nickname; repeat login reuses the same account"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val storedEmail =
          sql"""SELECT email FROM play.user_identities
                WHERE provider = 'google' AND subject = 'sub-login-1'"""
            .query[Option[String]]
            .unique
            .transact(xa)
        for
          first     <- db.upsertOnLogin("google", "sub-login-1", Some("first@example.com"), IO.pure("LoginNick1"))
          again     <- db.upsertOnLogin("google", "sub-login-1", None, IO.pure("NeverUsed2"))
          kept      <- storedEmail
          _         <- db.upsertOnLogin("google", "sub-login-1", Some("renamed@example.com"), IO.pure("NeverUsed3"))
          refreshed <- storedEmail
          loaded    <- db.userById(first.id)
        yield
          assertEquals(first.nickname, "LoginNick1")
          assertEquals(again.id, first.id)
          assertEquals(again.nickname, "LoginNick1", "a repeat login must not rename the account")
          assert(again.lastLoginAt.nonEmpty, "repeat login must stamp last_login_at")
          assertEquals(kept, Some("first@example.com"), "a login without an email must not blank the stored one")
          assertEquals(refreshed, Some("renamed@example.com"), "a login with a new email refreshes the stored one")
          assert(loaded.exists(_.isActive), "accounts start active")
      }
    }

  test("a nickname collision at first login retries with the next candidate, case-insensitively"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          counter <- IO.ref(0)
          gen = counter.getAndUpdate(_ + 1).map(i => if i == 0 then "collidenick" else "CollideSecond")
          _     <- db.upsertOnLogin("google", "sub-collide-a", None, IO.pure("CollideNick"))
          other <- db.upsertOnLogin("google", "sub-collide-b", None, gen)
        yield assertEquals(other.nickname, "CollideSecond", "'collidenick' collides with 'CollideNick'")
      }
    }

  test("nickname updates enforce case-insensitive uniqueness but allow changing your own casing"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          a       <- db.upsertOnLogin("google", "sub-nick-a", None, IO.pure("NickHolderA"))
          b       <- db.upsertOnLogin("google", "sub-nick-b", None, IO.pure("NickHolderB"))
          taken   <- db.updateNickname(b.id, "nickholdera")
          recased <- db.updateNickname(b.id, "NICKHOLDERB")
          renamed <- db.updateNickname(b.id, "NickHolderB2")
          missing <- db.updateNickname(UUID.randomUUID().toString, "GhostNick")
          loaded  <- db.userById(b.id)
          holderA <- db.userById(a.id)
        yield
          assertEquals(taken, NicknameUpdate.Taken)
          assertEquals(recased, NicknameUpdate.Updated, "re-casing your own nickname must not self-collide")
          assertEquals(renamed, NicknameUpdate.Updated)
          assertEquals(missing, NicknameUpdate.UserNotFound)
          assertEquals(loaded.map(_.nickname), Some("NickHolderB2"))
          assertEquals(holderA.map(_.nickname), Some("NickHolderA"), "the rejected rename left account A untouched")
      }
    }

  test("a guest id is claimed exactly once — idempotent for its owner, terminal for everyone else"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          owner   <- db.upsertOnLogin("google", "sub-guest-owner", None, IO.pure("GuestOwner"))
          rival   <- db.upsertOnLogin("google", "sub-guest-rival", None, IO.pure("GuestRival"))
          guestId <- IO(UUID.randomUUID().toString)
          first   <- db.linkGuest(owner.id, guestId)
          again   <- db.linkGuest(owner.id, guestId)
          stolen  <- db.linkGuest(rival.id, guestId)
          ghost   <- db.linkGuest(UUID.randomUUID().toString, UUID.randomUUID().toString)
          linked  <- db.guestsOf(owner.id)
        yield
          assertEquals(first, GuestLink.Linked)
          assertEquals(again, GuestLink.Linked, "re-claiming your own guest id is idempotent, not an error")
          assertEquals(stolen, GuestLink.ClaimedByAnother)
          assertEquals(ghost, GuestLink.UserNotFound)
          assertEquals(linked, List(guestId))
      }
    }

  test("deleting an account cascades identities and guest links but leaves game history untouched"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          user    <- db.upsertOnLogin("google", "sub-delete", None, IO.pure("DeletedNick"))
          guestId <- IO(UUID.randomUUID().toString)
          _       <- db.linkGuest(user.id, guestId)
          gameId  <- GameId.random
          _       <- db.save(
            gameId,
            endedResultFixture(Principal.User(user.id), Principal.Bot("delete-team", "delete-bot"), rated = true)
          )
          deleted <- db.deleteUser(user.id)
          gone    <- db.userById(user.id)
          // The same Google subject signing in again gets a FRESH account (the identity row cascaded)
          // that can reuse the freed nickname — deletion must not squat names forever.
          relogin <- db.upsertOnLogin("google", "sub-delete", None, IO.pure("DeletedNick"))
          tally   <- db.resultTallyFor(Principal.User(user.id).externalId)
          reclaim <- db.linkGuest(relogin.id, guestId)
          missing <- db.deleteUser(user.id)
        yield
          assert(deleted)
          assertEquals(gone, None)
          assertNotEquals(relogin.id, user.id, "deletion severs the subject: re-login mints a new account")
          assertEquals(relogin.nickname, "DeletedNick")
          assertEquals(tally, ResultTally(1, 0, 0), "game_results keeps the orphaned user: external id")
          assertEquals(reclaim, GuestLink.Linked, "the guest link cascaded, so the id is claimable again")
          assert(!missing, "a second delete finds nothing")
      }
    }

  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
