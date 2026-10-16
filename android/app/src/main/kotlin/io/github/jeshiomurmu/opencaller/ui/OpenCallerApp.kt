package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.foundation.layout.padding
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
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.navigation.CallsRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactsRoute
import io.github.jeshiomurmu.opencaller.navigation.MeRoute
import io.github.jeshiomurmu.opencaller.navigation.MessagesRoute
import io.github.jeshiomurmu.opencaller.navigation.ProtectionRoute
import io.github.jeshiomurmu.opencaller.navigation.TopLevelDestination

/** App shell: bottom bar with the five tabs and a navigation host showing each tab's screen. */
@Composable
fun OpenCallerApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination.isOn(destination),
                        onClick = { navController.navigateToTab(destination) },
                        icon = { Icon(painter = painterResource(destination.icon), contentDescription = null) },
                        label = { Text(text = stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = CallsRoute, modifier = Modifier.padding(padding)) {
            composable<CallsRoute> { PlaceholderScreen(TopLevelDestination.CALLS) }
            composable<MessagesRoute> { PlaceholderScreen(TopLevelDestination.MESSAGES) }
            composable<ContactsRoute> { PlaceholderScreen(TopLevelDestination.CONTACTS) }
            composable<ProtectionRoute> { PlaceholderScreen(TopLevelDestination.PROTECTION) }
            composable<MeRoute> { PlaceholderScreen(TopLevelDestination.ME) }
        }
    }
}

/** Phase 1 placeholder for a tab; replaced by the feature modules in later phases. */
@Composable
private fun PlaceholderScreen(
    destination: TopLevelDestination,
    modifier: Modifier = Modifier,
) {
    EmptyState(icon = destination.icon, message = stringResource(destination.placeholder), modifier = modifier)
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route::class) } == true

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
        OpenCallerApp()
    }
}
