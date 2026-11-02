package io.github.jeshiomurmu.opencaller.server.registry

/** One sender ID as a registry states it. */
data class SenderIdRecord(
    val countryIso: String,
    val header: String,
    val brandName: String,
    val category: String,
)

/**
 * What an importer mapped from one fetch: the valid [records], the lines it had to skip ([rejected], e.g.
 * "line 7: invalid header") and whether the extract is the source's [complete] list for its country (then rows
 * of this source missing from it are removed).
 */
data class ImportBatch(
    val records: List<SenderIdRecord>,
    val rejected: List<String>,
    val complete: Boolean,
)

/** What [Importer.upsert] changed. */
data class ImportResult(
    val inserted: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val rejected: Int,
) {
    /** Rows added or changed. */
    val upserted: Int get() = inserted + updated
}

/**
 * A sender-ID registry source (docs/prd.md F-BZ-03): [fetch] gets its raw extract, [map] turns it into records
 * (rejecting what doesn't fit), [upsert] writes them into `sender_ids` under [source]. Runs are recorded by
 * [SenderIdImportJob].
 */
interface Importer<Raw> {
    /** Source name stored with each row (`sender_ids.source`), e.g. `csv-in-dlt`. */
    val source: String

    /** Country the extract covers. */
    val countryIso: String

    /** Gets the raw extract. Throws [RegistryUnavailableException] if the registry can't be read at all. */
    suspend fun fetch(): Raw

    /** Maps the extract to records. */
    fun map(raw: Raw): ImportBatch

    /** Writes [batch] into `sender_ids`. */
    suspend fun upsert(batch: ImportBatch): ImportResult
}

/** The registry can't be read: no documented access, no configuration, or it can't be reached. */
class RegistryUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Runs one importer: fetch, map, upsert. */
suspend fun <Raw> Importer<Raw>.runOnce(): ImportResult = upsert(map(fetch()))

/**
 * A registry OpenCaller has no documented access to yet. It is listed (so the admin web and the import log show
 * the gap) but never fetches anything: [fetch] always throws [RegistryUnavailableException] with [reason].
 * Replace it with a real importer once access and the extract's documented format exist.
 */
class UnavailableRegistryImporter(
    override val source: String,
    override val countryIso: String,
    val registry: String,
    val reason: String,
) : Importer<Nothing> {
    override suspend fun fetch(): Nothing = throw RegistryUnavailableException("$registry: $reason")

    override fun map(raw: Nothing): ImportBatch = raw

    override suspend fun upsert(batch: ImportBatch): ImportResult = throw RegistryUnavailableException("$registry: $reason")
}

/**
 * The official registries OpenCaller would import from (docs/prd.md F-BZ-03), none with documented access as of
 * 2026-11-02 (docs/phase.md, "Sender-ID registries"): the CSV importer takes extracts obtained by hand meanwhile.
 */
val placeholderRegistries: List<UnavailableRegistryImporter> =
    listOf(
        UnavailableRegistryImporter(
            "dlt-in",
            "IN",
            "India DLT (TRAI)",
            "no documented access: DLT data comes through operator portals and partners; TRAI's public header lookup " +
                "wasn't reachable to check its terms and format",
        ),
        UnavailableRegistryImporter(
            "ssir-sg",
            "SG",
            "Singapore SMS Sender ID Registry (SGNIC)",
            "no documented access: SGNIC publishes no list of registered sender IDs",
        ),
        UnavailableRegistryImporter(
            "acma-au",
            "AU",
            "Australia SMS Sender ID Register (ACMA)",
            "no documented access: no public list or API for the register found",
        ),
        UnavailableRegistryImporter(
            "comreg-ie",
            "IE",
            "Ireland SMS Sender ID Registry (ComReg)",
            "no documented access: ComReg offers a status check for owners, no public list or API found",
        ),
    )
