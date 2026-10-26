package io.github.jeshiomurmu.opencaller.feature.incall.calls

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallCallers
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Who is on each call, by [TrackedCall.id]; a call is missing until its lookup finishes. */
interface CallerDirectory {
    /** Callers by call id. */
    val callers: StateFlow<Map<String, CallerInfo>>
}

/**
 * Who is on each call, looked up once per call (and again if its number changes) and shared by the in-call screen and
 * the call notifications. Main thread only.
 */
@Singleton
internal class CallerCache
    @Inject
    constructor(
        private val lookup: InCallCallers,
        dispatchers: DispatcherProvider,
    ) : CallerDirectory {
        private val scope =
            CoroutineScope(
                SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.w(e, "In-call caller lookup failed") },
            )
        private val resolved = mutableMapOf<String, Resolved>()
        private val pending = mutableMapOf<String, Key>()
        private val mutableCallers = MutableStateFlow<Map<String, CallerInfo>>(emptyMap())

        override val callers: StateFlow<Map<String, CallerInfo>> = mutableCallers.asStateFlow()

        /** Looks up callers of new calls and forgets calls that are gone. */
        fun sync(state: InCallState) {
            val ids = state.calls.map(TrackedCall::id).toSet()
            resolved.keys.retainAll(ids)
            pending.keys.retainAll(ids)
            state.calls.forEach(::resolve)
            publish()
        }

        /** The caller of call [id], if known. */
        fun callerOf(id: String): CallerInfo? = resolved[id]?.caller

        private fun resolve(call: TrackedCall) {
            val key = Key(call.handle, call.hidden)
            if (resolved[call.id]?.key == key || pending[call.id] == key) return
            pending[call.id] = key
            scope.launch {
                val caller = lookup.callerFor(call)
                if (pending[call.id] == key) {
                    pending.remove(call.id)
                    resolved[call.id] = Resolved(key, caller)
                    publish()
                }
            }
        }

        private fun publish() {
            mutableCallers.value = resolved.mapValues { it.value.caller }
        }

        private data class Key(
            val handle: String?,
            val hidden: Boolean,
        )

        private class Resolved(
            val key: Key,
            val caller: CallerInfo,
        )
    }
