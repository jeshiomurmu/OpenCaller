package io.github.jeshiomurmu.opencaller.core.telephony

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries and requests OpenCaller's default-app roles through [RoleManager] (API 29+, the app's minSdk).
 *
 * [roleState] is a snapshot: call [refresh] whenever the user may have changed a role, which
 * [RefreshAccessOnResume] does every time the app comes back to the foreground.
 */
@Singleton
class RoleManagerHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)
        private val state = MutableStateFlow(readState())

        /** Status of every role, as of the last [refresh]. */
        val roleState: StateFlow<RoleState> = state.asStateFlow()

        /** Re-reads every role from the system. */
        fun refresh() {
            state.value = readState()
        }

        /** True when this device offers [role]. */
        fun isRoleAvailable(role: AppRole): Boolean = roleManager?.isRoleAvailable(role.androidName) == true

        /** True when OpenCaller holds [role]. */
        fun isRoleHeld(role: AppRole): Boolean = roleManager?.isRoleHeld(role.androidName) == true

        /** True when this build ships the components Android requires before it grants [role]. */
        fun isAppEligible(role: AppRole): Boolean = RoleEligibility.isEligible(context.packageManager, context.packageName, role)

        /**
         * The system dialog asking the user to give OpenCaller [role]; start it for a result. Returns `null` when
         * the device doesn't offer the role.
         */
        fun createRequestIntent(role: AppRole): Intent? =
            roleManager?.takeIf { it.isRoleAvailable(role.androidName) }?.createRequestRoleIntent(role.androidName)

        private fun statusOf(role: AppRole): RoleStatus =
            when {
                !isRoleAvailable(role) -> RoleStatus.UNAVAILABLE
                isRoleHeld(role) -> RoleStatus.HELD
                !isAppEligible(role) -> RoleStatus.APP_NOT_ELIGIBLE
                else -> RoleStatus.NOT_HELD
            }

        private fun readState(): RoleState =
            RoleState(
                callScreening = statusOf(AppRole.CALL_SCREENING),
                dialer = statusOf(AppRole.DIALER),
                sms = statusOf(AppRole.SMS),
            )
    }

/** The [RoleManager] role name for this role. */
val AppRole.androidName: String
    get() =
        when (this) {
            AppRole.CALL_SCREENING -> RoleManager.ROLE_CALL_SCREENING
            AppRole.DIALER -> RoleManager.ROLE_DIALER
            AppRole.SMS -> RoleManager.ROLE_SMS
        }
