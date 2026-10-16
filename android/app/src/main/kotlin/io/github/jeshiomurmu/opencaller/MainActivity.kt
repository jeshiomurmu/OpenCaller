package io.github.jeshiomurmu.opencaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.RefreshAccessOnResume
import io.github.jeshiomurmu.opencaller.feature.onboarding.OnboardingRoute
import io.github.jeshiomurmu.opencaller.ui.OpenCallerApp
import javax.inject.Inject

/** Single activity: onboarding until it is finished, then the app shell. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var refreshAccessOnResume: RefreshAccessOnResume

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Roles and permissions can change in system settings while the app is away (TC-ONB-005).
        lifecycle.addObserver(refreshAccessOnResume)
        setContent {
            OpenCallerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                when {
                    state.loading -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    state.showOnboarding -> OnboardingRoute()
                    else -> OpenCallerApp(state = state)
                }
            }
        }
    }
}
