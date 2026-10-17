package io.github.jeshiomurmu.opencaller.core.common

/**
 * Domain error returned inside [AppResult.Failure]. Repositories map exceptions to these at the boundary
 * (docs/rules.md §5); the UI maps each one to a message and an action.
 */
sealed interface AppError {
    /** The network was unreachable or the request failed to complete (`IOException`). */
    data object Network : AppError

    /** The server rejected the credentials (HTTP 401); the user must sign in again. */
    data object Auth : AppError

    /** The server rate-limited the request (HTTP 429). [retryAfterSeconds] is `null` when the server gave no hint. */
    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : AppError

    /** The requested item does not exist. */
    data object NotFound : AppError

    /** Input was rejected before or by the server; [message] is for logs, not for display. */
    data class Validation(
        val message: String,
    ) : AppError

    /** This build has no server address (e.g. the alpha release), so online features are unavailable. */
    data object ServerNotConfigured : AppError

    /** Local storage (database, DataStore, files) failed. */
    data object Storage : AppError

    /** Anything not covered above. Logged with the redacting logger; never shown as a stack trace. */
    data class Unexpected(
        val cause: Throwable,
    ) : AppError
}
