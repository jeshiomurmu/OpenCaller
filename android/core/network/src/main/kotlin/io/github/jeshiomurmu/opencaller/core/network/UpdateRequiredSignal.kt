package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raised when the server refuses this app version (HTTP 426 `upgrade_required`, `MIN_APP_VERSION_CODE` on the
 * server). The app shell shows one "Update OpenCaller" dialog; each failed request still returns its own error to its
 * screen. Stays raised until the app restarts (an update restarts it).
 */
class UpdateRequiredSignal {
    private val state = MutableStateFlow(false)

    /** `true` once a request got 426 from the server. */
    val required: StateFlow<Boolean> = state.asStateFlow()

    internal fun raise() {
        state.value = true
    }
}
