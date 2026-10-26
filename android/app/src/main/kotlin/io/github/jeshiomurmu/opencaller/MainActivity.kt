package io.github.jeshiomurmu.opencaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.RefreshAccessOnResume
import io.github.jeshiomurmu.opencaller.feature.callerid.CallerIdIntents
import io.github.jeshiomurmu.opencaller.feature.messages.MessageLinks
import io.github.jeshiomurmu.opencaller.feature.messages.MessagesIntents
import io.github.jeshiomurmu.opencaller.feature.onboarding.OnboardingRoute
import io.github.jeshiomurmu.opencaller.feature.onboarding.SignInExtra
import io.github.jeshiomurmu.opencaller.feature.profile.AccountViewModel
import io.github.jeshiomurmu.opencaller.feature.profile.SignInRoute
import io.github.jeshiomurmu.opencaller.ui.OpenCallerApp
import javax.inject.Inject

/** Single activity: onboarding until it is finished, then the app shell. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var refreshAccessOnResume: RefreshAccessOnResume

    private val viewModel: MainViewModel by viewModels()

    private val account: AccountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Roles and permissions can change in system settings while the app is away (TC-ONB-005).
        lifecycle.addObserver(refreshAccessOnResume)
        if (savedInstanceState == null) {
            viewModel.requestReport(intent?.getStringExtra(CallerIdIntents.EXTRA_REPORT_NUMBER))
            requestDialIfAsked(intent)
            requestMessageIfAsked(intent)
        }
        setContent {
            OpenCallerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val report by viewModel.pendingReport.collectAsStateWithLifecycle()
                val dial by viewModel.pendingDial.collectAsStateWithLifecycle()
                val message by viewModel.pendingMessage.collectAsStateWithLifecycle()
                when {
                    state.loading -> {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    }

                    state.showOnboarding -> {
                        Onboarding(account)
                    }

                    else -> {
                        OpenCallerApp(
                            state = state,
                            reportNumber = report,
                            onReportShown = viewModel::reportShown,
                            dialRequest = dial,
                            onDialShown = viewModel::dialShown,
                            messageRequest = message,
                            onMessageShown = viewModel::messageShown,
                        )
                    }
                }
            }
        }
    }

    // Onboarding with the optional sign-in of the Extras step (shown in place of onboarding until it's done or closed).
    @Composable
    private fun Onboarding(account: AccountViewModel) {
        val status by account.uiState.collectAsStateWithLifecycle()
        var signingIn by rememberSaveable { mutableStateOf(false) }
        if (signingIn) {
            BackHandler { signingIn = false }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SignInRoute(onDone = { signingIn = false }, modifier = Modifier.safeDrawingPadding())
            }
        } else {
            OnboardingRoute(signIn = if (status.serverConfigured) SignInExtra(status.signedIn) { signingIn = true } else null)
        }
    }

    // The after-call notification's Report and Add call (ACTION_DIAL) reuse the running activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.requestReport(intent.getStringExtra(CallerIdIntents.EXTRA_REPORT_NUMBER))
        requestDialIfAsked(intent)
        requestMessageIfAsked(intent)
    }

    // ACTION_SENDTO with sms:, smsto:, mms: or mmsto: (another app writes a message; the default SMS app must handle
    // it), or a tapped message notification.
    private fun requestMessageIfAsked(intent: Intent?) {
        val thread = MessagesIntents.threadOf(intent)
        val link = intent?.takeIf { it.action == Intent.ACTION_SENDTO }?.data?.takeIf { it.scheme in MessageLinks.SCHEMES }
        when {
            thread != null -> viewModel.openThread(thread)
            link != null -> viewModel.requestMessage(MessageLinks.recipients(link.schemeSpecificPart), draftOf(intent, link))
        }
    }

    // Apps put the text in sms_body (the common extra), EXTRA_TEXT or the link's body parameter.
    private fun draftOf(
        intent: Intent?,
        link: Uri,
    ): String? =
        intent?.getStringExtra(MessageLinks.EXTRA_SMS_BODY)
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: MessageLinks.body(link.schemeSpecificPart)

    // ACTION_DIAL, with or without a tel: number: the dial pad (OpenCaller as the default phone app must handle both).
    private fun requestDialIfAsked(intent: Intent?) {
        if (intent?.action != Intent.ACTION_DIAL) return
        viewModel.requestDial(intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart)
    }
}
