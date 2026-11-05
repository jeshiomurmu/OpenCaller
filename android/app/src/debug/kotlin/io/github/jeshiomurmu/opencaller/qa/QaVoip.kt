package io.github.jeshiomurmu.opencaller.qa

import android.content.Context
import android.content.Intent
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallIntents
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.data.voip.VideoCallerIdRepository
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import kotlinx.coroutines.flow.first
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

/**
 * Debug builds only: the steps of the emulator app-call test (`scripts/e2e/voip.sh`) for [QaCommandReceiver]. They
 * call the same repositories as the screens; app calls start the real call screen.
 *
 * - `voip-features`: "ok appCalls=true flash=true webPush=true", what the server offers (`GET /v1/server`).
 * - `push-endpoint --es url U`: registers a Web Push endpoint at U with a fresh P-256 key (emulators have no
 *   UnifiedPush distributor; the test's sink at U records what the server pushes).
 * - `app-call --es number E164`: opens the app-call screen and calls (the app must be in the foreground).
 * - `flash --es number E164 --es kind on_my_way`: sends a Flash message.
 * - `flash-count`: "ok received=N sent=M", Flash messages kept on this phone.
 * - `clip-upload --es file NAME`: uploads `files/NAME` as this user's video caller ID.
 * - `clip-status`: "ok video=<id|none> job=<state>".
 */
class QaVoip
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val registrar: PushRegistrar,
        private val flash: FlashRepository,
        private val videos: VideoCallerIdRepository,
    ) {
        /** Runs [cmd], or returns `null` when it isn't one of these commands. */
        suspend fun run(
            cmd: String?,
            intent: Intent,
        ): String? =
            when (cmd) {
                "voip-features" -> features()
                "push-endpoint" -> intent.getStringExtra("url")?.let { register(it) } ?: "error url"
                "app-call" -> number(intent) { call(it) }
                "flash" -> number(intent) { sendFlash(it, intent.getStringExtra("kind")) }
                "flash-count" -> flashCount()
                "clip-upload" -> intent.getStringExtra("file")?.let { upload(it) } ?: "error file"
                "clip-status" -> describe(videos.mine()) { "ok video=${it.videoId ?: "none"} job=${it.job} error=${it.error}" }
                else -> null
            }

        private suspend fun features(): String {
            val features = registrar.refreshFeatures() ?: return "error server"
            return "ok appCalls=${features.appCalls} flash=${features.flash} webPush=${features.webPushVapidKey != null}"
        }

        private suspend fun register(url: String): String {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val point = (generator.generateKeyPair().public as ECPublicKey).w
            val publicKey = byteArrayOf(UNCOMPRESSED) + point.affineX.fixed() + point.affineY.fixed()
            val auth = ByteArray(AUTH_BYTES).also(SecureRandom()::nextBytes)
            return describe(registrar.onNewEndpoint(PushEndpoint.WebPush(url, publicKey.base64Url(), auth.base64Url()))) { "ok" }
        }

        private fun call(number: PhoneNumber): String {
            context.startActivity(
                Intent(AppCallIntents.ACTION_CALL)
                    .setPackage(context.packageName)
                    .putExtra(AppCallIntents.EXTRA_NUMBER, number.e164)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "ok"
        }

        private suspend fun sendFlash(
            number: PhoneNumber,
            kind: String?,
        ): String {
            val flashKind = FlashKind.parse(kind) ?: return "error kind"
            return describe(flash.send(number, flashKind)) { "ok id=${it.id}" }
        }

        private suspend fun flashCount(): String =
            describe(flash.recent().first()) { messages ->
                "ok received=${messages.count { !it.outgoing }} sent=${messages.count { it.outgoing }}"
            }

        private suspend fun upload(name: String): String {
            val file = File(context.filesDir, name).takeIf { it.parentFile == context.filesDir } ?: return "error file"
            return describe(videos.upload(file)) { "ok job=${it.job}" }
        }

        private inline fun number(
            intent: Intent,
            block: (PhoneNumber) -> String,
        ): String = intent.getStringExtra("number")?.takeIf(PhoneNumber::isE164)?.let { block(PhoneNumber(it)) } ?: "error number"

        private inline fun <T> describe(
            result: AppResult<T>,
            ok: (T) -> String,
        ): String =
            when (result) {
                is AppResult.Success -> ok(result.value)
                is AppResult.Failure -> "error ${result.error}"
            }

        private companion object {
            const val UNCOMPRESSED: Byte = 0x04
            const val COORDINATE_BYTES = 32
            const val AUTH_BYTES = 16

            // A P-256 coordinate as exactly 32 bytes (BigInteger adds a sign byte or drops leading zeros).
            fun BigInteger.fixed(): ByteArray {
                val bytes = toByteArray().takeLast(COORDINATE_BYTES).toByteArray()
                return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
            }

            fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
