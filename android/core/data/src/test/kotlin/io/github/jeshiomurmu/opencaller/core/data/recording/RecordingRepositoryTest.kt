package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Recordings on the phone: the line, saving with confirmation (TC-REC-001), transcripts and delete. */
class RecordingRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val folder = Files.createTempDirectory("recordings").toFile()
    private val store = MapStore()
    private val tokens =
        object : TokenStore {
            override suspend fun read() = AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE)

            override suspend fun write(tokens: AuthTokens) = Unit

            override suspend fun clear() = Unit
        }
    private val api = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, FakeClock()).recordingApi
    private val repository = RecordingRepository(api, store, folder, FakeClock(), Direct)

    @After
    fun tearDown() {
        server.close()
        folder.deleteRecursively()
    }

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private val list =
        """[{"id":"$ID","createdAt":"2026-11-10T10:00:00Z","durationMs":61000,"sizeBytes":4,"transcript":"off",""" +
            """"downloaded":false,"deleteBy":"2026-11-11T10:00:00Z"}]"""

    @Test
    fun theLineAndTheSessionComeFromTheServer() =
        runBlocking {
            server.enqueue(json("""{"sessionId":"$ID","number":"+8809638000000","code":"12345678","expiresAt":"2026-11-10T10:10:00Z"}"""))
            val line = (repository.startLine() as AppResult.Success).value
            assertEquals(PhoneNumber("+8809638000000"), line.number)
            assertEquals("12345678", line.code)
            assertEquals("/v1/lines/recording", server.takeRequest().url.encodedPath)

            server.enqueue(json("""{"sessionId":"$ID","state":"active"}"""))
            assertEquals(RecordingSessionState.ACTIVE, (repository.session(ID) as AppResult.Success).value.state)
            // A garbled line is refused rather than dialled.
            server.enqueue(json("""{"sessionId":"$ID","number":"*123#","code":"12345678","expiresAt":"2026-11-10T10:10:00Z"}"""))
            assertEquals(AppResult.Failure(AppError.Validation(RecordingRepository.INVALID_LINE)), repository.startLine())
        }

    @Test
    fun tcRec001TheFileIsSavedThenTheServerIsTold() =
        runBlocking {
            server.enqueue(json(list))
            server.enqueue(
                json(
                    """{"url":"${server.url("/files/r.ogg")}","contentType":"audio/ogg","sizeBytes":4,"expiresInSeconds":900}""",
                ),
            )
            server.enqueue(json(list))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("OggS")
                    .build(),
            )
            server.enqueue(noContent())

            assertEquals(AppResult.Success(1), repository.syncAll())
            val recorded = List(5) { server.takeRequest() }
            val requests = recorded.map { "${it.method} ${it.url.encodedPath}" }
            assertEquals(
                listOf(
                    "GET /v1/recordings",
                    "GET /v1/recordings/$ID/download",
                    "GET /v1/recordings",
                    "GET /files/r.ogg",
                    "POST /v1/recordings/$ID/downloaded",
                ),
                requests,
            )
            val saved = store.rows.getValue(ID)
            assertEquals("OggS", java.io.File(saved.filePath).readText())
            assertEquals(61_000L, saved.durationMs)
            // The presigned link is fetched without the access token.
            assertNull(recorded[3].headers["Authorization"])
            assertEquals("Bearer access", recorded[4].headers["Authorization"])
        }

    @Test
    fun aFailedDownloadKeepsTheServerCopy() =
        runBlocking {
            server.enqueue(
                json("""{"url":"${server.url("/files/r.ogg")}","contentType":"audio/ogg","sizeBytes":4,"expiresInSeconds":900}"""),
            )
            server.enqueue(json(list))
            server.enqueue(MockResponse.Builder().code(403).build())
            assertEquals(AppResult.Failure(AppError.Network), repository.save(ID, null))
            assertTrue(store.rows.isEmpty())
            assertEquals(3, server.requestCount) // no confirmation, so the server keeps the file for a retry
        }

    @Test
    fun deletingRemovesTheFileAndTheServerCopy() =
        runBlocking {
            val file = java.io.File(folder, "$ID.ogg").apply { writeText("OggS") }
            store.rows[ID] = RecordingEntity(ID, null, file.absolutePath, 1_000, null, null, 0)
            server.enqueue(MockResponse.Builder().code(404).build()) // already deleted there
            assertEquals(AppResult.Success(Unit), repository.delete(ID))
            assertTrue(!file.exists() && store.rows.isEmpty())
            assertEquals("DELETE /v1/recordings/$ID", server.takeRequest().let { "${it.method} ${it.url.encodedPath}" })
        }

    private class MapStore : RecordingStore {
        val rows = linkedMapOf<String, RecordingEntity>()
        override val all = MutableStateFlow<List<RecordingEntity>>(emptyList())

        override suspend fun find(id: String) = rows[id]

        override suspend fun put(recording: RecordingEntity): Boolean {
            rows[recording.id] = recording
            return true
        }

        override suspend fun remove(id: String) {
            rows.remove(id)
        }
    }

    private object Direct : DispatcherProvider {
        override val main get() = Dispatchers.Unconfined
        override val io get() = Dispatchers.IO
        override val default get() = Dispatchers.Default
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
