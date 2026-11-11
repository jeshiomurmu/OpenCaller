package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

/** The phone's list of saved recordings (the Room `recording` table; a map in tests). */
interface RecordingStore {
    /** Every saved recording, newest first; empty if the database can't be read. */
    val all: Flow<List<RecordingEntity>>

    /** Recording [id], or `null` (also when the database can't be read). */
    suspend fun find(id: String): RecordingEntity?

    /** Saves [recording]; false if the database refused. */
    suspend fun put(recording: RecordingEntity): Boolean

    /** Forgets recording [id]. */
    suspend fun remove(id: String)
}
