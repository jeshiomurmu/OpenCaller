package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/**
 * Runs one API call and maps the outcome at the boundary (docs/rules.md §5): `IOException` → [AppError.Network],
 * 401 → [AppError.Auth], 429 → [AppError.RateLimited] with `Retry-After`, 404 → [AppError.NotFound], other 4xx →
 * [AppError.Validation] carrying the problem code, anything else → [AppError.Unexpected]. Never throws, except to
 * propagate cancellation.
 */
suspend fun <T : Any> apiCall(call: suspend () -> Response<T>): AppResult<T> =
    try {
        val response = call()
        val body = response.body()
        when {
            response.isSuccessful && body != null -> AppResult.Success(body)
            response.isSuccessful -> AppResult.Failure(AppError.Unexpected(IllegalStateException("empty body")))
            else -> AppResult.Failure(errorOf(response))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServerNotConfiguredException) {
        NetworkLog.warn("No server configured", e)
        AppResult.Failure(AppError.ServerNotConfigured)
    } catch (e: IOException) {
        NetworkLog.warn("Request failed", e)
        AppResult.Failure(AppError.Network)
    } catch (e: SerializationException) {
        AppResult.Failure(AppError.Unexpected(e))
    }

/** The [AppError] for an unsuccessful [response]. */
fun errorOf(response: Response<*>): AppError =
    when (response.code()) {
        HTTP_UNAUTHORIZED -> AppError.Auth
        HTTP_NOT_FOUND -> AppError.NotFound
        HTTP_TOO_MANY_REQUESTS -> AppError.RateLimited(response.headers()["Retry-After"]?.toLongOrNull())
        in HTTP_CLIENT_ERRORS -> AppError.Validation(problemCode(response) ?: "http_${response.code()}")
        else -> AppError.Unexpected(IOException("HTTP ${response.code()}"))
    }

/** The problem+json `code` of an error [response], if it has one. */
fun problemCode(response: Response<*>): String? =
    try {
        response.errorBody()?.string()?.let { problemJson.decodeFromString(ProblemDto.serializer(), it).code }
    } catch (e: SerializationException) {
        NetworkLog.warn("Unreadable error body", e)
        null
    } catch (e: IOException) {
        NetworkLog.warn("Error body could not be read", e)
        null
    }

/** Thrown for any request when the build has no server address. */
class ServerNotConfiguredException : IOException("No server is configured in this build")

private val problemJson = Json { ignoreUnknownKeys = true }
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_CLIENT_ERRORS = 400..499
