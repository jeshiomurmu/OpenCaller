package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ContactRequests
import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStoreException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The signed-in user's own profile (docs/prd.md F-SO-01, F-SO-04, F-SO-07): name, picture, visibility and
 * incognito; and account deletion, which takes the profile out of every API answer at once and leaves the rest
 * to [RetentionJob].
 */
class ProfileService(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val clock: Clock,
) {
    /** The profile of [userId]; 401 if the account was deleted. */
    suspend fun me(userId: UUID): MeResponse {
        val since = now().minusDays(VISIT_DAYS)
        val (row, counts) =
            db.query {
                val row = activeUser(userId)
                row to Triple(visits(userId, VisitKind.VIEW, since), visits(userId, VisitKind.SEARCH, since), pendingRequests(userId))
            }
        val tier = SupporterTier.of(row[Users.supporterTier])
        val avatarKey = row[Users.avatarKey]
        return MeResponse(
            e164 = row[Users.e164],
            displayName = row[Users.displayName],
            avatarKey = avatarKey,
            avatarUrl = avatarKey?.let { objects.presignGet(it, AVATAR_URL_SECONDS).toString() },
            visibility = row[Users.visibility].sqlValue,
            incognito = row[Users.incognito],
            badge = tier.badge,
            supporter = tier != SupporterTier.NONE,
            viewerCount = counts.first,
            searcherCount = counts.second,
            pendingContactRequests = counts.third,
        )
    }

    /** Replaces the editable profile of [userId] with [request]. */
    suspend fun update(
        userId: UUID,
        request: UpdateMeRequest,
    ): MeResponse {
        val name = request.displayName?.trim()
        requireValid(name == null || (isPlainText(name, MAX_NAME) && '\n' !in name)) {
            invalidProfile("displayName must be 1 to $MAX_NAME characters on one line, or null.")
        }
        val visibility =
            Visibility.entries.firstOrNull { it.sqlValue == request.visibility }
                ?: throw invalidProfile("visibility must be everyone, registered_only or nobody.")
        val current = db.query { activeUser(userId)[Users.avatarKey] }
        val avatar = request.avatarKey
        if (avatar != null && avatar != current) checkUploadedAvatar(userId, avatar)
        db.query {
            Users.update({ Users.id eq userId }) {
                it[displayName] = name
                it[Users.visibility] = visibility
                it[incognito] = request.incognito
                it[avatarKey] = avatar
            }
        }
        if (current != null && current != avatar) deleteQuietly(current)
        return me(userId)
    }

    /** A presigned URL for a new profile picture of [userId]; the picture counts once `PUT /v1/me` sets its key. */
    suspend fun avatarUpload(userId: UUID): AvatarUploadResponse {
        db.query { activeUser(userId) }
        val key = "${avatarPrefix(userId)}${UUID.randomUUID()}.jpg"
        return AvatarUploadResponse(
            uploadUrl = objects.presignPut(key, AVATAR_TYPE, UPLOAD_URL_SECONDS).toString(),
            avatarKey = key,
            contentType = AVATAR_TYPE,
            maxBytes = MAX_AVATAR_BYTES,
            expiresInSeconds = UPLOAD_URL_SECONDS,
        )
    }

    /**
     * Deletes [userId]'s account: from now on no lookup, search or profile shows it, every device is signed out,
     * and the views and contact requests involving it are gone. [RetentionJob] removes the rest (the reports the
     * user wrote, the picture, the row itself) on its next run.
     */
    suspend fun delete(userId: UUID) {
        db.query {
            activeUser(userId)
            Users.update({ Users.id eq userId }) {
                it[deletedAt] = now()
                it[displayName] = null
                it[visibility] = Visibility.NOBODY
                it[incognito] = true
            }
            Devices.deleteWhere { Devices.userId eq userId }
            ProfileViews.deleteWhere { (ProfileViews.viewedUserId eq userId) or (ProfileViews.viewerUserId eq userId) }
            ContactRequests.deleteWhere { (ContactRequests.requesterId eq userId) or (ContactRequests.targetId eq userId) }
        }
    }

    private suspend fun checkUploadedAvatar(
        userId: UUID,
        key: String,
    ) {
        requireValid(key.startsWith(avatarPrefix(userId)) && AVATAR_KEY.matches(key.substringAfterLast('/'))) {
            invalidProfile("avatarKey must come from POST /v1/me/avatar.")
        }
        val info = objects.head(key) ?: throw invalidProfile("Upload the picture to uploadUrl before setting avatarKey.")
        if (info.size !in 1..MAX_AVATAR_BYTES || info.contentType != AVATAR_TYPE) {
            deleteQuietly(key)
            throw invalidProfile("The picture must be a JPEG of at most $MAX_AVATAR_BYTES bytes.")
        }
    }

    private suspend fun deleteQuietly(key: String) {
        // A leftover picture is only storage and the account no longer points at it, so a failure is logged, not
        // shown to the user.
        try {
            objects.delete(key)
        } catch (e: ObjectStoreException) {
            log.warn("Could not delete an unused profile picture", e)
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** Longest display name. */
        const val MAX_NAME = 60

        /** Largest profile picture (the app sends a 512 px JPEG, far smaller). */
        const val MAX_AVATAR_BYTES = 1_000_000L

        /** Profile pictures are JPEG. */
        const val AVATAR_TYPE = "image/jpeg"

        /** How long an upload URL works. */
        const val UPLOAD_URL_SECONDS = 600

        /** How long a picture URL works. */
        const val AVATAR_URL_SECONDS = 900

        /** Visits are kept and counted for 90 days (docs/architecture.md §7). */
        const val VISIT_DAYS = 90L

        private val AVATAR_KEY = Regex("[0-9a-f-]{36}\\.jpg")
        private val log = LoggerFactory.getLogger(ProfileService::class.java)

        /** Object-store prefix of [userId]'s pictures. */
        fun avatarPrefix(userId: UUID) = "avatars/$userId/"
    }
}

/** The undeleted user [userId]; 401 if there is none (the account was deleted after the token was issued). */
internal fun JdbcTransaction.activeUser(userId: UUID): ResultRow =
    Users
        .selectAll()
        .where { (Users.id eq userId) and Users.deletedAt.isNull() }
        .singleOrNull()
        ?: throw ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "This account no longer exists")

private fun JdbcTransaction.visits(
    userId: UUID,
    kind: VisitKind,
    since: OffsetDateTime,
): Int =
    ProfileViews
        .select(ProfileViews.id)
        .where { (ProfileViews.viewedUserId eq userId) and (ProfileViews.kind eq kind.value) and (ProfileViews.at greaterEq since) }
        .count()
        .toInt()

private fun JdbcTransaction.pendingRequests(userId: UUID): Int =
    ContactRequests
        .select(ContactRequests.id)
        .where { (ContactRequests.targetId eq userId) and (ContactRequests.status eq ReviewStatus.PENDING) }
        .count()
        .toInt()

private fun invalidProfile(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Invalid profile", detail)
