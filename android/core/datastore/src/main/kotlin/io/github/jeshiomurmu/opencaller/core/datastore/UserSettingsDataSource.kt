package io.github.jeshiomurmu.opencaller.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Reads and writes the user's settings. The [DataStore] is created once per process by the app's DI graph
 * (one instance per file, as DataStore requires).
 */
class UserSettingsDataSource(
    private val dataStore: DataStore<UserSettings>,
) {
    /** Current settings, emitting again on every change. */
    val preferences: Flow<UserPreferences> = dataStore.data.map { it.toDomain() }

    /** Atomically replaces the settings with [transform] applied to the current value. */
    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.updateData { stored -> transform(stored.toDomain()).toProto() }
    }

    /** Factory used by the DI graph. */
    companion object {
        /** Creates the data source backed by [file]; DataStore work runs in [scope]. Call once per file. */
        fun create(
            scope: CoroutineScope,
            file: () -> File,
        ): UserSettingsDataSource =
            UserSettingsDataSource(DataStoreFactory.create(UserSettingsSerializer, scope = scope, produceFile = file))
    }
}
