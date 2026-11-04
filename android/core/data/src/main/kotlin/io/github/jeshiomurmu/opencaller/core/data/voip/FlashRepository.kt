package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.FlashMessageEntity
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.SendFlashDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Shows a received Flash message (a notification); implemented by `feature:social`. */
fun interface FlashAlerts {
    /** Tells the user about [message]. */
    fun show(message: FlashMessage)
}

/**
 * Flash messages (docs/prd.md F-CM-10): predefined pings sent through the server, kept on this phone only. The
 * server deletes a message once the recipient's phone fetched it; sent and received messages stay here for
 * [KEEP_MILLIS].
 */
@Singleton
class FlashRepository
    @Inject
    constructor(
        private val api: VoipApi,
        private val clock: Clock,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** The newest messages, newest first. */
        fun recent(): Flow<AppResult<List<FlashMessage>>> = storage.observe { flashMessageDao().observeRecent(RECENT_LIMIT) }.mapRows()

        /** Sends [kind] to [number] and keeps it here. */
        suspend fun send(
            number: PhoneNumber,
            kind: FlashKind,
        ): AppResult<FlashMessage> =
            apiCall { api.sendFlash(SendFlashDto(number.e164, kind.wireName)) }.map { sent ->
                FlashMessage(sent.id, kind, number, name = null, atMillis = clock.nowMillis(), outgoing = true).also { keep(it) }
            }

        /** Keeps a received [message]; true if it is new (not seen through another push or fetch). */
        suspend fun keep(message: FlashMessage): Boolean {
            val now = clock.nowMillis()
            val inserted =
                storage.run {
                    flashMessageDao().deleteOlderThan(now - KEEP_MILLIS)
                    flashMessageDao().insert(message.toEntity())
                }
            return (inserted as? AppResult.Success)?.value?.let { it != -1L } ?: false
        }

        private fun Flow<AppResult<List<FlashMessageEntity>>>.mapRows() =
            kotlinx.coroutines.flow.flow {
                collect { result -> emit(result.map { rows -> rows.mapNotNull { it.toModel() } }) }
            }

        private fun FlashMessage.toEntity() = FlashMessageEntity(id, kind.wireName, number.e164, name, atMillis, outgoing)

        private fun FlashMessageEntity.toModel(): FlashMessage? {
            val kind = FlashKind.parse(kind) ?: return null
            val number = phoneNumberOrNull(e164) ?: return null
            return FlashMessage(id, kind, number, name, atMillis, outgoing)
        }

        private companion object {
            const val RECENT_LIMIT = 100
            const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
        }
    }
