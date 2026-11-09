package io.github.jeshiomurmu.opencaller.core.data.family

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.ScamCard
import io.github.jeshiomurmu.opencaller.core.network.FamilyApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ScamFeedDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

/** Where the last fetched scam feed of each country is kept for offline use. */
data class ScamFeedCache(
    val directory: File,
)

/** The scam cards to show and whether they come from the offline copy. */
data class ScamFeedCards(
    val cards: List<ScamCard>,
    val offline: Boolean,
)

/** The scam cards for the Protection tab ([ScamFeedRepository]; a fake in tests). */
fun interface ScamFeed {
    /** The cards of [country] for a reader of [language], fetched or from the offline copy. */
    suspend fun cards(
        country: String,
        language: String,
    ): AppResult<ScamFeedCards>
}

/**
 * The scam feed (docs/prd.md F-PR-15): admin-curated cards for the user's country, fetched without sign-in (the
 * request names only the country) and kept as a JSON file so the Protection tab shows them offline too.
 */
class ScamFeedRepository(
    private val api: FamilyApi,
    private val cache: ScamFeedCache,
    private val dispatchers: DispatcherProvider,
) : ScamFeed {
    /**
     * The cards of [country] (ISO 3166 alpha-2) for a reader of [language]: fetched, or the last copy when offline.
     * Fails only when neither is available.
     */
    override suspend fun cards(
        country: String,
        language: String,
    ): AppResult<ScamFeedCards> {
        val iso = country.uppercase(Locale.ROOT)
        if (!ISO.matches(iso)) return AppResult.Failure(AppError.Validation("invalid_country"))
        return when (val fetched = apiCall { api.scamFeed(iso) }) {
            is AppResult.Success -> {
                save(iso, fetched.value)
                AppResult.Success(ScamFeedCards(pick(fetched.value, language), offline = false))
            }

            is AppResult.Failure -> {
                val kept = load(iso)
                if (kept != null) AppResult.Success(ScamFeedCards(pick(kept, language), offline = true)) else fetched
            }
        }
    }

    private suspend fun save(
        iso: String,
        feed: ScamFeedDto,
    ) = withContext<Unit>(dispatchers.io) {
        try {
            cache.directory.mkdirs()
            val file = fileOf(iso)
            val partial = File(file.path + ".part")
            partial.writeText(OpenCallerNetwork.json.encodeToString(ScamFeedDto.serializer(), feed))
            if (!partial.renameTo(file)) Timber.w("Scam feed copy not replaced")
        } catch (e: IOException) {
            Timber.w(e, "Scam feed copy not written")
        }
    }

    private suspend fun load(iso: String): ScamFeedDto? =
        withContext(dispatchers.io) {
            val file = fileOf(iso)
            try {
                if (file.exists()) OpenCallerNetwork.json.decodeFromString(ScamFeedDto.serializer(), file.readText()) else null
            } catch (e: IOException) {
                Timber.w(e, "Scam feed copy not read")
                null
            } catch (e: SerializationException) {
                Timber.w(e, "Scam feed copy unreadable")
                null
            }
        }

    private fun fileOf(iso: String) = File(cache.directory, "$iso.json")

    /** Choosing cards by language. */
    companion object {
        private val ISO = Regex("^[A-Z]{2}$")
        private const val ENGLISH = "en"

        /**
         * The cards in the reader's [language] (`bn`, `bn-BD`: the part before `-` is compared) when the feed has any,
         * else the English ones, newest first as the server sends them.
         */
        fun pick(
            feed: ScamFeedDto,
            language: String,
        ): List<ScamCard> {
            val cards = feed.cards.map { it.toScamCard() }
            val reader = baseOf(language)
            val own = cards.filter { baseOf(it.language) == reader }
            return own.ifEmpty { cards.filter { baseOf(it.language) == ENGLISH } }
        }

        private fun baseOf(language: String) = language.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
    }
}
