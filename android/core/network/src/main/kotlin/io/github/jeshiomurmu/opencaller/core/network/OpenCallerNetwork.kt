package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.Clock
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Where and how to reach the server.
 *
 * @property baseUrl e.g. `http://10.0.2.2:8080/` in debug builds; `null` when the build has none (online features off).
 * @property pins `sha256/…` public-key pins (play release builds: the server's key plus a backup); empty = system
 *   trust only.
 * @property userAgent e.g. `OpenCaller/0.1.0-alpha (foss)`.
 * @property flavor the app's build flavor (`foss` or `play`), registered with each signed-in device.
 */
data class ServerConfig(
    val baseUrl: String?,
    val pins: List<String> = emptyList(),
    val userAgent: String = "OpenCaller",
    val flavor: String = "unknown",
) {
    /** The parsed base URL, or `null` when none is configured (or it doesn't parse). */
    val httpUrl: HttpUrl? get() = baseUrl?.takeIf { it.isNotBlank() }?.let { it.trimEnd('/') + "/" }?.toHttpUrlOrNull()

    /** True when online features can work. */
    val isConfigured: Boolean get() = httpUrl != null

    /** The server's own unlist page (works without the app), or `null`. */
    val unlistPageUrl: String? get() = httpUrl?.resolve("unlist")?.toString()
}

/**
 * Builds the HTTP clients and API interfaces for one [ServerConfig]: the main client (30 s timeouts, auth,
 * pinning), a lookup client limited to [lookupTimeoutMillis] per call (docs/architecture.md §4.1 step 10) and a
 * refresh client without the authenticator.
 */
class OpenCallerNetwork(
    val config: ServerConfig,
    tokens: TokenStore,
    clock: Clock,
    lookupTimeoutMillis: Long = DEFAULT_LOOKUP_TIMEOUT_MILLIS,
) {
    private val baseUrl: HttpUrl = config.httpUrl ?: UNCONFIGURED
    private val base: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(serverConfigured())
            .addInterceptor(userAgent())
            .apply { pinner()?.let(::certificatePinner) }
            .build()

    private val authApi: AuthApi = retrofit(base).create(AuthApi::class.java)

    private val authed: OkHttpClient =
        base
            .newBuilder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, authApi, clock))
            .build()

    /** The API for everything except the live lookup. */
    val api: OpenCallerApi = retrofit(authed).create(OpenCallerApi::class.java)

    /** The live lookup, cut off after the lookup timeout so a slow server never holds up a call. */
    val lookupApi: LookupApi =
        retrofit(
            authed
                .newBuilder()
                .callTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS)
                .connectTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS)
                .build(),
        ).create(LookupApi::class.java)

    private fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON))
            .build()

    // Without a configured server every request fails at once, before any socket is opened.
    private fun serverConfigured() =
        Interceptor { chain ->
            if (!config.isConfigured) throw ServerNotConfiguredException()
            chain.proceed(chain.request())
        }

    private fun userAgent() =
        Interceptor { chain ->
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .header("User-Agent", config.userAgent)
                    .build(),
            )
        }

    private fun pinner(): CertificatePinner? {
        val host = config.httpUrl?.host
        return if (host == null || config.pins.isEmpty()) {
            null
        } else {
            CertificatePinner
                .Builder()
                .apply { config.pins.forEach { add(host, it) } }
                .build()
        }
    }

    /** Defaults. */
    companion object {
        /** docs/architecture.md §4.1 step 10. */
        const val DEFAULT_LOOKUP_TIMEOUT_MILLIS = 800L
        private const val TIMEOUT_SECONDS = 30L
        private val JSON = "application/json".toMediaType()

        // Retrofit needs some base URL even when none is configured; requests never reach it (serverConfigured()).
        private val UNCONFIGURED =
            HttpUrl
                .Builder()
                .scheme("http")
                .host("server.invalid")
                .build()

        /** JSON as the server writes it; unknown fields are ignored so newer servers work with older apps. */
        val json: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
