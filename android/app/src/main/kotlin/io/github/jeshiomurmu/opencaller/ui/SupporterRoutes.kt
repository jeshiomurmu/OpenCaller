package io.github.jeshiomurmu.opencaller.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.feature.premium.SupporterRoute
import io.github.jeshiomurmu.opencaller.feature.settings.SettingsLinks
import io.github.jeshiomurmu.opencaller.navigation.AnnounceSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.AppearanceSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.BackupSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.GhostCallSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.MessengerSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.QuickRepliesSettingsRoute
import io.github.jeshiomurmu.opencaller.navigation.ScreeningDebugRoute
import io.github.jeshiomurmu.opencaller.navigation.SupporterPlansRoute

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
    )
}

/** The supporter screen and the supporter extras (Phase 10). */
internal fun NavGraphBuilder.supporterRoutes() {
    composable<SupporterPlansRoute> { SupporterRoute() }
}
