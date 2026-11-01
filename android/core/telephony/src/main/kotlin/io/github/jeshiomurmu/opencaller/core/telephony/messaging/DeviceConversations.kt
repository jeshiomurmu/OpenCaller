package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.providers.changes
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether OpenCaller is the default SMS app right now (`RoleManager.ROLE_SMS`). */
fun interface DefaultSmsAppRole {
    /** True while OpenCaller holds the role. */
    fun isHeld(): Boolean
}

/** The user's conversations in Android's message store. Read and changed on the device only. */
interface ConversationSource {
    /** True when OpenCaller is the default SMS app and may read messages. */
    fun canRead(): Boolean

    /** The conversations, newest first, again after every change to the store. */
    fun observeConversations(): Flow<AppResult<List<Conversation>>>

    /** The messages of thread [threadId], oldest first, again after every change. */
    fun observeThread(threadId: Long): Flow<AppResult<List<Message>>>

    /** The messages of thread [threadId] now. */
    suspend fun thread(threadId: Long): AppResult<List<Message>>

    /** The other people of thread [threadId]. */
    suspend fun participants(threadId: Long): AppResult<List<Participant>>

    /** The thread with exactly [addresses] (created when there is none yet). */
    suspend fun threadFor(addresses: Set<String>): AppResult<Long>

    /** Marks every message of [threadId] read and seen; returns how many changed. */
    suspend fun markRead(threadId: Long): AppResult<Int>

    /** Deletes thread [threadId] (its SMS and MMS); returns how many messages went. */
    suspend fun delete(threadId: Long): AppResult<Int>
}

/** [ConversationSource] over `Telephony.Sms` and `Telephony.Mms`, only while OpenCaller is the default SMS app. */
@Singleton
class DeviceConversations
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val smsApp: DefaultSmsAppRole,
        private val sims: MessagingSimSource,
        private val normalizer: PhoneNumberNormalizer,
        private val region: HomeRegionProvider,
        private val dispatchers: DispatcherProvider,
    ) : ConversationSource {
        private val resolver get() = context.contentResolver
        private val reader by lazy { ConversationReader(context.contentResolver, ::participantOf) }

        override fun canRead(): Boolean =
            smsApp.isHeld() && context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        override fun observeConversations(): Flow<AppResult<List<Conversation>>> =
            resolver
                .messageChanges()
                .map {
                    read("conversations") { reader.conversations(sims.ownNumbers()) }
                }.flowOn(dispatchers.io)

        override fun observeThread(threadId: Long): Flow<AppResult<List<Message>>> =
            resolver
                .messageChanges()
                .map {
                    read("thread") { reader.thread(threadId, sims.ownNumbers()) }
                }.flowOn(dispatchers.io)

        override suspend fun thread(threadId: Long): AppResult<List<Message>> =
            withContext(dispatchers.io) {
                read("thread") { reader.thread(threadId, sims.ownNumbers()) }
            }

        override suspend fun participants(threadId: Long): AppResult<List<Participant>> =
            withContext(dispatchers.io) {
                read("participants") { reader.participantsOf(threadId, sims.ownNumbers()) }
            }

        override suspend fun threadFor(addresses: Set<String>): AppResult<Long> =
            if (addresses.isEmpty()) {
                AppResult.Failure(AppError.Validation("no recipients"))
            } else {
                withContext(dispatchers.io) { read("thread id") { Telephony.Threads.getOrCreateThreadId(context, addresses) } }
            }

        override suspend fun markRead(threadId: Long): AppResult<Int> =
            withContext(dispatchers.io) {
                read("mark read") {
                    val values =
                        ContentValues().apply {
                            put(Telephony.Sms.READ, 1)
                            put(Telephony.Sms.SEEN, 1)
                        }
                    val args = arrayOf(threadId.toString())
                    resolver.update(
                        Telephony.Sms.CONTENT_URI,
                        values,
                        "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                        args,
                    ) +
                        resolver.update(
                            Telephony.Mms.CONTENT_URI,
                            values,
                            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                            args,
                        )
                }
            }

        override suspend fun delete(threadId: Long): AppResult<Int> =
            withContext(dispatchers.io) {
                read("delete") {
                    val args = arrayOf(threadId.toString())
                    resolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", args) +
                        resolver.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ?", args)
                }
            }

        private fun participantOf(address: String): Participant =
            Participant(
                address,
                normalizer.fromHandle(
                    address,
                    region.homeRegion() ?: UNKNOWN_REGION,
                ),
            )

        private inline fun <T> read(
            what: String,
            block: () -> T,
        ): AppResult<T> = if (!canRead()) AppResult.Failure(AppError.PermissionDenied) else providerCall(what, block)

        private companion object {
            const val UNKNOWN_REGION = "ZZ"
        }
    }

// Both tables notify their own address and each emits once at the start (one of the two is dropped); a burst of
// changes is read once.
private fun ContentResolver.messageChanges(): Flow<Unit> =
    merge(changes(Telephony.Sms.CONTENT_URI), changes(Telephony.Mms.CONTENT_URI))
        .drop(1)
        .conflate()
