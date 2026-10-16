package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jeshiomurmu.opencaller.AppUiState
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.core.ui.WarningBanner
import io.github.jeshiomurmu.opencaller.feature.settings.PermissionsStatusRoute
import io.github.jeshiomurmu.opencaller.navigation.CallsRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactsRoute
import io.github.jeshiomurmu.opencaller.navigation.MeRoute
import io.github.jeshiomurmu.opencaller.navigation.MessagesRoute
import io.github.jeshiomurmu.opencaller.navigation.PermissionsRoute
import io.github.jeshiomurmu.opencaller.navigation.ProtectionRoute
import io.github.jeshiomurmu.opencaller.navigation.TopLevelDestination

/**
 * App shell: the degraded-mode banner when call screening is off, the bottom bar with the five tabs and a
 * navigation host showing each tab's screen.
 */
@Composable
fun OpenCallerApp(
    state: AppUiState,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val openPermissions = { item: AccessItem? -> navController.navigate(PermissionsRoute(explain = item?.name)) { launchSingleTop = true } }
    Scaffold(
        modifier = modifier,
        topBar = {
            if (state.degraded) {
                WarningBanner(
                    message = stringResource(R.string.banner_screening_off),
                    actionLabel = stringResource(R.string.banner_fix),
                    onAction = { openPermissions(AccessItem.CALL_SCREENING) },
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        },
        bottomBar = { TabBar(navController) },
    ) { padding ->
        NavHost(navController = navController, startDestination = CallsRoute, modifier = Modifier.padding(padding)) {
            composable<CallsRoute> {
                CallsScreen(defaultPhoneApp = state.defaultPhoneApp, onSetDefault = { openPermissions(AccessItem.DEFAULT_PHONE_APP) })
            }
            composable<MessagesRoute> { PlaceholderScreen(TopLevelDestination.MESSAGES) }
            composable<ContactsRoute> { PlaceholderScreen(TopLevelDestination.CONTACTS) }
            composable<ProtectionRoute> { PlaceholderScreen(TopLevelDestination.PROTECTION) }
            composable<MeRoute> { MeScreen(onOpenPermissions = { openPermissions(null) }) }
            composable<PermissionsRoute> { entry ->
                PermissionsStatusRoute(explain = entry.toRoute<PermissionsRoute>().explain?.let(AccessItem::valueOf))
            }
        }
    }
}

@Composable
private fun TabBar(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentDestination.isOn(destination),
                onClick = { navController.navigateToTab(destination) },
                icon = { Icon(painter = painterResource(destination.icon), contentDescription = null) },
                label = { Text(text = stringResource(destination.label)) },
            )
        }
    }
}

/** Placeholder for a tab whose feature module arrives in a later phase. */
@Composable
internal fun PlaceholderScreen(
    destination: TopLevelDestination,
    modifier: Modifier = Modifier,
) {
    EmptyState(icon = destination.icon, message = stringResource(destination.placeholder), modifier = modifier)
}

// Permissions & roles lives under the Me tab.
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any {
        it.hasRoute(destination.route::class) || (destination == TopLevelDestination.ME && it.hasRoute(PermissionsRoute::class))
    } == true

// Standard tab navigation: one copy of each tab on the back stack, state saved and restored per tab.
private fun NavHostController.navigateToTab(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OpenCallerPreviews
@Composable
private fun OpenCallerAppPreview() {
    OpenCallerTheme {
        OpenCallerApp(state = AppUiState(loading = false, degraded = true, defaultPhoneApp = AccessItemStatus.COMING_LATER))
    }
}
