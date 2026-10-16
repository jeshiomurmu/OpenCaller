package io.github.jeshiomurmu.opencaller.core.telephony

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject

/**
 * Re-reads roles and permissions each time the observed lifecycle resumes, so a role revoked in system
 * settings (TC-ONB-005) or a permission changed in a system dialog shows up as soon as the user is back.
 */
class RefreshAccessOnResume
    @Inject
    constructor(
        private val source: AccessStatusSource,
    ) : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            source.refresh()
        }
    }
