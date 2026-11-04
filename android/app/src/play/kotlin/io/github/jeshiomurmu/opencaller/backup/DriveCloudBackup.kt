package io.github.jeshiomurmu.opencaller.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudAuthorization
import io.github.jeshiomurmu.opencaller.core.data.backup.CloudBackup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Backups in Google Drive's hidden app folder (`appDataFolder`), only reachable by OpenCaller. Access comes from
 * Google Identity Services' `AuthorizationClient` with the `drive.appdata` scope only (Google's current way to
 * authorize Google APIs; sign-in is separate and not needed). Drive v3 REST without the Drive SDK, as in Google's
 * discovery document (revision 20261028) and its `UploadAppData`/`ListAppData` samples: `files.create` multipart
 * upload with `parents: ["appDataFolder"]`, `files.list` with `spaces=appDataFolder`, `files.get?alt=media`,
 * `files.delete`. The file is encrypted before upload; Google only stores it.
 */
@Singleton
class DriveCloudBackup
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : CloudBackup {
        private val http = OkHttpClient()
        private val json = Json { ignoreUnknownKeys = true }
        private val client get() = Identity.getAuthorizationClient(context)

        override val available: Boolean = true

        override suspend fun authorize(): AppResult<CloudAuthorization> {
            val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()
            return when (val result = client.authorize(request).await()) {
                is AppResult.Failure -> {
                    result
                }

                is AppResult.Success -> {
                    val auth = result.value
                    val pending = auth.pendingIntent
                    val token = auth.accessToken
                    when {
                        auth.hasResolution() && pending != null -> AppResult.Success(CloudAuthorization.NeedsConsent(pending.intentSender))
                        token != null -> AppResult.Success(CloudAuthorization.Granted(token))
                        else -> AppResult.Failure(AppError.Auth)
                    }
                }
            }
        }

        override fun tokenFrom(data: Intent?): AppResult<String> =
            try {
                data
                    ?.let { client.getAuthorizationResultFromIntent(it).accessToken }
                    ?.let { AppResult.Success(it) }
                    ?: AppResult.Failure(AppError.Auth)
            } catch (e: ApiException) {
                Timber.w(e, "Drive access not granted")
                AppResult.Failure(AppError.Auth)
            }

        override suspend fun upload(
            token: String,
            file: ByteArray,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                val older = (list(token) as? AppResult.Success)?.value.orEmpty()
                val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
                val body =
                    MultipartBody
                        .Builder()
                        .setType("multipart/related".toMediaType())
                        .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                        .addPart(file.toRequestBody(OCTET_STREAM))
                        .build()
                val created = call(token, Request.Builder().url("$UPLOAD_FILES?uploadType=multipart&fields=id").post(body)) { Unit }
                // The new file is safe in Drive before the old ones go.
                if (created is AppResult.Success) older.forEach { delete(token, it.id) }
                created
            }

        override suspend fun download(token: String): AppResult<ByteArray?> =
            withContext(dispatchers.io) {
                when (val files = list(token)) {
                    is AppResult.Failure -> {
                        files
                    }

                    is AppResult.Success -> {
                        val newest = files.value.maxByOrNull { it.modifiedTime.orEmpty() }
                        if (newest == null) {
                            AppResult.Success(null)
                        } else {
                            call(token, Request.Builder().url("$FILES/${newest.id}?alt=media").get()) { it.body.bytes() }
                        }
                    }
                }
            }

        // Our backups in the app folder (only OpenCaller's files are there; the name is checked anyway).
        private fun list(token: String): AppResult<List<DriveFile>> =
            call(
                token,
                Request.Builder().url("$FILES?spaces=appDataFolder&fields=files(id,name,modifiedTime)&pageSize=$PAGE_SIZE").get(),
            ) { response ->
                try {
                    json.decodeFromString(FileList.serializer(), response.body.string()).files.filter { it.name == FILE_NAME }
                } catch (e: SerializationException) {
                    throw IOException("unreadable Drive file list", e)
                }
            }

        private fun delete(
            token: String,
            id: String,
        ) {
            val deleted = call(token, Request.Builder().url("$FILES/$id").delete()) { Unit }
            if (deleted is AppResult.Failure) Timber.w("An older Drive backup was not deleted")
        }

        private fun <T> call(
            token: String,
            request: Request.Builder,
            read: (Response) -> T,
        ): AppResult<T> =
            try {
                http.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            AppResult.Success(read(response))
                        }

                        response.code == UNAUTHORIZED -> {
                            client.clearToken(ClearTokenRequest.builder().setToken(token).build())
                            AppResult.Failure(AppError.Auth)
                        }

                        else -> {
                            Timber.w("Drive request failed: HTTP %d", response.code)
                            AppResult.Failure(AppError.Unexpected(IOException("Drive HTTP ${response.code}")))
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.w(e, "Drive unreachable")
                AppResult.Failure(AppError.Network)
            }

        private suspend fun <T> Task<T>.await(): AppResult<T> =
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { if (cont.isActive) cont.resume(AppResult.Success(it)) }
                addOnFailureListener { e ->
                    Timber.w(e, "Drive authorization failed")
                    if (cont.isActive) cont.resume(AppResult.Failure(AppError.Auth))
                }
            }

        @Serializable
        private data class FileList(
            val files: List<DriveFile> = emptyList(),
        )

        @Serializable
        private data class DriveFile(
            val id: String,
            val name: String = "",
            val modifiedTime: String? = null,
        )

        private companion object {
            const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
            const val FILES = "https://www.googleapis.com/drive/v3/files"
            const val UPLOAD_FILES = "https://www.googleapis.com/upload/drive/v3/files"
            const val FILE_NAME = "opencaller-backup.ocbk"
            const val PAGE_SIZE = 100
            const val UNAUTHORIZED = 401
            val OCTET_STREAM = "application/octet-stream".toMediaType()
        }
    }
