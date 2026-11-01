package io.github.jeshiomurmu.opencaller.server.storage

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3 requests (the scheme MinIO implements): canonical request → string to sign →
 * HMAC-SHA256 with a key derived from the secret, date, region and service. [sign] signs a request the server
 * sends itself (header form, three signed headers); [presign] makes a URL an app can use on its own (query form).
 * Tests compare both with botocore (AWS's Python SDK).
 */
class SigV4Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
) {
    /** The headers to add to a request: `x-amz-date`, `x-amz-content-sha256` and `Authorization`. */
    fun sign(
        method: String,
        uri: URI,
        payload: ByteArray,
        at: Instant,
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(at)
        val day = amzDate.substring(0, DAY_LENGTH)
        val payloadHash = sha256Hex(payload)
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        val canonicalRequest =
            listOf(
                method,
                canonicalPath(uri.rawPath),
                canonicalQuery(uri.rawQuery),
                "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n",
                SIGNED_HEADERS,
                payloadHash,
            ).joinToString("\n")
        val scope = "$day/$region/$SERVICE/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val signature = hmac(signingKey(day), stringToSign).toHexString()
        return mapOf(
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature",
        )
    }

    /**
     * A presigned URL (query-string authentication): anyone holding it may send [method] to [uri] until
     * [expiresSeconds] after [at], without credentials. Signed headers are `host` plus [headers] (e.g.
     * `content-type`, which the client must then send with exactly that value); the payload is not signed.
     */
    fun presign(
        method: String,
        uri: URI,
        at: Instant,
        expiresSeconds: Int,
        headers: Map<String, String> = emptyMap(),
    ): URI {
        val amzDate = AMZ_DATE.format(at)
        val day = amzDate.substring(0, DAY_LENGTH)
        val scope = "$day/$region/$SERVICE/aws4_request"
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        val signed = (headers.mapKeys { it.key.lowercase() } + ("host" to host)).toSortedMap()
        val signedHeaders = signed.keys.joinToString(";")
        val query =
            listOf(
                "X-Amz-Algorithm" to ALGORITHM,
                "X-Amz-Credential" to "$accessKey/$scope",
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to signedHeaders,
            ).joinToString("&") { (name, value) -> "$name=${uriEncode(value, keepSlash = false)}" }
        val canonicalRequest =
            listOf(
                method,
                canonicalPath(uri.rawPath),
                canonicalQuery(query),
                signed.entries.joinToString("") { (name, value) -> "$name:${value.trim()}\n" },
                signedHeaders,
                UNSIGNED_PAYLOAD,
            ).joinToString("\n")
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val signature = hmac(signingKey(day), stringToSign).toHexString()
        val base = uri.toString().substringBefore('?')
        return URI.create("$base?$query&X-Amz-Signature=$signature")
    }

    private fun signingKey(day: String): ByteArray =
        listOf(day, region, SERVICE, "aws4_request")
            .fold("AWS4$secretKey".toByteArray(Charsets.UTF_8)) { key, part -> hmac(key, part) }

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(data.toByteArray(Charsets.UTF_8))
        }

    /** URI encoding as S3 expects it. */
    companion object {
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val SERVICE = "s3"
        private const val HMAC = "HmacSHA256"
        private const val SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date"
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        private const val DAY_LENGTH = 8
        private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** Hex SHA-256 of [bytes]. */
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

        /** Percent-encodes every byte except the unreserved characters `A-Z a-z 0-9 - . _ ~` (and `/` if [keepSlash]). */
        fun uriEncode(
            value: String,
            keepSlash: Boolean,
        ): String =
            buildString {
                value.toByteArray(Charsets.UTF_8).forEach { byte ->
                    val c = (byte.toInt() and BYTE_MASK).toChar()
                    when {
                        c.isUnreserved() || (keepSlash && c == '/') -> append(c)
                        else -> append('%').append("%02X".format(byte.toInt() and BYTE_MASK))
                    }
                }
            }

        private const val BYTE_MASK = 0xFF

        private fun Char.isUnreserved() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

        // The path arrives already encoded by [uriEncode]; S3 doesn't normalise or double-encode it.
        private fun canonicalPath(rawPath: String?) = rawPath?.ifEmpty { "/" } ?: "/"

        private fun canonicalQuery(rawQuery: String?): String =
            rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                ?.sortedWith(compareBy({ it.first }, { it.second }))
                ?.joinToString("&") { (name, value) -> "$name=$value" }
                .orEmpty()
    }
}
