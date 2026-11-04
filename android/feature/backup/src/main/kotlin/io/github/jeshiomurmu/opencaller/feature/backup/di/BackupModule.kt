package io.github.jeshiomurmu.opencaller.feature.backup.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.backup.BackupFiles
import io.github.jeshiomurmu.opencaller.feature.backup.ResolverBackupFiles

/** Backup files through the content resolver. */
@Module
@InstallIn(SingletonComponent::class)
internal interface BackupModule {
    @Binds
    fun backupFiles(impl: ResolverBackupFiles): BackupFiles
}
