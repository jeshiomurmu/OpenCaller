package io.github.jeshiomurmu.opencaller.feature.backup

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupError
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupRepository
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupResult
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudAuthorization
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudBackup
import io.github.jeshiomurmu.opencaller.core.data.backup.RestoreSummary
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What went wrong. */
enum class BackupProblem {
    NOT_ENTITLED,
    PASSPHRASE_TOO_SHORT,
    PASSPHRASES_DIFFER,
    WRONG_PASSPHRASE,
    NOT_A_BACKUP,
    NO_DRIVE_BACKUP,
    DRIVE_REFUSED,
    OFFLINE,
    FAILED,
}

/** What was done. */
sealed interface BackupNote {
    /** The backup was saved. */
    data object Saved : BackupNote

    /** The backup was restored. */
    data class Restored(
        val summary: RestoreSummary,
    ) : BackupNote
}

/** Which Drive action waits for Google's consent screen. */
enum class CloudAction { UPLOAD, DOWNLOAD }

/**
 * Settings → Backup and restore.
 *
 * @property consent Google's consent screen to show, once.
 */
data class BackupUiState(
    val entitled: Boolean = false,
    val cloudAvailable: Boolean = false,
    val passphrase: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val consent: IntentSender? = null,
    val note: BackupNote? = null,
    val problem: BackupProblem? = null,
)

/** What the user did. */
sealed interface BackupEvent {
    /** The passphrase field changed. */
    data class Passphrase(
        val text: String,
    ) : BackupEvent

    /** The confirmation field changed. */
    data class Confirm(
        val text: String,
    ) : BackupEvent

    /** The user picked where to save the file ([uri] from `CreateDocument`). */
    data class SaveTo(
        val uri: String,
    ) : BackupEvent

    /** The user picked a backup file to restore. */
    data class RestoreFrom(
        val uri: String,
    ) : BackupEvent

    /** Back up to a file was tapped but [BackupViewModel.backupReady] refuses: say why. */
    data object ExplainNotReady : BackupEvent

    /** Back up to Google Drive. */
    data object DriveBackup : BackupEvent

    /** Restore from Google Drive. */
    data object DriveRestore : BackupEvent

    /** Google's consent screen is showing. */
    data object ConsentShown : BackupEvent

    /** Google's consent screen returned [data]. */
    data class ConsentResult(
        val data: Intent?,
    ) : BackupEvent
}

/** Backs up and restores (docs/prd.md F-UT-05); see [BackupRepository]. */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val backups: BackupRepository,
        private val files: BackupFiles,
        private val cloud: CloudBackup,
        entitlements: EntitlementProvider,
    ) : ViewModel() {
        private val local = MutableStateFlow(BackupUiState(cloudAvailable = cloud.available))
        private var waiting: CloudAction? = null

        /** Screen state. */
        val uiState: StateFlow<BackupUiState> =
            combine(local, entitlements.entitlement) { state, entitlement -> state.copy(entitled = entitlement.entitled) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), local.value)

        /** True when a backup may be made with what was typed (restoring only needs the passphrase). */
        fun backupReady(): BackupProblem? {
            val state = uiState.value
            return when {
                !state.entitled -> BackupProblem.NOT_ENTITLED
                state.passphrase.length < MIN_LENGTH -> BackupProblem.PASSPHRASE_TOO_SHORT
                state.passphrase != state.confirm -> BackupProblem.PASSPHRASES_DIFFER
                else -> null
            }
        }

        /** Handles [event]. */
        fun onEvent(event: BackupEvent) {
            when (event) {
                is BackupEvent.Passphrase -> {
                    local.update { it.copy(passphrase = event.text, problem = null, note = null) }
                }

                is BackupEvent.Confirm -> {
                    local.update { it.copy(confirm = event.text, problem = null, note = null) }
                }

                is BackupEvent.SaveTo -> {
                    launchBusy {
                        create()?.let { bytes ->
                            finish(files.write(event.uri, bytes)) { BackupNote.Saved }
                        }
                    }
                }

                is BackupEvent.RestoreFrom -> {
                    launchBusy { files.read(event.uri).let { read -> restoreFrom(read) } }
                }

                BackupEvent.ExplainNotReady -> {
                    local.update { it.copy(problem = backupReady(), note = null) }
                }

                BackupEvent.DriveBackup -> {
                    startCloud(CloudAction.UPLOAD)
                }

                BackupEvent.DriveRestore -> {
                    startCloud(CloudAction.DOWNLOAD)
                }

                BackupEvent.ConsentShown -> {
                    local.update { it.copy(consent = null) }
                }

                is BackupEvent.ConsentResult -> {
                    onConsent(event.data)
                }
            }
        }

        private fun launchBusy(block: suspend () -> Unit) {
            viewModelScope.launch {
                local.update { it.copy(busy = true, problem = null, note = null) }
                block()
                local.update { it.copy(busy = false) }
            }
        }

        // The encrypted backup, or null with the reason shown.
        private suspend fun create(): ByteArray? {
            backupReady()?.let { problem ->
                local.update { it.copy(problem = problem) }
                return null
            }
            return when (val made = backups.create(local.value.passphrase.toCharArray())) {
                is BackupResult.Done -> made.value
                is BackupResult.Refused -> null.also { local.update { it.copy(problem = made.reason.toProblem()) } }
            }
        }

        private suspend fun restoreFrom(file: AppResult<ByteArray?>) {
            when (file) {
                is AppResult.Failure -> {
                    local.update { it.copy(problem = file.error.toProblem()) }
                }

                is AppResult.Success -> {
                    val bytes = file.value ?: return local.update { it.copy(problem = BackupProblem.NO_DRIVE_BACKUP) }
                    when (val restored = backups.restore(bytes, local.value.passphrase.toCharArray())) {
                        is BackupResult.Done -> local.update { it.copy(note = BackupNote.Restored(restored.value)) }
                        is BackupResult.Refused -> local.update { it.copy(problem = restored.reason.toProblem()) }
                    }
                }
            }
        }

        private fun startCloud(action: CloudAction) {
            launchBusy {
                when (val auth = cloud.authorize()) {
                    is AppResult.Failure -> {
                        local.update { it.copy(problem = BackupProblem.DRIVE_REFUSED) }
                    }

                    is AppResult.Success -> {
                        when (val value = auth.value) {
                            is CloudAuthorization.Granted -> {
                                runCloud(action, value.token)
                            }

                            is CloudAuthorization.NeedsConsent -> {
                                waiting = action
                                local.update { it.copy(consent = value.request) }
                            }
                        }
                    }
                }
            }
        }

        private fun onConsent(data: Intent?) {
            val action = waiting ?: return
            waiting = null
            launchBusy {
                when (val token = cloud.tokenFrom(data)) {
                    is AppResult.Success -> runCloud(action, token.value)
                    is AppResult.Failure -> local.update { it.copy(problem = BackupProblem.DRIVE_REFUSED) }
                }
            }
        }

        private suspend fun runCloud(
            action: CloudAction,
            token: String,
        ) {
            when (action) {
                CloudAction.UPLOAD -> create()?.let { bytes -> finish(cloud.upload(token, bytes)) { BackupNote.Saved } }
                CloudAction.DOWNLOAD -> restoreFrom(cloud.download(token))
            }
        }

        private fun finish(
            result: AppResult<Unit>,
            note: () -> BackupNote,
        ) {
            when (result) {
                is AppResult.Success -> local.update { it.copy(note = note()) }
                is AppResult.Failure -> local.update { it.copy(problem = result.error.toProblem()) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MIN_LENGTH = io.github.jeshiomurmu.opencaller.core.data.backup.BackupCipher.MIN_PASSPHRASE_LENGTH

            fun BackupError.toProblem(): BackupProblem =
                when (this) {
                    BackupError.NotEntitled -> BackupProblem.NOT_ENTITLED
                    BackupError.PassphraseTooShort -> BackupProblem.PASSPHRASE_TOO_SHORT
                    BackupError.WrongPassphrase -> BackupProblem.WRONG_PASSPHRASE
                    BackupError.NotABackup -> BackupProblem.NOT_A_BACKUP
                    is BackupError.Failed -> error.toProblem()
                }

            fun AppError.toProblem(): BackupProblem =
                when (this) {
                    AppError.Network -> BackupProblem.OFFLINE
                    AppError.Auth -> BackupProblem.DRIVE_REFUSED
                    else -> BackupProblem.FAILED
                }
        }
    }
