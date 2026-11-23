package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.AppCaseMode
import com.example.domain.GeneratedKeyRecord
import com.example.domain.ParsedUserKey
import com.example.domain.SourceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mycodeg7f_prefs")

data class MonitorPreferences(
    val activeCaseMode: AppCaseMode = AppCaseMode.MYCODE_G7F,
    val whatsAppEnabled: Boolean = false,
    val instagramEnabled: Boolean = false,
    val monitoringActive: Boolean = true,
    val vibrateOnCopy: Boolean = true,
    val soundOnCopy: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val setupGuideSeen: Boolean = false,
    val autoRedeemShop2Game: Boolean = true,
    val isUnlocked: Boolean = false,
    val isAdmin: Boolean = false,
    val isUnlimitedLicense: Boolean = false,
    val expiresAtMillis: Long? = null,
    val lockReasonExpired: Boolean = false,
    val usedKeyIds: Set<String> = emptySet(),
    val generatedKeys: List<GeneratedKeyRecord> = emptyList()
) {
    val hasAnyAppEnabled: Boolean
        get() = whatsAppEnabled || instagramEnabled

    fun isAppEnabled(app: SourceApp): Boolean = when (app) {
        SourceApp.WHATSAPP -> whatsAppEnabled
        SourceApp.INSTAGRAM -> instagramEnabled
        SourceApp.OCR -> true
    }

    fun hasValidAccess(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isUnlocked) return false
        if (isAdmin || isUnlimitedLicense) return true
        val expiry = expiresAtMillis ?: return false
        return nowMillis < expiry
    }

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        if (!isUnlocked || isAdmin || isUnlimitedLicense) return null
        val expiry = expiresAtMillis ?: return 0L
        return (expiry - nowMillis).coerceAtLeast(0L)
    }
}

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val ACTIVE_CASE_MODE = stringPreferencesKey("active_case_mode")
        val WHATSAPP_ENABLED = booleanPreferencesKey("whatsapp_enabled")
        val INSTAGRAM_ENABLED = booleanPreferencesKey("instagram_enabled")
        val MONITORING_ACTIVE = booleanPreferencesKey("monitoring_active")
        val VIBRATE_ON_COPY = booleanPreferencesKey("vibrate_on_copy")
        val SOUND_ON_COPY = booleanPreferencesKey("sound_on_copy")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val SETUP_GUIDE_SEEN = booleanPreferencesKey("setup_guide_seen")
        val AUTO_REDEEM_SHOP2GAME = booleanPreferencesKey("auto_redeem_shop2game")

        val IS_UNLOCKED = booleanPreferencesKey("access_is_unlocked")
        val IS_ADMIN = booleanPreferencesKey("access_is_admin")
        val IS_UNLIMITED = booleanPreferencesKey("access_is_unlimited")
        val EXPIRES_AT_MILLIS = longPreferencesKey("access_expires_at_millis")
        val LOCK_REASON_EXPIRED = booleanPreferencesKey("access_lock_reason_expired")
        val USED_KEY_IDS = stringSetPreferencesKey("access_used_key_ids")
        val GENERATED_KEYS_JSON = stringPreferencesKey("admin_generated_keys_json")
    }

    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedPrefs: MonitorPreferences = MonitorPreferences()

    val preferencesFlow: Flow<MonitorPreferences> = context.dataStore.data.map { prefs ->
        val rawExpiry = prefs[Keys.EXPIRES_AT_MILLIS]
        MonitorPreferences(
            activeCaseMode = AppCaseMode.fromId(prefs[Keys.ACTIVE_CASE_MODE]),
            whatsAppEnabled = prefs[Keys.WHATSAPP_ENABLED] ?: false,
            instagramEnabled = prefs[Keys.INSTAGRAM_ENABLED] ?: false,
            monitoringActive = prefs[Keys.MONITORING_ACTIVE] ?: true,
            vibrateOnCopy = prefs[Keys.VIBRATE_ON_COPY] ?: true,
            soundOnCopy = prefs[Keys.SOUND_ON_COPY] ?: false,
            autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: true,
            setupGuideSeen = prefs[Keys.SETUP_GUIDE_SEEN] ?: false,
            autoRedeemShop2Game = prefs[Keys.AUTO_REDEEM_SHOP2GAME] ?: true,
            isUnlocked = prefs[Keys.IS_UNLOCKED] ?: false,
            isAdmin = prefs[Keys.IS_ADMIN] ?: false,
            isUnlimitedLicense = prefs[Keys.IS_UNLIMITED] ?: false,
            expiresAtMillis = if (rawExpiry != null && rawExpiry > 0L) rawExpiry else null,
            lockReasonExpired = prefs[Keys.LOCK_REASON_EXPIRED] ?: false,
            usedKeyIds = prefs[Keys.USED_KEY_IDS] ?: emptySet(),
            generatedKeys = decodeGeneratedKeys(prefs[Keys.GENERATED_KEYS_JSON])
        )
    }.onEach { latest ->
        cachedPrefs = latest
    }

    init {
        warmupScope.launch {
            cachedPrefs = preferencesFlow.first()
        }
    }

    /** Zero-latency in-memory preferences read for hot-path accessibility & notification listeners. */
    fun currentFast(): MonitorPreferences = cachedPrefs

    suspend fun current(): MonitorPreferences = preferencesFlow.first().also { cachedPrefs = it }

    suspend fun redeemAdminKey() {
        cachedPrefs = cachedPrefs.copy(
            isUnlocked = true,
            isAdmin = true,
            isUnlimitedLicense = true,
            expiresAtMillis = null,
            lockReasonExpired = false
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_UNLOCKED] = true
            prefs[Keys.IS_ADMIN] = true
            prefs[Keys.IS_UNLIMITED] = true
            prefs.remove(Keys.EXPIRES_AT_MILLIS)
            prefs[Keys.LOCK_REASON_EXPIRED] = false
        }
    }

    suspend fun redeemUserKey(
        parsed: ParsedUserKey,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val nextUsed = cachedPrefs.usedKeyIds + parsed.keyId
        val nextExpiry = if (parsed.isUnlimited) {
            null
        } else {
            nowMillis + parsed.durationSeconds * 1000L
        }
        cachedPrefs = cachedPrefs.copy(
            isUnlocked = true,
            isAdmin = false,
            isUnlimitedLicense = parsed.isUnlimited,
            expiresAtMillis = nextExpiry,
            lockReasonExpired = false,
            usedKeyIds = nextUsed
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_UNLOCKED] = true
            prefs[Keys.IS_ADMIN] = false
            prefs[Keys.IS_UNLIMITED] = parsed.isUnlimited
            if (nextExpiry != null) {
                prefs[Keys.EXPIRES_AT_MILLIS] = nextExpiry
            } else {
                prefs.remove(Keys.EXPIRES_AT_MILLIS)
            }
            prefs[Keys.LOCK_REASON_EXPIRED] = false
            prefs[Keys.USED_KEY_IDS] = (prefs[Keys.USED_KEY_IDS] ?: emptySet()) + parsed.keyId
        }
    }

    suspend fun lockSession(expired: Boolean = false) {
        cachedPrefs = cachedPrefs.copy(
            isUnlocked = false,
            isAdmin = false,
            isUnlimitedLicense = false,
            expiresAtMillis = null,
            lockReasonExpired = expired
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_UNLOCKED] = false
            prefs[Keys.IS_ADMIN] = false
            prefs[Keys.IS_UNLIMITED] = false
            prefs.remove(Keys.EXPIRES_AT_MILLIS)
            prefs[Keys.LOCK_REASON_EXPIRED] = expired
        }
    }

    suspend fun saveGeneratedKeyRecord(record: GeneratedKeyRecord) {
        val updated = listOf(record) + cachedPrefs.generatedKeys.filterNot { it.keyId == record.keyId }
        val json = encodeGeneratedKeys(updated)
        cachedPrefs = cachedPrefs.copy(generatedKeys = updated)
        context.dataStore.edit { prefs ->
            prefs[Keys.GENERATED_KEYS_JSON] = json
        }
    }

    suspend fun deleteGeneratedKeyRecord(keyId: String) {
        val updated = cachedPrefs.generatedKeys.filterNot { it.keyId == keyId }
        val json = encodeGeneratedKeys(updated)
        cachedPrefs = cachedPrefs.copy(generatedKeys = updated)
        context.dataStore.edit { prefs ->
            prefs[Keys.GENERATED_KEYS_JSON] = json
        }
    }

    suspend fun setActiveCaseMode(mode: AppCaseMode) {
        if (cachedPrefs.activeCaseMode == mode) return
        cachedPrefs = cachedPrefs.copy(activeCaseMode = mode)
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_CASE_MODE] = mode.id
        }
    }

    suspend fun setWhatsAppEnabled(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(
            whatsAppEnabled = enabled,
            monitoringActive = if (enabled) true else cachedPrefs.monitoringActive
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.WHATSAPP_ENABLED] = enabled
            if (enabled) prefs[Keys.MONITORING_ACTIVE] = true
        }
    }

    suspend fun setInstagramEnabled(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(
            instagramEnabled = enabled,
            monitoringActive = if (enabled) true else cachedPrefs.monitoringActive
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.INSTAGRAM_ENABLED] = enabled
            if (enabled) prefs[Keys.MONITORING_ACTIVE] = true
        }
    }

    suspend fun setMonitoringActive(active: Boolean) {
        cachedPrefs = cachedPrefs.copy(monitoringActive = active)
        context.dataStore.edit { prefs ->
            prefs[Keys.MONITORING_ACTIVE] = active
        }
    }

    suspend fun setVibrateOnCopy(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(vibrateOnCopy = enabled)
        context.dataStore.edit { prefs ->
            prefs[Keys.VIBRATE_ON_COPY] = enabled
        }
    }

    suspend fun setSoundOnCopy(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(soundOnCopy = enabled)
        context.dataStore.edit { prefs ->
            prefs[Keys.SOUND_ON_COPY] = enabled
        }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(autoStartOnBoot = enabled)
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_START_ON_BOOT] = enabled
        }
    }

    suspend fun setAutoRedeemShop2Game(enabled: Boolean) {
        cachedPrefs = cachedPrefs.copy(autoRedeemShop2Game = enabled)
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_REDEEM_SHOP2GAME] = enabled
        }
    }

    suspend fun markSetupGuideSeen() {
        cachedPrefs = cachedPrefs.copy(setupGuideSeen = true)
        context.dataStore.edit { prefs ->
            prefs[Keys.SETUP_GUIDE_SEEN] = true
        }
    }

    private fun encodeGeneratedKeys(list: List<GeneratedKeyRecord>): String {
        val arr = JSONArray()
        for (item in list.take(50)) {
            val obj = JSONObject()
            obj.put("key", item.key)
            obj.put("keyId", item.keyId)
            obj.put("label", item.label)
            obj.put("durationSeconds", item.durationSeconds)
            obj.put("createdAtMillis", item.createdAtMillis)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeGeneratedKeys(raw: String?): List<GeneratedKeyRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<GeneratedKeyRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key")
                val keyId = obj.optString("keyId")
                if (key.isBlank() || keyId.isBlank()) continue
                list.add(
                    GeneratedKeyRecord(
                        key = key,
                        keyId = keyId,
                        label = obj.optString("label"),
                        durationSeconds = obj.optLong("durationSeconds", 0L),
                        createdAtMillis = obj.optLong("createdAtMillis", 0L)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
