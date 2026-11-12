package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreenedCallEntity
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ScreenedCallStore] in the encrypted database. */
internal class RoomScreenedCallStore(
    access: DatabaseAccess,
    dispatchers: DispatcherProvider,
) : ScreenedCallStore {
    private val storage = Storage(access, dispatchers)

    override fun recent(limit: Int): Flow<List<ScreenedCall>> =
        storage
            .observe { screenedCallDao().observeRecent(limit) }
            .map { rows -> (rows as? AppResult.Success)?.value.orEmpty().map(ScreenedCallEntity::toModel) }

    override suspend fun put(call: ScreenedCall) {
        storage.run { screenedCallDao().upsert(call.toEntity()) }
    }

    override suspend fun remove(id: String) {
        storage.run { screenedCallDao().delete(id) }
    }

    override suspend fun deleteOlderThan(beforeMillis: Long) {
        storage.run { screenedCallDao().deleteOlderThan(beforeMillis) }
    }
}
