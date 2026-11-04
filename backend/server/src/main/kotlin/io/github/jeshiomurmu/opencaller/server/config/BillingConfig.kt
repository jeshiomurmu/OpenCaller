package io.github.jeshiomurmu.opencaller.server.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Supporter tier (docs/prd.md F-UT-08). [selfHosted] unlocks every supporter feature for every user of this server
 * (a self-hosted server has nobody to pay; badges still come from purchases only). [googlePlay] verifies Play
 * subscriptions; without it `POST /v1/billing/verify` answers 503.
 */
data class BillingConfig(
    val selfHosted: Boolean = false,
    val googlePlay: GooglePlayConfig? = null,
)

/**
 * Google Play Developer API access for one app ([packageName]) and its supporter subscription ([productId], with
 * base plans whose IDs start with `supporter`, `gold` or `family`). [accountSecret] keys the HMAC that turns a user
 * ID into the `obfuscatedAccountId` the app passes to Play, so a purchase is bound to the account that made it.
 */
data class GooglePlayConfig(
    val packageName: String,
    val productId: String,
    val serviceAccount: ServiceAccountKey,
    val accountSecret: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    override fun toString() =
        "GooglePlayConfig(packageName=$packageName, productId=$productId, serviceAccount=$serviceAccount, " +
            "accountSecret=***, apiBaseUrl=$apiBaseUrl)"

    /** Google's API host. */
    companion object {
        /** Production Play Developer API. */
        const val DEFAULT_API_BASE_URL = "https://androidpublisher.googleapis.com"

        /** Default subscription product ID; the Play Console product must use the same ID. */
        const val DEFAULT_PRODUCT_ID = "opencaller_supporter"
    }
}

/** A Google service account's key (the JSON file downloaded from Google Cloud), parsed. */
class ServiceAccountKey(
    val clientEmail: String,
    val privateKeyId: String,
    val privateKey: RSAPrivateKey,
    val tokenUri: String,
) {
    override fun toString() = "ServiceAccountKey(clientEmail=$clientEmail, privateKeyId=***, privateKey=***, tokenUri=$tokenUri)"

    /** Reading the key file. */
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a service-account key file; throws [IllegalArgumentException] naming what is wrong (never the key). */
        fun read(path: Path): ServiceAccountKey {
            val text =
                try {
                    Files.readString(path)
                } catch (e: IOException) {
                    throw IllegalArgumentException("can't be read (${e.javaClass.simpleName})", e)
                }
            return parse(text)
        }

        /** Parses the JSON of a service-account key. */
        fun parse(text: String): ServiceAccountKey {
            val file =
                try {
                    json.decodeFromString(KeyFile.serializer(), text)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("is not a service-account key file", e)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("is not a service-account key file", e)
                }
            require(file.type == "service_account") { "is not a service-account key (type ${file.type})" }
            require(file.clientEmail.isNotBlank() && file.privateKeyId.isNotBlank()) { "has no client_email or private_key_id" }
            require(file.tokenUri.startsWith("https://") || file.tokenUri.startsWith("http://")) { "has no valid token_uri" }
            return ServiceAccountKey(file.clientEmail, file.privateKeyId, rsaKey(file.privateKey), file.tokenUri)
        }

        private fun rsaKey(pem: String): RSAPrivateKey {
            val base64 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            return try {
                val der = Base64.getDecoder().decode(base64)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("has an unreadable private_key", e)
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("has an unreadable private_key", e)
            }
        }
    }

    @Serializable
    private data class KeyFile(
        val type: String = "",
        @kotlinx.serialization.SerialName("client_email") val clientEmail: String = "",
        @kotlinx.serialization.SerialName("private_key_id") val privateKeyId: String = "",
        @kotlinx.serialization.SerialName("private_key") val privateKey: String = "",
        @kotlinx.serialization.SerialName("token_uri") val tokenUri: String = "",
    )
}
