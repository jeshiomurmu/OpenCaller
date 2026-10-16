package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.LookupBucketEntity

/** Fetched lookup buckets. */
@Dao
interface LookupBucketDao {
    /** Inserts or replaces [bucket]. */
    @Upsert
    suspend fun upsert(bucket: LookupBucketEntity)

    /** The bucket [bucketId] of [countryCode], or `null` if never fetched. */
    @Query("SELECT * FROM lookup_bucket WHERE countryCode = :countryCode AND bucketId = :bucketId")
    suspend fun find(
        countryCode: Int,
        bucketId: Int,
    ): LookupBucketEntity?

    /** Removes buckets fetched before [millis]; returns the number removed. */
    @Query("DELETE FROM lookup_bucket WHERE fetchedAt < :millis")
    suspend fun deleteFetchedBefore(millis: Long): Int
}
