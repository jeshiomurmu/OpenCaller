package io.github.jeshiomurmu.opencaller.core.ml.assets

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [MlAssets] from the app's assets. */
class AndroidMlAssets
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MlAssets {
        override fun read(name: String): String =
            context.assets
                .open(name)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
    }
