package io.github.jeshiomurmu.opencaller.feature.messages

import android.content.Context
import android.content.Intent

/** How other parts of the app open a conversation. */
object MessagesIntents {
    /** Extra on the app's launch intent: the thread to open (a message notification was tapped). */
    const val EXTRA_THREAD_ID = "io.github.jeshiomurmu.opencaller.extra.OPEN_THREAD"

    /** The app's launch intent opening thread [threadId], or `null` when the app has no launcher entry. */
    fun openThread(
        context: Context,
        threadId: Long,
    ): Intent? =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_THREAD_ID, threadId)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The thread [intent] asks to open, if any. */
    fun threadOf(intent: Intent?): Long? = intent?.getLongExtra(EXTRA_THREAD_ID, NONE)?.takeIf { it != NONE }

    private const val NONE = -1L
}
