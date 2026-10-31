package io.github.jeshiomurmu.opencaller.core.ml.link

import io.github.jeshiomurmu.opencaller.core.ml.assets.MlAssets
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A brand phishing links imitate: the [labels] its name is written as in hosts and its own [domains]. */
@Serializable
data class Brand(
    val name: String,
    val labels: List<String>,
    val domains: List<String>,
)

/** The link data in `assets/ml/brands.json`. */
@Serializable
data class LinkData(
    val brands: List<Brand> = emptyList(),
    val shorteners: Set<String> = emptySet(),
    val suspiciousTlds: Set<String> = emptySet(),
    val hostWords: Set<String> = emptySet(),
)

/** [LinkData] read once from the assets; unreadable data leaves it empty (links are then only checked against the list). */
@Singleton
class BrandCatalog
    @Inject
    constructor(
        private val assets: MlAssets,
    ) {
        /** The brands, shorteners, top-level domains and host words. */
        val data: LinkData by lazy(::load)

        private fun load(): LinkData =
            try {
                parse(assets.read(ASSET))
            } catch (e: IOException) {
                Timber.e(e, "Link data not readable")
                LinkData()
            } catch (e: SerializationException) {
                Timber.e(e, "Link data malformed")
                LinkData()
            }

        /** Parsing, shared with the tests. */
        companion object {
            /** The asset holding the link data. */
            const val ASSET = "ml/brands.json"
            private val JSON = Json { ignoreUnknownKeys = true }

            /** The link data in [json] (the format of `brands.json`). */
            fun parse(json: String): LinkData = JSON.decodeFromString<LinkData>(json)
        }
    }
