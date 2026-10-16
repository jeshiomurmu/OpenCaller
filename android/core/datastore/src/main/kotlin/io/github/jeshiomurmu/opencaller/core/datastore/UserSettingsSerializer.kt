package io.github.jeshiomurmu.opencaller.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** Proto DataStore serializer for [UserSettings]; unreadable files surface as [CorruptionException]. */
object UserSettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): UserSettings =
        try {
            UserSettings.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read user settings", e)
        }

    override suspend fun writeTo(
        t: UserSettings,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
