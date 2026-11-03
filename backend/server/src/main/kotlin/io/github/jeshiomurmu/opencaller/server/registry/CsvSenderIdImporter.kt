package io.github.jeshiomurmu.opencaller.server.registry

import io.github.jeshiomurmu.opencaller.server.sync.RegistryRules

/**
 * Imports a sender-ID extract obtained by hand (a registry's download, a partner's file) as CSV: UTF-8, a header
 * line `header,brand_name,category`, then one sender ID per line; `#` lines are comments; a field containing a
 * comma or quote is written in double quotes ("" for a quote). Invalid lines are skipped and reported, never
 * guessed at. [complete] = the file is the source's whole list for [countryIso] (rows it no longer has are removed).
 */
class CsvSenderIdImporter(
    override val source: String,
    override val countryIso: String,
    private val store: SenderIdStore,
    private val complete: Boolean,
    private val read: suspend () -> String,
) : Importer<String> {
    init {
        require(RegistryRules.SOURCE.matches(source)) { "invalid source name $source" }
        require(RegistryRules.ISO.matches(countryIso)) { "invalid country $countryIso" }
    }

    override suspend fun fetch(): String = read()

    override fun map(raw: String): ImportBatch {
        val lines =
            raw
                .removePrefix(BOM)
                .lines()
                .withIndex()
                .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
        val header = lines.firstOrNull()
        if (header == null || parseLine(header.value)?.map { it.trim().lowercase() } != COLUMNS) {
            val problem = header?.let { "line ${it.index + 1}: the first line must be ${COLUMNS.joinToString(",")}" } ?: "the file is empty"
            return ImportBatch(emptyList(), listOf(problem), complete = false)
        }
        val records = mutableListOf<SenderIdRecord>()
        val rejected = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        lines.drop(1).take(MAX_ROWS).forEach { (index, line) ->
            val record = recordOf(line)
            when {
                record == null -> rejected += "line ${index + 1}: invalid row"
                !seen.add(record.header) -> rejected += "line ${index + 1}: duplicate header ${record.header}"
                else -> records += record
            }
        }
        if (lines.size - 1 > MAX_ROWS) rejected += "more than $MAX_ROWS rows; the rest was skipped"
        // A file with skipped rows isn't trusted as complete: removing rows because a line was malformed would be wrong.
        return ImportBatch(records, rejected, complete = complete && rejected.isEmpty())
    }

    override suspend fun upsert(batch: ImportBatch): ImportResult = store.apply(source, countryIso, batch)

    private fun recordOf(line: String): SenderIdRecord? =
        parseLine(line)
            ?.map { it.trim() }
            ?.takeIf { cells ->
                cells.size == COLUMNS.size &&
                    RegistryRules.HEADER.matches(cells[0]) &&
                    RegistryRules.isName(cells[1]) &&
                    RegistryRules.SLUG.matches(cells[2])
            }?.let { (header, brand, category) -> SenderIdRecord(countryIso, header, brand, category) }

    /** CSV parsing. */
    companion object {
        /** The required first line. */
        val COLUMNS = listOf("header", "brand_name", "category")

        /** Most rows read from one file. */
        const val MAX_ROWS = 200_000

        private const val BOM = "﻿"

        /** Splits one CSV line into fields, or `null` if its quotes don't match. */
        fun parseLine(line: String): List<String>? {
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> field.append('"').also { i++ }
                    c == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                    !quoted && c == ',' -> fields += field.toString().also { field.clear() }
                    else -> field.append(c)
                }
                i++
            }
            if (quoted) return null
            fields += field.toString()
            return fields
        }
    }
}
