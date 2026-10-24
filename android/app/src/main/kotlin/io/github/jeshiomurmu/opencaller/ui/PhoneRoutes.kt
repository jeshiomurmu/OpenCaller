package io.github.jeshiomurmu.opencaller.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.jeshiomurmu.opencaller.AppUiState
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.feature.calllog.CallLogNavigation
import io.github.jeshiomurmu.opencaller.feature.contacts.ContactDetailNavigation
import io.github.jeshiomurmu.opencaller.feature.contacts.ContactDetailRoute
import io.github.jeshiomurmu.opencaller.feature.contacts.ContactEditRoute
import io.github.jeshiomurmu.opencaller.feature.contacts.ContactsListRoute
import io.github.jeshiomurmu.opencaller.feature.dialer.DialPadRoute
import io.github.jeshiomurmu.opencaller.navigation.CallsRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactEditorRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactRoute
import io.github.jeshiomurmu.opencaller.navigation.ContactsRoute
import io.github.jeshiomurmu.opencaller.navigation.DialerRoute
import io.github.jeshiomurmu.opencaller.navigation.NEW_CONTACT
import io.github.jeshiomurmu.opencaller.navigation.NumberSearchRoute

/**
 * The phone app's screens: the Calls tab (the call log as the default phone app, else search and the role offer),
 * the dial pad, and the Contacts tab with a contact and its editor.
 */
internal fun NavGraphBuilder.phoneRoutes(
    state: AppUiState,
    navController: NavHostController,
    openPermissions: (AccessItem?) -> Unit,
) {
    composable<CallsRoute> {
        val onSearch = { navController.navigate(NumberSearchRoute) { launchSingleTop = true } }
        if (state.isDefaultPhoneApp) {
            CallHistoryTab(onSearch = onSearch, navigation = callLogNavigation(navController))
        } else {
            CallsScreen(
                defaultPhoneApp = state.defaultPhoneApp,
                onSetDefault = { openPermissions(AccessItem.DEFAULT_PHONE_APP) },
                onSearch = onSearch,
            )
        }
    }
    composable<DialerRoute> { entry -> DialPadRoute(initialNumber = entry.toRoute<DialerRoute>().number) }
    contactRoutes(navController)
}

// Where the call log leads: the dial pad, a contact, or a new contact with the number.
private fun callLogNavigation(navController: NavHostController): CallLogNavigation =
    CallLogNavigation(
        onDialPad = { number -> navController.navigate(DialerRoute(number)) { launchSingleTop = true } },
        onOpenContact = { id -> navController.navigate(ContactRoute(id)) { launchSingleTop = true } },
        onAddContact = { number -> navController.navigate(ContactEditorRoute(number = number)) { launchSingleTop = true } },
    )

// The Contacts tab, a contact, and the create/edit screen. Saving a new contact opens it; saving an edit goes back to it.
private fun NavGraphBuilder.contactRoutes(navController: NavHostController) {
    composable<ContactsRoute> {
        ContactsListRoute(
            onOpen = { id -> navController.navigate(ContactRoute(id)) { launchSingleTop = true } },
            onCreate = { navController.navigate(ContactEditorRoute()) { launchSingleTop = true } },
        )
    }
    composable<ContactRoute> { entry ->
        ContactDetailRoute(
            contactId = entry.toRoute<ContactRoute>().contactId,
            navigation =
                ContactDetailNavigation(
                    onEdit = { id -> navController.navigate(ContactEditorRoute(contactId = id)) { launchSingleTop = true } },
                    onDialPad = { number -> navController.navigate(DialerRoute(number)) { launchSingleTop = true } },
                    onClose = { navController.popBackStack() },
                ),
        )
    }
    composable<ContactEditorRoute> { entry ->
        val route = entry.toRoute<ContactEditorRoute>()
        val editing = route.contactId.takeIf { it != NEW_CONTACT }
        ContactEditRoute(
            contactId = editing,
            number = route.number,
            onSaved = { id ->
                navController.popBackStack()
                if (editing == null) navController.navigate(ContactRoute(id)) { launchSingleTop = true }
            },
            onCancel = { navController.popBackStack() },
        )
    }
}
