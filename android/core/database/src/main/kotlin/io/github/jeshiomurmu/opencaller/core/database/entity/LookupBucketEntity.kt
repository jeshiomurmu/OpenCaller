package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/** A k-anonymous lookup bucket fetched from the server (docs/architecture.md §4.6). */
@Entity(tableName = "lookup_bucket", primaryKeys = ["countryCode", "bucketId"])
data class LookupBucketEntity(
    val countryCode: Int,
    val bucketId: Int,
    val fetchedAt: Long,
    val etag: String?,
)
