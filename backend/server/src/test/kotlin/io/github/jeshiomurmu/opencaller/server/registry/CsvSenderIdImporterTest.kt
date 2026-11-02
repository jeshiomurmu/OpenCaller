package io.github.jeshiomurmu.opencaller.server.registry

import io.github.jeshiomurmu.opencaller.server.db.ImportRuns
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CSV importer, the import folder and the import job's records. */
class CsvSenderIdImporterTest {
    private val server = TestServer()

    private fun importer(complete: Boolean = true) =
        CsvSenderIdImporter("csv-bd-test", "BD", server.deps.senderIdStore, complete) { error("not fetched in map tests") }

    @Test
    fun linesAreParsedWithQuotesAndBadRowsAreReportedNotGuessed() {
        assertEquals(listOf("a", "b, c", "say \"hi\""), CsvSenderIdImporter.parseLine("a,\"b, c\",\"say \"\"hi\"\"\""))
        assertEquals(null, CsvSenderIdImporter.parseLine("a,\"open"))
        val batch =
            importer().map(
                "# exported 2026-11-01\nheader,brand_name,category\nEXBANK,Example Bank,bank\n" +
                    "EXBANK,Again,bank\nbad header,X,bank\nEXTEL,Example Tel,Telecom\nEXSHOP,,retail\n",
            )
        assertEquals(listOf(SenderIdRecord("BD", "EXBANK", "Example Bank", "bank")), batch.records)
        assertEquals(
            listOf("line 4: duplicate header EXBANK", "line 5: invalid row", "line 6: invalid row", "line 7: invalid row"),
            batch.rejected,
        )
        // Skipped rows make a "complete" file untrustworthy for removals.
        assertFalse(batch.complete)
        assertTrue(importer().map("header,brand_name,category\nEXBANK,Example Bank,bank\n").complete)
        assertEquals(listOf("line 1: the first line must be header,brand_name,category"), importer().map("a,b,c\n").rejected)
    }

    @Test
    fun theImportFolderGivesOneImporterPerSourceAndCountryAndTheJobRecordsEveryRun() =
        runBlocking {
            server.reset()
            val dir = Files.createTempDirectory("imports")
            dir
                .resolve(
                    "csv-bd-test",
                ).createDirectories()
                .resolve("BD.csv")
                .writeText("header,brand_name,category\nEXBANK,Example Bank,bank\n")
            dir.resolve("csv-bd-test").resolve("notes.txt").writeText("ignored")
            dir.resolve("csv-bd-test").resolve("bad.csv").writeText("ignored: not a country")
            dir
                .resolve("Bad Source")
                .createDirectories()
                .resolve("BD.csv")
                .writeText("ignored")
            val importers = csvImportersIn(dir.toString(), server.deps.senderIdStore)
            assertEquals(listOf("csv-bd-test" to "BD"), importers.map { it.source to it.countryIso })
            assertTrue(csvImportersIn(null, server.deps.senderIdStore).isEmpty())
            assertTrue(csvImportersIn(dir.resolve("missing").toString(), server.deps.senderIdStore).isEmpty())

            val job = SenderIdImportJob({ importers + placeholderRegistries }, server.deps.importRuns, server.clock)
            assertEquals(ImportJobResult(ok = 1, failed = 0, unavailable = 4), job.run())
            assertEquals(
                listOf("EXBANK"),
                server.deps.sync
                    .senderIds("BD", null)
                    .senderIds
                    .map { it.header },
            )
            val runs = TestInfra.database.query { ImportRuns.selectAll().map { it[ImportRuns.sourceName] to it[ImportRuns.status] } }
            assertEquals(
                setOf(
                    "csv-bd-test" to "ok",
                    "dlt-in" to "unavailable",
                    "ssir-sg" to "unavailable",
                    "acma-au" to "unavailable",
                    "comreg-ie" to "unavailable",
                ),
                runs.toSet(),
            )
            // Running again changes nothing that apps would download.
            val synced =
                server.deps.sync
                    .senderIds("BD", null)
                    .syncedAt
            server.clock.advance(kotlin.time.Duration.parse("1m"))
            job.run()
            assertTrue(
                server.deps.sync
                    .senderIds("BD", synced.toString())
                    .senderIds
                    .isEmpty(),
            )
        }
}
