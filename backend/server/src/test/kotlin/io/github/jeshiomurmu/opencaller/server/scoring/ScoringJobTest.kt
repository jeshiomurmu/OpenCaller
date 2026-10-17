package io.github.jeshiomurmu.opencaller.server.scoring

import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.UnlistRequests
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.insertBusiness
import io.github.jeshiomurmu.opencaller.server.testing.insertNotSpamReports
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** The scoring job against PostgreSQL: rescoring, verified-business cap (TC-API-006), decay, indexing, unlist. */
class ScoringJobTest {
    private val spammer = "+8801712345678"
    private val bank = "+8801812345678"

    private fun withServer(block: suspend (TestServer) -> Unit) =
        runBlocking {
            val server = TestServer()
            server.reset()
            block(server)
        }

    private suspend fun reputation(e164: String) =
        TestInfra.database.query { NumberReputation.selectAll().where { NumberReputation.e164 eq e164 }.singleOrNull() }

    @Test
    fun reportedNumbersAreScoredWithTheSection9Formula() =
        withServer { server ->
            insertReports(spammer, 20, trust = 1.0f, at = server.clock.now, category = "loan")
            insertNotSpamReports(spammer, 2, trust = 0.5f, at = server.clock.now)
            val result = server.deps.scoringJob.run()
            assertEquals(1, result.rescored)
            val row = assertNotNull(reputation(spammer))
            // (1 + 20) / (3 + 20 + 1)
            assertEquals(21.0 / 24.0, row[NumberReputation.spamScore].toDouble(), 1e-6)
            assertEquals(20, row[NumberReputation.reportCount])
            assertEquals(2, row[NumberReputation.notSpamCount])
            assertEquals("loan", row[NumberReputation.category])
            assertEquals(880, row[NumberReputation.countryCode].toInt())
            // 16-bit bucket of +8801712345678 in fixtures/bucket_vectors.csv.
            assertEquals(25856, row[NumberReputation.bucket16])
            assertEquals(ScoringJob.MODEL_VERSION, row[NumberReputation.modelVersion])
        }

    @Test
    fun laterReportsArePickedUpByTheNextRun() =
        withServer { server ->
            insertReports(spammer, 1, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            assertEquals(2.0 / 4.0, reputation(spammer)!![NumberReputation.spamScore].toDouble(), 1e-6)

            server.clock.advance(15.minutes)
            insertReports(spammer, 1, trust = 1.0f, at = server.clock.now)
            assertEquals(
                1,
                server.deps.scoringJob
                    .run()
                    .rescored,
            )
            // The first report is 15 minutes older now: weight 2^(-15 min / 30 d).
            val first = 0.5.pow(15.0 / (30 * 24 * 60))
            assertEquals((2 + first) / (4 + first), reputation(spammer)!![NumberReputation.spamScore].toDouble(), 1e-6)
        }

    @Test
    fun scoresDecayWhenNoNewReportsArrive() =
        withServer { server ->
            insertReports(spammer, 1, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            server.clock.advance(31.days)
            assertEquals(
                1,
                server.deps.scoringJob
                    .run()
                    .rescored,
            )
            val weight = 0.5.pow(31.0 / 30.0)
            assertEquals((1 + weight) / (3 + weight), reputation(spammer)!![NumberReputation.spamScore].toDouble(), 1e-6)
        }

    // TC-API-006
    @Test
    fun verifiedBusinessesAreCappedAndQueuedForReviewOnce() =
        withServer { server ->
            insertBusiness(bank, "Example Bank")
            insertReports(bank, 30, trust = 1.0f, at = server.clock.now)
            val first = server.deps.scoringJob.run()
            assertEquals(1, first.capped)
            assertEquals(1, first.disputesOpened)
            val row = reputation(bank)!!
            assertEquals(0.39, row[NumberReputation.spamScore].toDouble(), 1e-6)
            assertNull(SpamLabels.of(row[NumberReputation.spamScore].toDouble()))
            assertEquals(30, row[NumberReputation.reportCount])

            server.clock.advance(15.minutes)
            insertReports(bank, 5, trust = 1.0f, at = server.clock.now)
            val second = server.deps.scoringJob.run()
            assertEquals(1, second.capped)
            assertEquals(0, second.disputesOpened)
            val disputes = TestInfra.database.query { Disputes.selectAll().where { Disputes.e164 eq bank }.toList() }
            assertEquals(1, disputes.size)
            assertEquals(ReviewStatus.PENDING, disputes.single()[Disputes.status])
            assertEquals(ScoringJob.AUTO_DISPUTE_REASON, disputes.single()[Disputes.reason])
        }

    @Test
    fun businessesAwaitingVerificationAreScoredLikeAnyNumber() =
        withServer { server ->
            insertBusiness(bank, "Unverified Bank", status = ReviewStatus.PENDING)
            insertReports(bank, 30, trust = 1.0f, at = server.clock.now)
            assertEquals(
                0,
                server.deps.scoringJob
                    .run()
                    .capped,
            )
            assertEquals(31.0 / 33.0, reputation(bank)!![NumberReputation.spamScore].toDouble(), 1e-6)
        }

    @Test
    fun usersMissingFromTheIndexAreAdded() =
        withServer { server ->
            TestInfra.database.query {
                Users.insert {
                    it[id] = UUID.randomUUID()
                    it[e164] = "+918123456789"
                    it[verifiedAt] = OffsetDateTime.now(server.clock).withOffsetSameInstant(ZoneOffset.UTC)
                }
            }
            assertNull(reputation("+918123456789"))
            assertEquals(
                1,
                server.deps.scoringJob
                    .run()
                    .indexed,
            )
            val row = assertNotNull(reputation("+918123456789"))
            assertEquals(91, row[NumberReputation.countryCode].toInt())
            assertEquals(46761, row[NumberReputation.bucket16])
            assertEquals(
                0,
                server.deps.scoringJob
                    .run()
                    .indexed,
            )
        }

    @Test
    fun verifiedUnlistRequestsAreProcessed() =
        withServer { server ->
            TestInfra.database.query {
                UnlistRequests.insert {
                    it[e164] = spammer
                    it[verifiedAt] = OffsetDateTime.now(server.clock).withOffsetSameInstant(ZoneOffset.UTC)
                }
                // Never verified: stays unprocessed.
                UnlistRequests.insert { it[e164] = bank }
            }
            assertEquals(
                1,
                server.deps.scoringJob
                    .run()
                    .unlistProcessed,
            )
            val rows =
                TestInfra.database.query {
                    UnlistRequests.selectAll().associate {
                        it[UnlistRequests.e164] to
                            it[UnlistRequests.processedAt]
                    }
                }
            assertNotNull(rows[spammer])
            assertNull(rows[bank])
            assertEquals(
                0,
                server.deps.scoringJob
                    .run()
                    .unlistProcessed,
            )
        }
}
