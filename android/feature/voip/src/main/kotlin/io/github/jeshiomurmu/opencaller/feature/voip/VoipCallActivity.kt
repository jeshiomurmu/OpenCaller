package io.github.jeshiomurmu.opencaller.feature.voip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallIntents
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app-call screen, over the lock screen while a call rings. It starts an outgoing call ([AppCallIntents]) or
 * answers one (the notification's Answer), but only once the microphone is allowed; then it shows the call and
 * closes a moment after it ends.
 */
@AndroidEntryPoint
class VoipCallActivity : ComponentActivity() {
    @Inject
    lateinit var controller: AppCallController

    private var pending: Intent? = null

    // True once there was a call to show: the screen closes only after that (not while it asks for the microphone).
    private var hadCall = false
    private var micDenied by mutableStateOf(false)
    private val askMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micDenied = !granted
            if (granted) pending?.let(::act)
            pending = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hadCall = controller.state.value?.isLive == true
        if (savedInstanceState == null) handle(intent)
        setContent {
            OpenCallerTheme {
                val state by controller.state.collectAsStateWithLifecycle()
                VoipCallScreen(
                    state = state,
                    micDenied = micDenied,
                    onEvent = ::onEvent,
                )
            }
        }
        lifecycleScope.launch {
            controller.state.collect { ui ->
                if (ui?.isLive == true) hadCall = true
                if (hadCall && ui?.isLive != true) {
                    delay(ENDED_SCREEN_MILLIS)
                    if (controller.state.value?.isLive != true) finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun onEvent(event: VoipCallEvent) {
        when (event) {
            VoipCallEvent.Answer -> handle(Intent(ACTION_ANSWER))
            VoipCallEvent.HangUp -> controller.hangUp()
            is VoipCallEvent.Mute -> controller.setMuted(event.muted)
            is VoipCallEvent.Speaker -> controller.setSpeaker(event.on)
            VoipCallEvent.AllowMicrophone -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Calls and answers need the microphone first; showing the call doesn't.
    private fun handle(intent: Intent) {
        if (intent.action != AppCallIntents.ACTION_CALL && intent.action != ACTION_ANSWER) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            act(intent)
        } else {
            pending = intent
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun act(intent: Intent) {
        when (intent.action) {
            AppCallIntents.ACTION_CALL -> intent.getStringExtra(AppCallIntents.EXTRA_NUMBER)?.let(controller::call)
            ACTION_ANSWER -> controller.answer()
        }
    }

    /** Intent actions of the notification. */
    companion object {
        /** Show the call. */
        const val ACTION_SHOW = "io.github.jeshiomurmu.opencaller.voip.SHOW"

        /** Answer the ringing call. */
        const val ACTION_ANSWER = "io.github.jeshiomurmu.opencaller.voip.ANSWER"
        private const val ENDED_SCREEN_MILLIS = 2_000L
    }
}
