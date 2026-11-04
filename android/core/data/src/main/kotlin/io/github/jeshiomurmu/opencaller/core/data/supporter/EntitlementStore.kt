package io.github.jeshiomurmu.opencaller.core.data.supporter

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The server's last answer about entitlements, as stored on the phone, with when it was received. */
data class StoredEntitlement(
    val entitlement: Entitlement,
    val offer: SupporterOffer,
    val checkedAtMillis: Long?,
)

/**
 * Keeps [StoredEntitlement] in a small Preferences DataStore of its own, in the no-backup directory: a backup must
 * never carry an entitlement to another phone, the server is asked again.
 */
class EntitlementStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The stored answer. */
    val stored: Flow<StoredEntitlement> =
        dataStore.data.map { prefs ->
            StoredEntitlement(
                entitlement =
                    Entitlement(
                        entitled = prefs[ENTITLED] ?: false,
                        tier = prefs[TIER]?.let { name -> PaidTier.entries.firstOrNull { it.name == name } },
                        source =
                            prefs[SOURCE]?.let { name -> EntitlementSource.entries.firstOrNull { it.name == name } }
                                ?: EntitlementSource.NONE,
                        expiresAtMillis = prefs[EXPIRES_AT],
                        autoRenewing = prefs[AUTO_RENEWING] ?: false,
                    ),
                offer =
                    SupporterOffer(
                        serverConfigured = prefs[SERVER_CONFIGURED] ?: false,
                        selfHosted = prefs[SELF_HOSTED] ?: false,
                        productId = prefs[PRODUCT_ID],
                        billingAccountId = prefs[ACCOUNT_ID],
                    ),
                checkedAtMillis = prefs[CHECKED_AT],
            )
        }

    /** Replaces the stored answer. */
    suspend fun save(value: StoredEntitlement) {
        dataStore.edit { prefs ->
            val e = value.entitlement
            prefs[ENTITLED] = e.entitled
            e.tier?.let { prefs[TIER] = it.name } ?: prefs.remove(TIER)
            prefs[SOURCE] = e.source.name
            e.expiresAtMillis?.let { prefs[EXPIRES_AT] = it } ?: prefs.remove(EXPIRES_AT)
            prefs[AUTO_RENEWING] = e.autoRenewing
            prefs[SERVER_CONFIGURED] = value.offer.serverConfigured
            prefs[SELF_HOSTED] = value.offer.selfHosted
            value.offer.productId?.let { prefs[PRODUCT_ID] = it } ?: prefs.remove(PRODUCT_ID)
            value.offer.billingAccountId?.let { prefs[ACCOUNT_ID] = it } ?: prefs.remove(ACCOUNT_ID)
            value.checkedAtMillis?.let { prefs[CHECKED_AT] = it } ?: prefs.remove(CHECKED_AT)
        }
    }

    /** Storage keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "entitlements"
        private val ENTITLED = booleanPreferencesKey("entitled")
        private val TIER = stringPreferencesKey("tier")
        private val SOURCE = stringPreferencesKey("source")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
        private val AUTO_RENEWING = booleanPreferencesKey("auto_renewing")
        private val SERVER_CONFIGURED = booleanPreferencesKey("server_configured")
        private val SELF_HOSTED = booleanPreferencesKey("self_hosted")
        private val PRODUCT_ID = stringPreferencesKey("product_id")
        private val ACCOUNT_ID = stringPreferencesKey("billing_account_id")
        private val CHECKED_AT = longPreferencesKey("checked_at")
    }
}
