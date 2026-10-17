package io.github.jeshiomurmu.opencaller.server.lists

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.ListVersions
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.format.DateTimeFormatter

/** A downloadable list file. [baseVersion] is set for deltas (apply to that version to get [version]). */
@Serializable
data class ListFileRef(
    val version: Long,
    val baseVersion: Long? = null,
    val url: String,
    val sha256: String,
    val createdAt: String,
)

/** `GET /v1/lists/{countryIso}/manifest` response. */
@Serializable
data class ListManifest(
    val countryIso: String,
    val format: Int,
    val version: Long,
    val full: ListFileRef,
    val deltas: List<ListFileRef>,
)

/** A list file's bytes and checksum. */
class ListFileContent(
    val bytes: ByteArray,
    val sha256: String,
)

/**
 * Manifest and downloads of the published lists. The manifest names the latest full file and the deltas of the
 * last [MAX_DELTAS] versions, so an app that missed a few nightly builds chains deltas instead of downloading
 * everything again. Files are served through the API (MinIO stays internal) and never change once published.
 */
class ListService(
    private val db: ServerDatabase,
    private val store: ObjectStore,
) {
    private val regions = PhoneNumberUtil.getInstance().supportedRegions

    /** Manifest for [rawIso]. */
    suspend fun manifest(rawIso: String): ListManifest {
        val iso = countryIso(rawIso)
        val rows =
            db.query {
                ListVersions
                    .selectAll()
                    .where { ListVersions.countryIso eq iso }
                    .orderBy(ListVersions.version, SortOrder.DESC)
                    .limit(2 * (MAX_DELTAS + 1))
                    .toList()
            }
        val full = rows.firstOrNull { it[ListVersions.kind] == ListKind.FULL.sqlValue } ?: notFound(iso)
        val latest = full[ListVersions.version]
        val deltas =
            rows
                .filter { it[ListVersions.kind] == ListKind.DELTA.sqlValue && it[ListVersions.version] > latest - MAX_DELTAS }
                .map { ref(iso, it[ListVersions.version], ListKind.DELTA, it[ListVersions.sha256], it[ListVersions.createdAt]) }
        return ListManifest(
            countryIso = iso,
            format = ListFormat.FORMAT_VERSION,
            version = latest,
            full = ref(iso, latest, ListKind.FULL, full[ListVersions.sha256], full[ListVersions.createdAt]),
            deltas = deltas,
        )
    }

    /** The file [rawKind] (`full` or `delta`) of version [rawVersion] for [rawIso]. */
    suspend fun file(
        rawIso: String,
        rawVersion: String,
        rawKind: String,
    ): ListFileContent {
        val iso = countryIso(rawIso)
        val version = rawVersion.toLongOrNull()?.takeIf { it > 0 } ?: notFound(iso)
        val kind = ListKind.entries.firstOrNull { it.sqlValue == rawKind } ?: notFound(iso)
        val row =
            db.query {
                ListVersions
                    .selectAll()
                    .where {
                        (ListVersions.countryIso eq iso) and (ListVersions.version eq version) and (ListVersions.kind eq kind.sqlValue)
                    }.singleOrNull()
            } ?: notFound(iso)
        val bytes = store.get(row[ListVersions.objectKey]) ?: notFound(iso)
        return ListFileContent(bytes, row[ListVersions.sha256])
    }

    private fun countryIso(raw: String): String {
        val iso = raw.uppercase()
        if (iso.length != 2 || iso !in regions) {
            throw ApiProblem(HttpStatusCode.BadRequest, "invalid_country", "Unknown country", "Use an ISO 3166-1 alpha-2 code such as BD.")
        }
        return iso
    }

    private fun ref(
        iso: String,
        version: Long,
        kind: ListKind,
        sha256: String,
        createdAt: java.time.OffsetDateTime,
    ) = ListFileRef(
        version = version,
        baseVersion = if (kind == ListKind.DELTA) version - 1 else null,
        url = "/v1/lists/$iso/files/$version/${kind.sqlValue}",
        sha256 = sha256,
        createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt.toInstant()),
    )

    private fun notFound(iso: String): Nothing =
        throw ApiProblem(HttpStatusCode.NotFound, "list_not_found", "No list published", "Nothing for $iso yet.")

    /** Manifest size. */
    companion object {
        /** Deltas listed in a manifest. */
        const val MAX_DELTAS = 14
    }
}
