package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The fake incoming-call screen (docs/prd.md F-UT-01), over the lock screen. Answer shows a fake call with a timer;
 * Decline or Hang up closes it. Nothing here is a call: Telecom, the dialer and the call log never hear of it.
 */
@AndroidEntryPoint
class GhostCallActivity : ComponentActivity() {
    @Inject
    lateinit var settings: UserSettingsDataSource

    @Inject
    lateinit var photos: GhostCallPhotos

    @Inject
    lateinit var notifier: GhostCallNotifier

    private val ringer by lazy { GhostCallRinger(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { end() }
    private var setup by mutableStateOf(GhostCallSetup())
    private var photo by mutableStateOf<ImageBitmap?>(null)
    private var answered by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        answered = savedInstanceState?.getBoolean(STATE_ANSWERED) ?: false
        lifecycleScope.launch {
            setup = settings.preferences.first().ghostCall
            photo = if (setup.hasPhoto) photos.load()?.asImageBitmap() else null
        }
        setContent {
            OpenCallerTheme {
                GhostCallContent(
                    setup = setup,
                    photo = photo,
                    answered = answered,
                    onAnswer = ::answer,
                    onDecline = ::end,
                )
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWERED, answered)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        ringer.stop()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_DECLINE -> end()
            ACTION_ANSWER -> answer()
            else -> if (!answered) ring()
        }
    }

    private fun ring() {
        ringer.start()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, GhostCallNotifier.RING_MILLIS)
    }

    private fun answer() {
        answered = true
        handler.removeCallbacks(timeout)
        ringer.stop()
        notifier.clear()
    }

    private fun end() {
        handler.removeCallbacks(timeout)
        ringer.stop()
        notifier.clear()
        finish()
    }

    /** Intent actions of the notification. */
    companion object {
        /** Show the ringing screen. */
        const val ACTION_RING = "io.github.jeshiomurmu.opencaller.ghostcall.RING"

        /** Answer from the notification. */
        const val ACTION_ANSWER = "io.github.jeshiomurmu.opencaller.ghostcall.ANSWER"

        /** Decline from the notification. */
        const val ACTION_DECLINE = "io.github.jeshiomurmu.opencaller.ghostcall.DECLINE"
        private const val STATE_ANSWERED = "answered"
    }
}
