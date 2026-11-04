package io.github.jeshiomurmu.opencaller.feature.settings

/** Where each settings row leads (the app's navigation). */
data class SettingsLinks(
    val onOpenPermissions: () -> Unit = {},
    val onOpenQuickReplies: () -> Unit = {},
    val onOpenDebug: () -> Unit = {},
    val onOpenSupporter: () -> Unit = {},
    val onOpenAppearance: () -> Unit = {},
    val onOpenAnnounceCaller: () -> Unit = {},
    val onOpenGhostCall: () -> Unit = {},
    val onOpenMessengerCallerId: () -> Unit = {},
    val onOpenBackup: () -> Unit = {},
    val onOpenVoip: () -> Unit = {},
)
