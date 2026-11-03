package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.db.AdminAudit
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.OffsetDateTime

/** One admin action. */
data class AuditEntry(
    val at: OffsetDateTime,
    val admin: String,
    val ip: String,
    val action: String,
    val target: String,
    val detail: String?,
)

/** The `admin_audit` table: who did what in the admin web, from where and when. Rows are never changed or deleted here. */
class AdminAuditLog(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Records [action] on [target] by [admin] from [ip]. */
    suspend fun record(
        admin: String,
        ip: String,
        action: String,
        target: String,
        detail: String? = null,
    ) {
        db.query {
            AdminAudit.insert {
                it[at] = OffsetDateTime.now(clock)
                it[AdminAudit.admin] = admin
                it[AdminAudit.ip] = ip
                it[AdminAudit.action] = action
                it[AdminAudit.target] = target
                it[AdminAudit.detail] = detail?.take(MAX_DETAIL)
            }
        }
    }

    /** The latest [limit] entries, newest first. */
    suspend fun recent(limit: Int = RECENT): List<AuditEntry> =
        db.query {
            AdminAudit
                .selectAll()
                .orderBy(AdminAudit.id to SortOrder.DESC)
                .limit(limit)
                .map {
                    AuditEntry(
                        it[AdminAudit.at],
                        it[AdminAudit.admin],
                        it[AdminAudit.ip],
                        it[AdminAudit.action],
                        it[AdminAudit.target],
                        it[AdminAudit.detail],
                    )
                }
        }

    private companion object {
        const val MAX_DETAIL = 2_000
        const val RECENT = 200
    }
}
