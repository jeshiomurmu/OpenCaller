package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.feature.incall.ui.InCallCallbacks
import io.github.jeshiomurmu.opencaller.feature.incall.ui.InCallRoute
import timber.log.Timber
import javax.inject.Inject

/**
 * The default phone app's in-call screen. It shows over the lock screen and turns the screen on (manifest
 * `showWhenLocked` / `turnScreenOn`), so an incoming call can be answered without unlocking (TC-INCALL-001). Started by
 * the incoming-call notification's full-screen intent, its Answer action ([InCallIntents.ACTION_ANSWER]), the ongoing
 * notification, or the in-call service for calls the user places.
 */
@AndroidEntryPoint
class InCallActivity : ComponentActivity() {
    @Inject
    lateinit var calls: CallController

    @Inject
    lateinit var visibility: InCallVisibility

    private lateinit var proximity: ProximityScreenLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        proximity = ProximityScreenLock(this)
        // After a rotation the call was already answered.
        if (savedInstanceState == null) answerIfAsked(intent)
        val showKeypad = intent?.getBooleanExtra(InCallIntents.EXTRA_SHOW_DIALPAD, false) == true
        setContent {
            OpenCallerTheme {
                InCallRoute(
                    callbacks = InCallCallbacks(onAddCall = ::openDialPad, onFinished = ::finish, onEarpiece = proximity::want),
                    showKeypadAtStart = showKeypad,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerIfAsked(intent)
    }

    override fun onStart() {
        super.onStart()
        proximity.onVisible(true)
    }

    override fun onResume() {
        super.onResume()
        visibility.isVisible = true
    }

    override fun onPause() {
        visibility.isVisible = false
        super.onPause()
    }

    override fun onStop() {
        proximity.onVisible(false)
        super.onStop()
    }

    private fun answerIfAsked(intent: Intent?) {
        if (intent?.action != InCallIntents.ACTION_ANSWER) return
        intent.getStringExtra(InCallIntents.EXTRA_CALL_ID)?.let(calls::answer)
    }

    // Add call: OpenCaller's own dial pad (it handles ACTION_DIAL); Telecom holds the current call when the new one starts.
    private fun openDialPad() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "Dial pad could not be opened")
        }
    }
}
