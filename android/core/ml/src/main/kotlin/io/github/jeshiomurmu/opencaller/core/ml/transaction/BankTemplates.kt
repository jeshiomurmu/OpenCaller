package io.github.jeshiomurmu.opencaller.core.ml.transaction

import io.github.jeshiomurmu.opencaller.core.ml.assets.MlAssets
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Which way money moved. */
enum class Direction {
    /** Money left the account (spent, sent, debited, cash out). */
    DEBIT,

    /** Money came in (credited, received, deposit). */
    CREDIT,
}

/** One bank's message templates, compiled. [senders] are matched without regard to case. */
data class Bank(
    val id: String,
    val name: String,
    val country: String,
    val currency: String,
    val senders: Set<String>,
    val templates: List<BankTemplate>,
)

/** One message format of a [Bank]: a pattern with the named groups `amount` and, if present, `last4` and `merchant`. */
data class BankTemplate(
    val direction: Direction,
    val pattern: Regex,
)

@Serializable
internal data class BankFile(
    val version: Int,
    val banks: List<BankEntry>,
)

@Serializable
internal data class BankEntry(
    val id: String,
    val name: String,
    val country: String,
    val currency: String,
    val senders: List<String>,
    val templates: List<TemplateEntry>,
)

@Serializable
internal data class TemplateEntry(
    val direction: Direction,
    val pattern: String,
)

/** The banks in `assets/ml/banks.json`, read once. Unreadable data leaves the list empty (no transaction is parsed). */
@Singleton
class BankTemplates
    @Inject
    constructor(
        private val assets: MlAssets,
    ) {
        /** The banks, in file order. */
        val banks: List<Bank> by lazy(::load)

        private fun load(): List<Bank> =
            try {
                parse(assets.read(ASSET))
            } catch (e: IOException) {
                Timber.e(e, "Bank templates not readable")
                emptyList()
            } catch (e: SerializationException) {
                Timber.e(e, "Bank templates malformed")
                emptyList()
            } catch (e: IllegalArgumentException) {
                // A pattern that doesn't compile (java.util.regex.PatternSyntaxException).
                Timber.e(e, "Bank template pattern invalid")
                emptyList()
            }

        /** Parsing, shared with the tests. */
        companion object {
            /** The asset holding the templates. */
            const val ASSET = "ml/banks.json"
            private val JSON = Json { ignoreUnknownKeys = true }
            private val FIELDS =
                mapOf(
                    "{amount}" to """(?<amount>\d[\d,]*(?:\.\d{1,2})?)""",
                    "{last4}" to """(?<last4>\d{4})""",
                    "{merchant}" to """(?<merchant>[^.\n]+?)""",
                )

            /** The banks in [json] (the format of `banks.json`). */
            fun parse(json: String): List<Bank> =
                JSON.decodeFromString<BankFile>(json).banks.map { entry ->
                    Bank(
                        id = entry.id,
                        name = entry.name,
                        country = entry.country,
                        currency = entry.currency,
                        senders = entry.senders.mapTo(mutableSetOf()) { it.lowercase() },
                        templates = entry.templates.map { BankTemplate(it.direction, compile(it.pattern)) },
                    )
                }

            private fun compile(pattern: String): Regex =
                Regex(FIELDS.entries.fold(pattern) { text, (field, group) -> text.replace(field, group) }, RegexOption.IGNORE_CASE)
        }
    }
