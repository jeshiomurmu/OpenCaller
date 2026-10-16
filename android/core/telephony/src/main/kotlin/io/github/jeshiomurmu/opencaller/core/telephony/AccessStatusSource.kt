package io.github.jeshiomurmu.opencaller.core.telephony

import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** OpenCaller's roles and permissions, re-read on [refresh]. */
interface AccessStatusSource {
    /** Status as of the last [refresh]. */
    val status: StateFlow<AccessStatus>

    /** Re-reads roles and permissions from the system. */
    fun refresh()
}

/** [AccessStatusSource] backed by [RoleManagerHelper] and [PermissionStatusReader]. */
@Singleton
class DeviceAccessStatusSource
    @Inject
    constructor(
        private val roles: RoleManagerHelper,
        private val permissions: PermissionStatusReader,
    ) : AccessStatusSource {
        private val state = MutableStateFlow(AccessStatus(roles.roleState.value, permissions.read()))

        override val status: StateFlow<AccessStatus> = state.asStateFlow()

        override fun refresh() {
            roles.refresh()
            state.value = AccessStatus(roles.roleState.value, permissions.read())
        }
    }
