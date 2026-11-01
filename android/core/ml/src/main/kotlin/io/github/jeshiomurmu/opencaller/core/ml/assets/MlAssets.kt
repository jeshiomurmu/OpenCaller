package io.github.jeshiomurmu.opencaller.core.ml.assets

import java.io.IOException

/** Reads the data files that ship with `core:ml` (`src/main/assets/ml/`): bank templates and protected brands. */
fun interface MlAssets {
    /**
     * The text of asset [name] (e.g. `ml/banks.json`).
     *
     * @throws IOException when it can't be read.
     */
    fun read(name: String): String
}
