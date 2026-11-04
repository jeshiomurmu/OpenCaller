package io.github.jeshiomurmu.opencaller.server.video

import io.github.jeshiomurmu.opencaller.server.config.VideoConfig
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.lookup.BucketResponse
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.github.jeshiomurmu.opencaller.server.testing.updateProfile
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Video caller ID: pre-signed upload, the transcoder worker with the real ffmpeg, the clip id in bucket entries,
 * download links and reports (ffmpeg must be on the PATH, as in the transcoder image and CI).
 */
class VideoCallerIdTest {
    private val store = InMemoryObjectStore()
    private val worker = { server: TestServer ->
        TranscodeWorker(TestInfra.database, store, FfmpegTranscoder(VideoConfig(), Dispatchers.IO), server.clock)
    }

    private suspend fun <T> HttpResponse.body(serializer: KSerializer<T>): T {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return testJson.decodeFromString(serializer, bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.uploadAndSubmit(
        token: String,
        clip: ByteArray,
    ): VideoUpload {
        val upload = client.postJson("/v1/me/video/upload", "", bearer(token)).body(VideoUpload.serializer())
        store.upload(upload.key, clip, "video/mp4")
        val queued = client.postJson("/v1/me/video", """{"key":"${upload.key}"}""", bearer(token)).body(MyVideo.serializer())
        assertEquals("queued", queued.job)
        return upload
    }

    private suspend fun ApplicationTestBuilder.bucketVideo(token: String? = null): String? =
        client
            .getWith("/v1/lookup/buckets/880/25856", token)
            .body(BucketResponse.serializer())
            .entries
            .singleOrNull()
            ?.info
            ?.videoId

    // A 12 s, 720p clip with sound: the worker must cut it to 10 s, scale it to 480 px and drop the sound.
    private fun recording(): ByteArray {
        val dir = Files.createTempDirectory("clip-test")
        val file = dir.resolve("in.mp4")
        ffmpeg(
            "-f",
            "lavfi",
            "-i",
            "testsrc=duration=12:size=1280x720:rate=30",
            "-f",
            "lavfi",
            "-i",
            "sine=duration=12",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-c:a",
            "aac",
            "-shortest",
            file.toString(),
        )
        return Files.readAllBytes(file).also { dir.toFile().deleteRecursively() }
    }

    private fun ffmpeg(vararg args: String) {
        val process = ProcessBuilder(listOf("ffmpeg", "-y", "-loglevel", "error") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, output)
    }

    private fun probe(bytes: ByteArray): String {
        val file = Files.createTempFile("clip", ".mp4")
        Files.write(file, bytes)
        val process =
            ProcessBuilder(
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "stream=codec_type,codec_name,width,height:format=duration",
                "-of",
                "default=noprint_wrappers=1",
                file.toString(),
            ).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also {
            process.waitFor(30, TimeUnit.SECONDS)
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun anUploadIsTranscodedPublishedAndShownInBuckets() =
        apiTest(TestServer(objectStore = store)) { server ->
            val alice = signIn(server, ALICE).accessToken
            updateProfile(ALICE, visibility = Visibility.EVERYONE)
            val upload = uploadAndSubmit(alice, recording())
            assertTrue(upload.key.startsWith(VideoCallerIdService.UPLOAD_PREFIX))
            assertTrue(upload.uploadUrl.contains("put&type=video/mp4"), upload.uploadUrl)
            assertNull(bucketVideo()) // nothing published yet

            assertEquals(TranscodeOutcome.PUBLISHED, worker(server).runOnce())
            assertEquals(TranscodeOutcome.IDLE, worker(server).runOnce())
            val mine = client.getWith("/v1/me/video", alice).body(MyVideo.serializer())
            assertEquals("none", mine.job)
            val clip = assertNotNull(mine.clip)
            assertNull(store.objects[upload.key]) // the upload is deleted
            val published = assertNotNull(store.objects["${VideoCallerIdService.CLIP_PREFIX}${clip.videoId}.mp4"])
            assertEquals(published.size, clip.bytes)
            val info = probe(published)
            assertTrue("codec_name=h264" in info && "width=480" in info && "codec_type=audio" !in info, info)
            val duration = Regex("duration=([0-9.]+)").find(info)!!.groupValues[1].toDouble()
            assertTrue(duration <= 10.1, info)

            assertEquals(clip.videoId, bucketVideo())
            val links =
                client
                    .postJson("/v1/videos/links", """{"ids":["${clip.videoId}","${UUID.randomUUID()}","nope"]}""")
                    .body(VideoLinks.serializer())
            assertEquals(listOf(clip.videoId), links.links.map { it.id })

            // Same visibility as the name: registered users only, then nobody.
            updateProfile(ALICE, visibility = Visibility.REGISTERED_ONLY)
            assertNull(bucketVideo())
            assertEquals(clip.videoId, bucketVideo(alice))
            updateProfile(ALICE, visibility = Visibility.NOBODY)
            assertNull(bucketVideo(alice))
        }

    @Test
    fun aNewClipReplacesTheOldOneOnlyOnceItIsReady() =
        apiTest(TestServer(objectStore = store)) { server ->
            val alice = signIn(server, ALICE).accessToken
            uploadAndSubmit(alice, recording())
            worker(server).runOnce()
            val first = assertNotNull(client.getWith("/v1/me/video", alice).body(MyVideo.serializer()).clip)
            uploadAndSubmit(alice, "not a video".toByteArray())
            assertEquals(
                first.videoId,
                client
                    .getWith("/v1/me/video", alice)
                    .body(MyVideo.serializer())
                    .clip
                    ?.videoId,
            )

            assertEquals(TranscodeOutcome.FAILED, worker(server).runOnce())
            val failed = client.getWith("/v1/me/video", alice).body(MyVideo.serializer())
            assertEquals("failed", failed.job)
            assertNotNull(failed.error)
            assertEquals(first.videoId, failed.clip?.videoId) // the old clip stays

            uploadAndSubmit(alice, recording())
            assertEquals(TranscodeOutcome.PUBLISHED, worker(server).runOnce())
            val second = assertNotNull(client.getWith("/v1/me/video", alice).body(MyVideo.serializer()).clip)
            assertNotEquals(first.videoId, second.videoId)
            assertEquals(listOf("${VideoCallerIdService.CLIP_PREFIX}${second.videoId}.mp4"), store.objects.keys.toList())

            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/me/video", alice).status)
            assertTrue(store.objects.isEmpty())
            assertNull(client.getWith("/v1/me/video", alice).body(MyVideo.serializer()).clip)
        }

    @Test
    fun submitsAreChecked() =
        apiTest(TestServer(objectStore = store)) { server ->
            val alice = signIn(server, ALICE).accessToken
            client
                .postJson(
                    "/v1/me/video",
                    """{"key":"video-uploads/x.mp4"}""",
                    bearer(alice),
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_video")
            val upload = client.postJson("/v1/me/video/upload", "", bearer(alice)).body(VideoUpload.serializer())
            // Not uploaded yet, then the wrong type.
            client
                .postJson(
                    "/v1/me/video",
                    """{"key":"${upload.key}"}""",
                    bearer(alice),
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_video")
            store.upload(upload.key, ByteArray(10), "image/png")
            client
                .postJson(
                    "/v1/me/video",
                    """{"key":"${upload.key}"}""",
                    bearer(alice),
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_video")
            client.postJson("/v1/videos/links", """{"ids":[]}""").assertProblem(HttpStatusCode.BadRequest, "invalid_video")
        }

    @Test
    fun threeReportsHideAClip() =
        apiTest(TestServer(objectStore = store)) { server ->
            val alice = signIn(server, ALICE).accessToken
            updateProfile(ALICE, visibility = Visibility.EVERYONE)
            uploadAndSubmit(alice, recording())
            worker(server).runOnce()
            val videoId = assertNotNull(client.getWith("/v1/me/video", alice).body(MyVideo.serializer()).clip).videoId
            client.postJson("/v1/videos/$videoId/report", "", bearer(alice)).assertProblem(HttpStatusCode.BadRequest, "invalid_video")
            client
                .postJson("/v1/videos/${UUID.randomUUID()}/report", "", bearer(alice))
                .assertProblem(HttpStatusCode.NotFound, "video_not_found")

            val reporters = listOf("+8801812345678", "+8801912345678", "+8801612345678").map { signIn(server, it).accessToken }
            reporters.take(2).forEach { token ->
                assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/videos/$videoId/report", "", bearer(token)).status)
                assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/videos/$videoId/report", "", bearer(token)).status)
            }
            assertEquals(videoId, bucketVideo()) // two reporters, reporting twice, are still two
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/videos/$videoId/report", "", bearer(reporters[2])).status)
            assertNull(bucketVideo())
            assertTrue(
                client
                    .postJson("/v1/videos/links", """{"ids":["$videoId"]}""")
                    .body(VideoLinks.serializer())
                    .links
                    .isEmpty(),
            )
            assertEquals(
                true,
                client
                    .getWith("/v1/me/video", alice)
                    .body(MyVideo.serializer())
                    .clip
                    ?.hidden,
            )
        }

    private companion object {
        const val ALICE = "+8801712345678"
    }
}
