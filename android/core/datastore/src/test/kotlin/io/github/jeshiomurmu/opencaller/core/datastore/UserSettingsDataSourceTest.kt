package io.github.jeshiomurmu.opencaller.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class UserSettingsDataSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun startsWithDefaultsAndPersistsUpdates() =
        runTest {
            val file = File(tmp.root, "user_settings.pb")
            val first = UserSettingsDataSource(DataStoreFactory.create(UserSettingsSerializer, scope = backgroundScope) { file })
            assertEquals(UserPreferences(), first.preferences.first())

            first.update { it.copy(themeMode = ThemeMode.DARK, spamThreshold = SpamThreshold.HIGH) }

            val expected = UserPreferences(themeMode = ThemeMode.DARK, spamThreshold = SpamThreshold.HIGH)
            assertEquals(expected, first.preferences.first())
            assertEquals(expected, file.inputStream().use { UserSettingsSerializer.readFrom(it) }.toDomain())
        }

    @Test(expected = CorruptionException::class)
    fun corruptFileIsReported() =
        runTest {
            UserSettingsSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x0A, 0x7F)))
        }
}
