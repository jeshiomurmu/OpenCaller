package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.feature.backup.BackupRoute
import io.github.jeshiomurmu.opencaller.feature.premium.SupporterRoute
import io.github.jeshiomurmu.opencaller.feature.settings.SettingsLinks
import io.github.jeshiomurmu.opencaller.feature.settings.extras.AnnounceCallerRoute
import io.github.jeshiomurmu.opencaller.feature.settings.extras.AppearanceRoute
import io.github.jeshiomurmu.opencaller.feature.settings.extras.MessengerCallerIdRoute
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallRoute
import io.github.jeshiomurmu.opencaller.feature.social.RecordClipRoute
import io.github.jeshiomurmu.opencaller.feature.social.VoipSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.AnnounceSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.AppearanceSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.BackupSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.GhostCallSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.MessengerSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.QuickRepliesSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.RecordClipNavRoute
import io.github.jeshiomurmu.opencaller.navigation.ScreeningDebugRoute
import io.github.jeshiomurmu.opencaller.navigation.SupporterPlansRoute
import io.github.jeshiomurmu.opencaller.navigation.VoipSettingsNavRoute

/** Where the Me tab's settings rows lead. */
internal fun settingsLinks(
    navController: NavHostController,
    openPermissions: (AccessItem?) -> Unit,
): SettingsLinks {
    fun go(route: Any) = navController.navigate(route) { launchSingleTop = true }
    return SettingsLinks(
        onOpenPermissions = { openPermissions(null) },
        onOpenQuickReplies = { go(QuickRepliesSettingsRoute) },
        onOpenDebug = { go(ScreeningDebugRoute) },
        onOpenSupporter = { go(SupporterPlansRoute) },
        onOpenAppearance = { go(AppearanceSettingsRoute) },
        onOpenAnnounceCaller = { go(AnnounceSettingsRoute) },
        onOpenGhostCall = { go(GhostCallSettingsRoute) },
        onOpenMessengerCallerId = { go(MessengerSettingsRoute) },
        onOpenBackup = { go(BackupSettingsRoute) },
        onOpenVoip = { go(VoipSettingsNavRoute) },
    )
}

/** The supporter screen and the supporter extras (Phase 10). */
internal fun NavGraphBuilder.supporterRoutes(navController: NavHostController) {
    val openSupporter = { navController.navigate(SupporterPlansRoute) { launchSingleTop = true } }
    composable<SupporterPlansRoute> { SupporterRoute() }
    composable<AppearanceSettingsRoute> { AppearanceRoute(onOpenSupporter = openSupporter) }
    composable<AnnounceSettingsRoute> { AnnounceCallerRoute(onOpenSupporter = openSupporter) }
    composable<GhostCallSettingsRoute> { GhostCallRoute(onOpenSupporter = openSupporter) }
    composable<MessengerSettingsRoute> { MessengerCallerIdRoute(onOpenSupporter = openSupporter) }
    composable<BackupSettingsRoute> { BackupRoute(onOpenSupporter = openSupporter) }
    voipRoutes(navController)
}

/** App calls, Flash and video caller ID (Phase 11): the settings and the clip recorder, which hands its file back. */
private fun NavGraphBuilder.voipRoutes(navController: NavHostController) {
    composable<VoipSettingsNavRoute> { entry ->
        val handle = entry.savedStateHandle
        val clip by handle.getStateFlow<String?>(RECORDED_CLIP, null).collectAsStateWithLifecycle()
        VoipSettingsRoute(
            onRecord = { navController.navigate(RecordClipNavRoute) { launchSingleTop = true } },
            recordedClip = clip,
            onClipTaken = { handle[RECORDED_CLIP] = null },
        )
    }
    composable<RecordClipNavRoute> {
        RecordClipRoute(
            onRecorded = { path ->
                navController.previousBackStackEntry?.savedStateHandle?.set(RECORDED_CLIP, path)
                navController.popBackStack()
            },
        )
    }
}

private const val RECORDED_CLIP = "recorded_clip"
