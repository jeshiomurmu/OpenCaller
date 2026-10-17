package io.github.jeshiomurmu.opencaller.core.data.sync

import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** [SpamListUpdates] over the stored sync state and WorkManager. */
@Singleton
class DefaultSpamListUpdates
    @Inject
    constructor(
        store: SpamListSyncStateStore,
        private val server: ServerConfig,
        private val work: BackgroundWork,
    ) : SpamListUpdates {
        override val state: Flow<SpamListSyncState> = store.state

        override val serverConfigured: Boolean get() = server.isConfigured

        override fun updateNow() {
            if (server.isConfigured) work.syncListNow()
        }
    }
