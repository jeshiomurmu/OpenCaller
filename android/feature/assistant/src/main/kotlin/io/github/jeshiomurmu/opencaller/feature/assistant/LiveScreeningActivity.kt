package io.github.jeshiomurmu.opencaller.feature.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import timber.log.Timber

/** The live screen of a call the assistant answers, opened from its notification. */
@AndroidEntryPoint
class LiveScreeningActivity : ComponentActivity() {
    private val viewModel: LiveScreeningViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenCallerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.dial) {
                    state.dial?.let { number ->
                        openDialer(number.e164)
                        viewModel.onEvent(LiveEvent.Dialled)
                    }
                }
                LiveScreeningScreen(state = state, onEvent = viewModel::onEvent, onClose = ::finish)
            }
        }
    }

    // The dial pad with the caller's number: the user places the call (no CALL_PHONE needed).
    private fun openDialer(e164: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", e164, null)))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No dialer to call back")
        }
    }

    /** Intents. */
    companion object {
        /** Opens the live screen of call [callId]. */
        fun intent(
            context: Context,
            callId: String,
        ): Intent = liveIntent(context, callId)
    }
}
