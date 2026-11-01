package io.github.jeshiomurmu.opencaller.core.ml.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.ml.assets.AndroidMlAssets
import io.github.jeshiomurmu.opencaller.core.ml.assets.MlAssets
import io.github.jeshiomurmu.opencaller.core.ml.classify.RuleSmsClassifier
import io.github.jeshiomurmu.opencaller.core.ml.classify.SmsClassifier

/** `core:ml` bindings. The known-bad host list (`BadHostPrefixes`) is bound by `core:telephony`, which reads it from `core:data`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MlModule {
    @Binds
    abstract fun classifier(impl: RuleSmsClassifier): SmsClassifier

    @Binds
    abstract fun assets(impl: AndroidMlAssets): MlAssets
}
