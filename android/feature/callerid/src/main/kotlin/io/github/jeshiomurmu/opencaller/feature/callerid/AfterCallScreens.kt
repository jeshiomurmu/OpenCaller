package io.github.jeshiomurmu.opencaller.feature.callerid

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.AfterCallSurface
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AfterCallPresenter
import io.github.jeshiomurmu.opencaller.core.telephony.incall.EndedCall
import timber.log.Timber
import javax.inject.Inject

/**
 * The after-call card of the default phone app (docs/architecture.md §4.3): a screen ([AfterCallActivity]) when the
 * in-call screen was in front and the phone is unlocked, otherwise the after-call notification (when notifications
 * are allowed). The screen never opens over the lock screen: it offers Save contact and Block.
 */
class AfterCallScreens
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val formatter: CallerCardFormatter,
        private val notifications: CallerIdNotifications,
        private val ended: EndedCalls,
        private val access: AccessStatusSource,
    ) : AfterCallPresenter {
        override fun present(
            call: EndedCall,
            inCallScreenVisible: Boolean,
        ) {
            val number = call.caller.number ?: return
            val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
            if (inCallScreenVisible && !locked) {
                ended.put(call.caller)
                try {
                    context.startActivity(AfterCallActivity.intent(context, number, call.answered))
                    return
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e, "After-call screen could not be opened")
                }
            }
            access.refresh()
            if (access.status.value.afterCallSurface ==
                AfterCallSurface.NOTIFICATION
            ) {
                notifications.showAfterCall(formatter.card(call.caller))
            }
        }
    }
