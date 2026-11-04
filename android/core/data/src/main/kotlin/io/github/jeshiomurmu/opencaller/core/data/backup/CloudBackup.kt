package io.github.jeshiomurmu.opencaller.core.data.backup

import android.content.Intent
import android.content.IntentSender
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult

/** Access to the cloud folder: granted with a short-lived token, or the user must agree first. */
sealed interface CloudAuthorization {
    /** Access granted. */
    data class Granted(
        val token: String,
    ) : CloudAuthorization

    /** Show [request] (Google's consent screen); its result goes to [CloudBackup.tokenFrom]. */
    data class NeedsConsent(
        val request: IntentSender,
    ) : CloudAuthorization
}

/**
 * A cloud place for the encrypted backup file (docs/architecture.md §3: `BackupDestination`): Google Drive's hidden
 * app folder in the play build, none in the foss build (which backs up to a file the user picks, as play can too).
 * The file is encrypted before it leaves the phone; the cloud only stores it.
 */
interface CloudBackup {
    /** `true` if this build has a cloud destination. */
    val available: Boolean

    /** Asks for access to the app folder (`drive.appdata` scope only). */
    suspend fun authorize(): AppResult<CloudAuthorization>

    /** The token from the consent screen's result [data]. */
    fun tokenFrom(data: Intent?): AppResult<String>

    /** Stores [file] as the backup, replacing older ones. */
    suspend fun upload(
        token: String,
        file: ByteArray,
    ): AppResult<Unit>

    /** The newest backup, or `null` when there is none. */
    suspend fun download(token: String): AppResult<ByteArray?>
}

/** No cloud destination (foss, and tests). */
class NoCloudBackup : CloudBackup {
    override val available: Boolean = false

    override suspend fun authorize(): AppResult<CloudAuthorization> = AppResult.Failure(AppError.Validation("no_cloud"))

    override fun tokenFrom(data: Intent?): AppResult<String> = AppResult.Failure(AppError.Validation("no_cloud"))

    override suspend fun upload(
        token: String,
        file: ByteArray,
    ): AppResult<Unit> = AppResult.Failure(AppError.Validation("no_cloud"))

    override suspend fun download(token: String): AppResult<ByteArray?> = AppResult.Failure(AppError.Validation("no_cloud"))
}
