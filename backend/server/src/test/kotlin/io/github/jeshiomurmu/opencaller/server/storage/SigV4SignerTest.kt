package io.github.jeshiomurmu.opencaller.server.storage

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Our Signature V4 matches botocore's (AWS's Python SDK) on the vectors in fixtures/sigv4_vectors.csv. */
class SigV4SignerTest {
    private val parseDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    @Test
    fun matchesBotocoreVectors() {
        val vectors =
            javaClass
                .getResource("/fixtures/sigv4_vectors.csv")!!
                .readText()
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(',') }
        assertEquals(4, vectors.size)
        vectors.forEach { row ->
            val (method, url, payload) = row
            val (date, region, signature) = row.drop(3)
            val headers =
                SigV4Signer("test-access-key", "test-secret-key/not+real", region)
                    .sign(method, URI.create(url), payload.toByteArray(), Instant.from(parseDate.parse(date)))
            assertEquals(date, headers["x-amz-date"])
            assertEquals(SigV4Signer.sha256Hex(payload.toByteArray()), headers["x-amz-content-sha256"])
            assertEquals(
                "AWS4-HMAC-SHA256 Credential=test-access-key/${date.take(8)}/$region/s3/aws4_request, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature",
                headers["Authorization"],
                "$method $url",
            )
        }
    }

    @Test
    fun encodesKeysLikeS3() {
        assertEquals("lists/BD/3/full.ocsl", SigV4Signer.uriEncode("lists/BD/3/full.ocsl", keepSlash = true))
        assertEquals("a%20b/c%2Bd~e_f.g-h", SigV4Signer.uriEncode("a b/c+d~e_f.g-h", keepSlash = true))
        assertEquals("a%2Fb", SigV4Signer.uriEncode("a/b", keepSlash = false))
        assertTrue(SigV4Signer.uriEncode("ü", keepSlash = true) == "%C3%BC")
    }
}
