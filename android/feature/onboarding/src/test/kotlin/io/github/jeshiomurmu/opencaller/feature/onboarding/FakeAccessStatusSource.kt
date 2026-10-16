package io.github.jeshiomurmu.opencaller.feature.onboarding

import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Access state the test sets directly; [refresh] counts calls. */
class FakeAccessStatusSource(
    initial: AccessStatus = FRESH_INSTALL,
) : AccessStatusSource {
    override val status = MutableStateFlow(initial)
    var refreshes = 0
        private set

    override fun refresh() {
        refreshes++
    }

    companion object {
        /** A fresh install on Android 13+: nothing granted, dialer and SMS roles not offered yet. */
        val FRESH_INSTALL =
            AccessStatus(
                roles = RoleState(RoleStatus.NOT_HELD, RoleStatus.APP_NOT_ELIGIBLE, RoleStatus.APP_NOT_ELIGIBLE),
                permissions = PermissionState(PermissionStatus.DENIED, PermissionStatus.DENIED, PermissionStatus.DENIED),
            )
    }
}
