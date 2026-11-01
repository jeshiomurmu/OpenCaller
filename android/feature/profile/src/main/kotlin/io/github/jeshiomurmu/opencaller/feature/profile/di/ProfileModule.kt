package io.github.jeshiomurmu.opencaller.feature.profile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.profile.AvatarScaler
import io.github.jeshiomurmu.opencaller.feature.profile.PictureScaler

/** Bindings of the profile feature. */
@Module
@InstallIn(SingletonComponent::class)
interface ProfileModule {
    /** Profile pictures are scaled on the phone before upload. */
    @Binds
    fun pictureScaler(impl: AvatarScaler): PictureScaler
}
