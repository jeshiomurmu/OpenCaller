package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyAlertNotices
import io.github.jeshiomurmu.opencaller.core.data.family.toFamilyAlert
import io.github.jeshiomurmu.opencaller.core.network.NotificationDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import timber.log.Timber
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a push into what it stands for (ADR 0007). A push carries only a random id; the phone asks the server
 * what it is (`GET /v1/notifications/{id}`) and hands app calls to [AppCallHandler] (when the build has app calls)
 * and Flash messages to [FlashRepository] and [FlashAlerts], and shows a family alert to the group's admin
 * ([FamilyAlertNotices], ADR 0008). [catchUp] does the same for items whose push never arrived.
 */
@Singleton
class NotificationRouter
    @Inject
    constructor(
        private val api: VoipApi,
        private val calls: Optional<AppCallHandler>,
        private val flash: FlashRepository,
        private val alerts: FlashAlerts,
        private val family: FamilyAlertNotices,
        private val clock: Clock,
    ) {
        /** Handles the push that carried [itemId]; false if the item couldn't be fetched. */
        suspend fun onPush(itemId: String): Boolean {
            if (!ITEM_ID.matches(itemId)) {
                Timber.w("Push without a valid item id")
                return false
            }
            return when (val result = apiCall { api.notification(itemId) }) {
                is AppResult.Success -> {
                    route(result.value)
                    true
                }

                is AppResult.Failure -> {
                    Timber.i("Push item not fetched: %s", result.error)
                    false
                }
            }
        }

        /** Handles every pending item (a missed push, the app coming to the foreground). */
        suspend fun catchUp() {
            val items = (apiCall { api.pendingNotifications() } as? AppResult.Success)?.value ?: return
            // Oldest first, so a call's update follows its invite.
            items.reversed().forEach { route(it) }
        }

        private suspend fun route(item: NotificationDto) {
            val now = clock.nowMillis()
            when (item.kind) {
                CALL_INVITE -> {
                    item.call
                        ?.toModel(now)
                        ?.takeIf { !it.isCaller }
                        ?.let { call -> calls.ifPresent { it.onInvite(call) } }
                }

                CALL_UPDATE -> {
                    item.call?.toModel(now)?.let { call -> calls.ifPresent { it.onUpdate(call) } }
                }

                FLASH -> {
                    item.flash?.toModel(now)?.let { message -> if (flash.keep(message)) alerts.show(message) }
                }

                FAMILY_ALERT -> {
                    item.familyAlert?.toFamilyAlert()?.let(family::show)
                }

                else -> {
                    Timber.i("Unknown push item kind %s", item.kind)
                }
            }
        }

        private companion object {
            const val CALL_INVITE = "call_invite"
            const val CALL_UPDATE = "call_update"
            const val FLASH = "flash"
            const val FAMILY_ALERT = "family_alert"
            val ITEM_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        }
    }
