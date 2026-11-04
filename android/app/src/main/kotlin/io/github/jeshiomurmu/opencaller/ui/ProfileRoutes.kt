package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.feature.profile.ContactRequestsRoute
import io.github.jeshiomurmu.opencaller.feature.profile.MyProfileNavigation
import io.github.jeshiomurmu.opencaller.feature.profile.MyProfileRoute
import io.github.jeshiomurmu.opencaller.feature.profile.PublicProfileRoute
import io.github.jeshiomurmu.opencaller.feature.profile.VisitorsRoute
import io.github.jeshiomurmu.opencaller.feature.social.ReachActions
import io.github.jeshiomurmu.opencaller.navigation.ContactRequestsInboxRoute
import io.github.jeshiomurmu.opencaller.navigation.MeRoute
import io.github.jeshiomurmu.opencaller.navigation.NumberProfileRoute
import io.github.jeshiomurmu.opencaller.navigation.OwnProfileRoute
import io.github.jeshiomurmu.opencaller.navigation.ProfileVisitorsRoute

/**
 * The profile screens (Phase 8): my profile with its visitors and contact requests under the Me tab, and another
 * number's profile from a search. After the account is deleted the Me tab shows again, signed out.
 */
internal fun NavGraphBuilder.profileRoutes(navController: NavHostController) {
    composable<OwnProfileRoute> {
        MyProfileRoute(
            navigation =
                MyProfileNavigation(
                    onOpenVisitors = { kind -> navController.navigate(ProfileVisitorsRoute(kind.name)) { launchSingleTop = true } },
                    onOpenRequests = { navController.navigate(ContactRequestsInboxRoute) { launchSingleTop = true } },
                    onDeleted = { navController.popBackStack(MeRoute, inclusive = false) },
                ),
        )
    }
    composable<ProfileVisitorsRoute> { entry ->
        VisitorsRoute(kind = VisitKind.valueOf(entry.toRoute<ProfileVisitorsRoute>().kind))
    }
    composable<ContactRequestsInboxRoute> { ContactRequestsRoute() }
    composable<NumberProfileRoute> { entry ->
        val e164 = entry.toRoute<NumberProfileRoute>().e164
        // App call and Flash (Phase 11) above the profile, when the server and this build offer them.
        Column {
            ReachActions(e164 = e164)
            PublicProfileRoute(e164 = e164, modifier = Modifier.weight(1f))
        }
    }
}
