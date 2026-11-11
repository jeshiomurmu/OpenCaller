package io.github.jeshiomurmu.opencaller.core.data.voip

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Small Phase 11 settings on the phone: this device's push endpoint (sent again after sign-in), the server's
 * features as last seen, and whether video caller IDs may download on mobile data (off by default).
 */
class VoipPreferences(
    private val store: DataStore<Preferences>,
) {
    /** True when clips may download on mobile data too. */
    val clipsOnMobileData: Flow<Boolean> = store.data.map { it[MOBILE_DATA] ?: false }

    /** The server's features as last fetched. */
    val features: Flow<ServerFeatures> =
        store.data.map {
            ServerFeatures(
                appCalls = it[APP_CALLS] ?: false,
                flash = it[FLASH] ?: false,
                fcm = it[FCM] ?: false,
                webPushVapidKey = it[VAPID],
                assistant = it[ASSISTANT] ?: false,
            )
        }

    /** Allows or forbids clip downloads on mobile data. */
    suspend fun setClipsOnMobileData(allowed: Boolean) {
        store.edit { it[MOBILE_DATA] = allowed }
    }

    /** Remembers [features]. */
    suspend fun setFeatures(features: ServerFeatures) {
        store.edit {
            it[APP_CALLS] = features.appCalls
            it[FLASH] = features.flash
            it[FCM] = features.fcm
            it[ASSISTANT] = features.assistant
            val vapid = features.webPushVapidKey
            if (vapid == null) it.remove(VAPID) else it[VAPID] = vapid
        }
    }

    /** This device's push endpoint, if a push provider gave one. */
    suspend fun endpoint(): PushEndpoint? {
        val prefs = store.data.first()
        val value = prefs[ENDPOINT] ?: return null
        return when (prefs[ENDPOINT_TYPE]) {
            TYPE_FCM -> PushEndpoint.Fcm(value)
            TYPE_WEB_PUSH -> webPushOf(value, prefs[P256DH], prefs[AUTH])
            else -> null
        }
    }

    /** Remembers [endpoint] (`null` forgets it). */
    suspend fun setEndpoint(endpoint: PushEndpoint?) {
        store.edit {
            listOf(ENDPOINT_TYPE, ENDPOINT, P256DH, AUTH).forEach(it::remove)
            when (endpoint) {
                is PushEndpoint.Fcm -> {
                    it[ENDPOINT_TYPE] = TYPE_FCM
                    it[ENDPOINT] = endpoint.token
                }

                is PushEndpoint.WebPush -> {
                    it[ENDPOINT_TYPE] = TYPE_WEB_PUSH
                    it[ENDPOINT] = endpoint.url
                    it[P256DH] = endpoint.p256dh
                    it[AUTH] = endpoint.auth
                }

                // Forgotten: its keys were removed above.
                null -> {}
            }
        }
    }

    private fun webPushOf(
        url: String,
        p256dh: String?,
        auth: String?,
    ): PushEndpoint? = if (p256dh != null && auth != null) PushEndpoint.WebPush(url, p256dh, auth) else null

    /** File name and keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "voip"
        private const val TYPE_FCM = "fcm"
        private const val TYPE_WEB_PUSH = "webpush"
        private val MOBILE_DATA = booleanPreferencesKey("clips_on_mobile_data")
        private val APP_CALLS = booleanPreferencesKey("feature_app_calls")
        private val FLASH = booleanPreferencesKey("feature_flash")
        private val FCM = booleanPreferencesKey("feature_fcm")
        private val ASSISTANT = booleanPreferencesKey("feature_assistant")
        private val VAPID = stringPreferencesKey("feature_vapid_key")
        private val ENDPOINT_TYPE = stringPreferencesKey("push_type")
        private val ENDPOINT = stringPreferencesKey("push_endpoint")
        private val P256DH = stringPreferencesKey("push_p256dh")
        private val AUTH = stringPreferencesKey("push_auth")
    }
}
