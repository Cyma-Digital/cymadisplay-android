package com.cyma.videoloop.data.identity

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cyma.videoloop.util.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Both IDs are deterministically derived from the hardware serial (or ANDROID_ID fallback)
 * and never change for the lifetime of the device.
 *
 * Derivation:
 *   hash = sha256(serial).uppercase()   →  64 hex chars
 *   deviceId    = hash[0..9]            →  e.g. "120AA60DAA"
 *   pairingCode = hash[10..15]          →  e.g. "4B2A5C"
 */
@Singleton
class DeviceIdentityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_PAIRING_CODE = stringPreferencesKey("pairing_code")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_PAIRED = booleanPreferencesKey("paired")
    }

    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var cachedPairingCode: String? = null

    suspend fun getOrCreateDeviceId(): String {
        cachedDeviceId?.let { return it }
        ensureIds()
        return cachedDeviceId!!
    }

    suspend fun getOrCreatePairingCode(): String {
        cachedPairingCode?.let { return it }
        ensureIds()
        return cachedPairingCode!!
    }

    suspend fun getAuthToken(): String? = dataStore.data.first()[KEY_AUTH_TOKEN]

    suspend fun saveAuthToken(token: String) {
        dataStore.edit { it[KEY_AUTH_TOKEN] = token }
    }

    /**
     * Last-known pairing status, used as a fallback when the network is unreachable
     * during boot. Authoritative source is the schedule endpoint — see
     * [com.cyma.videoloop.data.schedule.ScheduleRepository.isPaired].
     */
    suspend fun isLocallyPaired(): Boolean = dataStore.data.first()[KEY_PAIRED] == true

    /** Stream of the local paired flag — re-emits whenever [setPaired] flips it. */
    fun pairedFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEY_PAIRED] == true }.distinctUntilChanged()

    suspend fun setPaired(paired: Boolean) {
        dataStore.edit {
            if (paired) it[KEY_PAIRED] = true else it.remove(KEY_PAIRED)
        }
    }

    suspend fun clearIdentity() {
        cachedDeviceId = null
        cachedPairingCode = null
        dataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_ID)
            prefs.remove(KEY_PAIRING_CODE)
            prefs.remove(KEY_AUTH_TOKEN)
            prefs.remove(KEY_PAIRED)
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private suspend fun ensureIds() {
        val prefs = dataStore.data.first()
        val storedId = prefs[KEY_DEVICE_ID]
        val storedCode = prefs[KEY_PAIRING_CODE]

        if (storedId != null && storedCode != null) {
            cachedDeviceId = storedId
            cachedPairingCode = storedCode
            return
        }

        val (deviceId, pairingCode) = deriveIds(resolveHardwareSerial())
        dataStore.edit { it[KEY_DEVICE_ID] = deviceId; it[KEY_PAIRING_CODE] = pairingCode }
        cachedDeviceId = deviceId
        cachedPairingCode = pairingCode
    }

    private fun resolveHardwareSerial(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "fallback-${System.currentTimeMillis()}"
}

/**
 * Derives [deviceId] (10 chars) and [pairingCode] (6 chars) from the same SHA-256 hash,
 * both uppercase hex — matching the format expected by the Cyma backend.
 */
private fun deriveIds(serial: String): Pair<String, String> {
    val hash = sha256(serial).uppercase()   // 64 uppercase hex chars
    return hash.take(10) to hash.substring(10, 16)
}
