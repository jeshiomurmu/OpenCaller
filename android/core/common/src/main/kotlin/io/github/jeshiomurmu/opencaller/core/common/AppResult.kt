package io.github.jeshiomurmu.opencaller.core.common

/** Result of an operation that can fail with an [AppError]. Repositories return this instead of throwing. */
sealed interface AppResult<out T> {
    /** The operation succeeded with [value]. */
    data class Success<out T>(
        val value: T,
    ) : AppResult<T>

    /** The operation failed with [error]. */
    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

/** Returns the success value, or `null` for a failure. */
fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> null
    }

/** Returns the error, or `null` for a success. */
fun AppResult<*>.errorOrNull(): AppError? =
    when (this) {
        is AppResult.Success -> null
        is AppResult.Failure -> error
    }

/** Transforms the success value with [transform]; failures pass through unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }

/** Chains another fallible operation on the success value; failures pass through unchanged. */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(value)
        is AppResult.Failure -> this
    }

/** Collapses the result into a single value. */
inline fun <T, R> AppResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R,
): R =
    when (this) {
        is AppResult.Success -> onSuccess(value)
        is AppResult.Failure -> onFailure(error)
    }
