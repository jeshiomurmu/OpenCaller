package io.github.jeshiomurmu.opencaller.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.jeshiomurmu.opencaller.AppUiState
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ConversationsRoute
import io.github.jeshiomurmu.opencaller.feature.messages.ui.NewMessageRoute
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadRoute
import io.github.jeshiomurmu.opencaller.navigation.ComposeMessageRoute
import io.github.jeshiomurmu.opencaller.navigation.ConversationRoute
import io.github.jeshiomurmu.opencaller.navigation.MessagesRoute

/**
 * The messages app's screens: the Messages tab (the conversations as the default SMS app, else the role offer), a
 * conversation, and a new message.
 */
internal fun NavGraphBuilder.messageRoutes(
    state: AppUiState,
    navController: NavHostController,
    openPermissions: (AccessItem?) -> Unit,
) {
    composable<MessagesRoute> {
        if (state.isDefaultSmsApp) {
            ConversationsRoute(
                onOpen = { id -> navController.navigate(ConversationRoute(id)) { launchSingleTop = true } },
                onNewMessage = { navController.navigate(ComposeMessageRoute) { launchSingleTop = true } },
            )
        } else {
            MessagesScreen(defaultSmsApp = state.defaultSmsApp, onSetDefault = { openPermissions(AccessItem.DEFAULT_SMS_APP) })
        }
    }
    composable<ConversationRoute> { entry ->
        val route = entry.toRoute<ConversationRoute>()
        ThreadRoute(threadId = route.threadId, draft = route.draft)
    }
    composable<ComposeMessageRoute> {
        NewMessageRoute(
            onOpenThread = { id ->
                navController.popBackStack()
                navController.navigate(ConversationRoute(id)) { launchSingleTop = true }
            },
        )
    }
}
