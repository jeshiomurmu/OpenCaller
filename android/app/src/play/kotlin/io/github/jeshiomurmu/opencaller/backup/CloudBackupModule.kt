package io.github.jeshiomurmu.opencaller.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudBackup

/** The play build backs up to Google Drive's app folder (and to a file, like foss). */
@Module
@InstallIn(SingletonComponent::class)
interface CloudBackupModule {
    /** Google Drive. */
    @Binds
    fun cloudBackup(impl: DriveCloudBackup): CloudBackup
}
