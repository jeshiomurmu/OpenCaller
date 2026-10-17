package io.github.jeshiomurmu.opencaller.core.network

/**
 * Where the network layer reports failures it turns into an [io.github.jeshiomurmu.opencaller.core.common.AppError]
 * (docs/rules.md §2: never swallow exceptions). The app points [sink] at its redacting logger; messages never
 * contain phone numbers or tokens.
 */
object NetworkLog {
    /** Receives a short message and the exception. Default: discard (unit tests, release builds). */
    @Volatile
    var sink: (message: String, error: Throwable) -> Unit = { _, _ -> }

    /** Reports [error] with [message]. */
    fun warn(
        message: String,
        error: Throwable,
    ) = sink(message, error)
}
