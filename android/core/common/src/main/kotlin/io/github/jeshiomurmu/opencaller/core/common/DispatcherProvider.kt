package io.github.jeshiomurmu.opencaller.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Coroutine dispatchers, injected so tests can substitute a test dispatcher. Classes must never hard-code
 * `Dispatchers.IO` (docs/rules.md §4).
 */
interface DispatcherProvider {
    /** Dispatcher for UI work. */
    val main: CoroutineDispatcher

    /** Dispatcher for blocking I/O (database, files, network). */
    val io: CoroutineDispatcher

    /** Dispatcher for CPU-bound work. */
    val default: CoroutineDispatcher
}

/** Production [DispatcherProvider] backed by [Dispatchers]. */
object DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
