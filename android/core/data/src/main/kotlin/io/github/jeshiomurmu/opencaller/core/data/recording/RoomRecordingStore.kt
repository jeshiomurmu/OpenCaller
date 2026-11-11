package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [RecordingStore] in the encrypted database. */
internal class RoomRecordingStore(
    access: DatabaseAccess,
    dispatchers: DispatcherProvider,
) : RecordingStore {
    private val storage = Storage(access, dispatchers)

    override val all: Flow<List<RecordingEntity>> =
        storage.observe { recordingDao().observeAll() }.map { (it as? AppResult.Success)?.value.orEmpty() }

    override suspend fun find(id: String): RecordingEntity? = (storage.run { recordingDao().find(id) } as? AppResult.Success)?.value

    override suspend fun put(recording: RecordingEntity): Boolean = storage.run { recordingDao().upsert(recording) } is AppResult.Success

    override suspend fun remove(id: String) {
        storage.run { recordingDao().delete(id) }
    }
}
