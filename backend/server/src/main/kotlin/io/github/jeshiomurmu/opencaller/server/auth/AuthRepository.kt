package io.github.jeshiomurmu.opencaller.server.auth

import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.RefreshTokens
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A signed-in user on one device. */
data class Session(
    val userId: UUID,
    val deviceId: UUID,
)

/** Outcome of presenting a refresh token. */
sealed interface Rotation {
    /** The token was valid: it is marked rotated and the new token is stored for the same device. */
    data class Rotated(
        val session: Session,
    ) : Rotation

    /** Unknown token. */
    data object Unknown : Rotation

    /** The token had expired (it has been deleted). */
    data object Expired : Rotation

    /** An already-rotated token came back: every refresh token of the device was revoked. */
    data object Reused : Rotation
}

/** Users, devices and refresh tokens (tables `users`, `devices`, `refresh_tokens`). */
class AuthRepository(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /**
     * After a verified code: finds or creates the user for [number] (marking it verified now and undeleted),
     * registers a new device of [flavor] and stores its first refresh token.
     */
    suspend fun signIn(
        number: E164,
        flavor: String,
        refreshTokenHash: String,
        refreshExpiresAt: Instant,
    ): Session =
        db.query {
            val now = now()
            val existing =
                Users
                    .selectAll()
                    .where { Users.e164 eq number.value }
                    .forUpdate()
                    .singleOrNull()
                    ?.get(Users.id)
            val userId =
                if (existing != null) {
                    Users.update({ Users.id eq existing }) {
                        it[verifiedAt] = now
                        it[deletedAt] = null
                    }
                    existing
                } else {
                    UUID.randomUUID().also { id ->
                        Users.insert {
                            it[Users.id] = id
                            it[e164] = number.value
                            it[verifiedAt] = now
                            it[createdAt] = now
                        }
                    }
                }
            val deviceId = UUID.randomUUID()
            Devices.insert {
                it[id] = deviceId
                it[Devices.userId] = userId
                it[Devices.flavor] = flavor
                it[lastSeen] = now
            }
            insertRefreshToken(refreshTokenHash, deviceId, refreshExpiresAt)
            Session(userId, deviceId)
        }

    /**
     * Rotates the refresh token with hash [oldHash] to [newHash] (expiring at [newExpiresAt]). The row is locked,
     * so of two concurrent uses of one token only the first rotates; the second counts as reuse.
     */
    suspend fun rotate(
        oldHash: String,
        newHash: String,
        newExpiresAt: Instant,
    ): Rotation =
        db.query {
            val now = now()
            val row =
                RefreshTokens
                    .selectAll()
                    .where { RefreshTokens.tokenHash eq oldHash }
                    .forUpdate()
                    .singleOrNull()
            val deviceId = row?.get(RefreshTokens.deviceId)
            val userId =
                deviceId?.let { id ->
                    Devices
                        .selectAll()
                        .where { Devices.id eq id }
                        .singleOrNull()
                        ?.get(Devices.userId)
                }
            when {
                row == null || deviceId == null || userId == null -> {
                    Rotation.Unknown
                }

                row[RefreshTokens.rotatedAt] != null -> {
                    RefreshTokens.deleteWhere { RefreshTokens.deviceId eq deviceId }
                    Rotation.Reused
                }

                !row[RefreshTokens.expiresAt].isAfter(now) -> {
                    RefreshTokens.deleteWhere { tokenHash eq oldHash }
                    Rotation.Expired
                }

                else -> {
                    RefreshTokens.update({ RefreshTokens.tokenHash eq oldHash }) { it[rotatedAt] = now }
                    insertRefreshToken(newHash, deviceId, newExpiresAt)
                    Devices.update({ Devices.id eq deviceId }) { it[lastSeen] = now }
                    Rotation.Rotated(Session(userId, deviceId))
                }
            }
        }

    private fun insertRefreshToken(
        hash: String,
        deviceId: UUID,
        expiresAt: Instant,
    ) {
        RefreshTokens.insert {
            it[tokenHash] = hash
            it[RefreshTokens.deviceId] = deviceId
            it[RefreshTokens.expiresAt] = expiresAt.atOffset(ZoneOffset.UTC)
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
