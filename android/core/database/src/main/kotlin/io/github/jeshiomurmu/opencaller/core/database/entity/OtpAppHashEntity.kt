package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/** Maps an SMS Retriever app hash to the app it belongs to. */
@Entity(tableName = "otp_app_hash", primaryKeys = ["hash"])
data class OtpAppHashEntity(
    val hash: String,
    val packageName: String,
    val appName: String,
)
