package io.github.jeshiomurmu.opencaller.core.datastore

import androidx.datastore.core.DataStore
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A [UserSettingsDataSource] kept in memory, for tests and Compose previews. Nothing is written to disk. */
fun UserSettingsDataSource.Companion.inMemory(initial: UserPreferences = UserPreferences()): UserSettingsDataSource =
    UserSettingsDataSource(InMemoryDataStore(initial.toProto()))

private class InMemoryDataStore<T>(
    initial: T,
) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock { transform(state.value).also { state.value = it } }
}
