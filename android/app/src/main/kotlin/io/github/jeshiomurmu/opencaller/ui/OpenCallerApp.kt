package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.jeshiomurmu.opencaller.feature.blocking.BlockedLogRoute
import io.github.jeshiomurmu.opencaller.feature.blocking.BlockingRoute
import io.github.jeshiomurmu.opencaller.feature.blocking.RulesRoute
import io.github.jeshiomurmu.opencaller.feature.settings.DebugRoute
import io.github.jeshiomurmu.opencaller.feature.settings.PermissionsStatusRoute
import io.github.jeshiomurmu.opencaller.navigation.BlockedCallsRoute
import io.github.jeshiomurmu.opencaller.navigation.BlockingRulesRoute
import io.github.jeshiomurmu.opencaller.navigation.CallsRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactsRoute
import io.github.jeshiomurmu.opencaller.navigation.MeRoute
import io.github.jeshiomurmu.opencaller.navigation.MessagesRoute
import io.github.jeshiomurmu.opencaller.navigation.PermissionsRoute
import io.github.jeshiomurmu.opencaller.navigation.ProtectionRoute
import io.github.jeshiomurmu.opencaller.navigation.ScreeningDebugRoute
import io.github.jeshiomurmu.opencaller.navigation.TopLevelDestination

/**
 * App shell: the degraded-mode banner when call screening is off, the bottom bar with the five tabs and a
 * navigation host showing each tab's screen.
 */
@Composable
fun OpenCallerApp(
    state: AppUiState,
    modifier: Modifier = Modifier,
    reportNumber: String? = null,
    onReportShown: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val openPermissions = { item: AccessItem? -> navController.navigate(PermissionsRoute(explain = item?.name)) { launchSingleTop = true } }
    // The after-call notification's Report opens the Protection tab, which shows the report dialog.
    LaunchedEffect(reportNumber) { if (reportNumber != null) navController.navigateToTab(TopLevelDestination.PROTECTION) }
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
        AppNavHost(
            state = state,
            navController = navController,
            reportNumber = reportNumber,
            onReportShown = onReportShown,
            openPermissions = openPermissions,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun AppNavHost(
    state: AppUiState,
    navController: NavHostController,
    reportNumber: String?,
    onReportShown: () -> Unit,
    openPermissions: (AccessItem?) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = CallsRoute, modifier = modifier) {
        composable<CallsRoute> {
            CallsScreen(defaultPhoneApp = state.defaultPhoneApp, onSetDefault = { openPermissions(AccessItem.DEFAULT_PHONE_APP) })
        }
        composable<MessagesRoute> { PlaceholderScreen(TopLevelDestination.MESSAGES) }
        composable<ContactsRoute> { PlaceholderScreen(TopLevelDestination.CONTACTS) }
        composable<ProtectionRoute> {
            BlockingRoute(
                onOpenRules = { navController.navigate(BlockingRulesRoute) { launchSingleTop = true } },
                onOpenBlockedLog = { navController.navigate(BlockedCallsRoute) { launchSingleTop = true } },
                onFixContacts = { openPermissions(AccessItem.CONTACTS) },
                reportNumber = reportNumber,
            )
            LaunchedEffect(reportNumber) { if (reportNumber != null) onReportShown() }
        }
        composable<BlockingRulesRoute> { RulesRoute() }
        composable<BlockedCallsRoute> { BlockedLogRoute() }
        composable<MeRoute> {
            MeScreen(
                onOpenPermissions = { openPermissions(null) },
                onOpenDebug = { navController.navigate(ScreeningDebugRoute) { launchSingleTop = true } },
            )
        }
        composable<ScreeningDebugRoute> { DebugRoute() }
        composable<PermissionsRoute> { entry ->
            PermissionsStatusRoute(explain = entry.toRoute<PermissionsRoute>().explain?.let(AccessItem::valueOf))
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

// Permissions & roles and the debug screen live under the Me tab; the rules and the blocked log under Protection.
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { node ->
        node.hasRoute(destination.route::class) || CHILDREN[destination].orEmpty().any { node.hasRoute(it) }
    } == true

private val CHILDREN =
    mapOf(
        TopLevelDestination.ME to listOf(PermissionsRoute::class, ScreeningDebugRoute::class),
        TopLevelDestination.PROTECTION to listOf(BlockingRulesRoute::class, BlockedCallsRoute::class),
    )

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
