package io.github.jeshiomurmu.opencaller.feature.dialer

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.providers.CallLogSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Records what would have reached Telecom. */
class FakeCallPlacer(
    private val emergencyNumbers: Set<String> = setOf("112", "911"),
) : CallPlacer {
    var permitted = true
    var result: AppResult<Unit> = AppResult.Success(Unit)
    var secretResult: AppResult<Unit> = AppResult.Success(Unit)
    val placed = mutableListOf<Pair<String, String?>>()
    val emergency = mutableListOf<String>()
    val voicemail = mutableListOf<String?>()
    val secretCodes = mutableListOf<String>()
    val handedOff = mutableListOf<String>()

    override fun canPlaceCalls() = permitted

    override fun isEmergencyNumber(number: String) = number in emergencyNumbers

    override fun place(
        number: String,
        simId: String?,
    ) = result.also { placed += number to simId }

    override fun placeEmergency(number: String) = AppResult.Success(Unit).also { emergency += number }

    override fun placeVoicemail(simId: String?) = result.also { voicemail += simId }

    override fun sendSecretCode(digits: String) = secretResult.also { secretCodes += digits }

    override fun handOff(number: String) = result.also { handedOff += number }
}

/** Contacts held in memory. */
class FakeContacts : ContactsSource {
    val list = MutableStateFlow<AppResult<List<ContactSummary>>>(AppResult.Success(emptyList()))

    override fun canRead() = true

    override fun canWrite() = true

    override fun observeAll(): Flow<AppResult<List<ContactSummary>>> = list

    override fun observe(contactId: Long): Flow<AppResult<ContactDetail?>> = flowOf(AppResult.Success(null))

    override suspend fun save(
        rawContactId: Long?,
        draft: ContactDraft,
    ): AppResult<Long> = AppResult.Failure(AppError.Storage)

    override suspend fun setStarred(
        contactId: Long,
        starred: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)
}

/** A call log held in memory. */
class FakeCallLog : CallLogSource {
    val calls = MutableStateFlow<AppResult<List<CallLogEntry>>>(AppResult.Success(emptyList()))

    override fun canRead() = true

    override fun observe(): Flow<AppResult<List<CallLogEntry>>> = calls

    override suspend fun delete(ids: Collection<Long>): AppResult<Int> = AppResult.Success(ids.size)
}
