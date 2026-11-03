package io.github.jeshiomurmu.opencaller.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaimRepository
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaims
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessDirectory
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessDirectoryRepository
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessLogoStore
import io.github.jeshiomurmu.opencaller.core.network.BusinessApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import java.io.File
import javax.inject.Singleton

/** Business claims, verified businesses' profiles and the verified-business directory on the phone (Phase 9). */
@Module
@InstallIn(SingletonComponent::class)
interface BusinessModule {
    /** Business claims and verified businesses' profiles. */
    @Binds
    fun businessClaims(impl: BusinessClaimRepository): BusinessClaims

    /** The verified-business directory on the phone. */
    @Binds
    fun businessDirectory(impl: BusinessDirectoryRepository): BusinessDirectory

    /** Providers. */
    companion object {
        /** Business claims, profiles and the verified-business directory. */
        @Provides
        fun businessApi(network: OpenCallerNetwork): BusinessApi = network.businessApi

        /** Verified businesses' logos, in the no-backup directory (the directory update fetches them again). */
        @Provides
        @Singleton
        fun businessLogoStore(
            @ApplicationContext context: Context,
            dispatchers: DispatcherProvider,
        ): BusinessLogoStore = BusinessLogoStore(File(context.noBackupFilesDir, "business_logos"), dispatchers)
    }
}
