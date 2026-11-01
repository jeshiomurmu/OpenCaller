package io.github.jeshiomurmu.opencaller.feature.otp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpAlarm
import io.github.jeshiomurmu.opencaller.feature.otp.OtpGuardAlarm

/** The OTP guard's warnings on screen. */
@Module
@InstallIn(SingletonComponent::class)
internal interface OtpGuardModule {
    @Binds
    fun alarm(impl: OtpGuardAlarm): OtpAlarm
}
