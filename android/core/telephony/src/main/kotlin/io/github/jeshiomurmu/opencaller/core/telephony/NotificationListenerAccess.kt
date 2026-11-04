package io.github.jeshiomurmu.opencaller.core.telephony

import android.content.Intent

/**
 * Notification access, which messenger caller ID needs (docs/architecture.md §5). Implemented where the listener
 * lives (`feature:callerid`), so settings can show and fix it without depending on that feature.
 */
interface NotificationListenerAccess {
    /** True when the user has given OpenCaller notification access. */
    fun granted(): Boolean

    /** The system settings page where the user gives or takes back that access. */
    fun settingsIntent(): Intent
}
