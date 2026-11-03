package io.github.jeshiomurmu.opencaller.backup

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudBackup
import io.github.jeshiomurmu.opencaller.core.data.backup.NoCloudBackup

/** The foss build has no cloud destination: backups go to a file the user picks (Storage Access Framework). */
@Module
@InstallIn(SingletonComponent::class)
object CloudBackupModule {
    /** None. */
    @Provides
    fun cloudBackup(): CloudBackup = NoCloudBackup()
}
