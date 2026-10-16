package io.github.jeshiomurmu.opencaller.core.testing

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher

/** [DispatcherProvider] that runs everything on one [TestDispatcher], so tests control execution. */
class TestDispatcherProvider(
    private val dispatcher: TestDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}
