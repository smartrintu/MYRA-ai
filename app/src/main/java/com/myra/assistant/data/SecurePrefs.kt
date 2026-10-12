package com.myra.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.myra.assistant.ai.GeminiModels
import org.json.JSONArray
import org.json.JSONObject

data class PrimeContact(val name: String, val number: String)

/** Encrypted storage for all settings (API key included). Call init() once in Application. */
object SecurePrefs {
    private const val FILE = "myra_secure_prefs"
    private lateinit var prefs: SharedPreferences

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        prefs = try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE, alias, app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            app.getSharedPreferences(FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    var userName: String
        get() = prefs.getString("user_name", "Smart Rintu") ?: "Smart Rintu"
        set(v) = prefs.edit().putString("user_name", v).apply()

    /** GF, PRO or ASSISTANT */
    var personality: String
        get() = prefs.getString("personality_mode", "GF") ?: "GF"
        set(v) = prefs.edit().putString("personality_mode", v).apply()

    /** HINGLISH, BANGLA or ENGLISH */
    var language: String
        get() = prefs.getString("language", "HINGLISH") ?: "HINGLISH"
        set(v) = prefs.edit().putString("language", v).apply()

    /** Master ON/OFF switch for MYRA (voice service, floating orb, call announcements). */
    var enabled: Boolean
        get() = prefs.getBoolean("myra_enabled", true)
        set(v) = prefs.edit().putBoolean("myra_enabled", v).apply()

    /** Shayari mode: ON = MYRA recites shayari now and then (and the button is shown). */
    var shayariEnabled: Boolean
        get() = prefs.getBoolean("shayari_enabled", true)
        set(v) = prefs.edit().putBoolean("shayari_enabled", v).apply()

    /** pink, red, blue... , "#RRGGBB" or "RAINBOW" (animated multicolor). */
    var theme: String
        get() = prefs.getString("ui_theme", "pink") ?: "pink"
        set(v) = prefs.edit().putString("ui_theme", v).apply()

    var licenseKey: String
        get() = prefs.getString("license_key", "") ?: ""
        set(v) = prefs.edit().putString("license_key", v).apply()

    var lastSeenSec: Long
        get() = prefs.getLong("last_seen_sec", 0L)
        set(v) = prefs.edit().putLong("last_seen_sec", v).apply()

    var model: String
        get() = prefs.getString("gemini_model", GeminiModels.DEFAULT_MODEL) ?: GeminiModels.DEFAULT_MODEL
        set(v) = prefs.edit().putString("gemini_model", v).apply()

    var voice: String
        get() = prefs.getString("gemini_voice", GeminiModels.DEFAULT_VOICE) ?: GeminiModels.DEFAULT_VOICE
        set(v) = prefs.edit().putString("gemini_voice", v).apply()

    var muteWhileSpeaking: Boolean
        get() = prefs.getBoolean("mute_while_speaking", false)
        set(v) = prefs.edit().putBoolean("mute_while_speaking", v).apply()

    fun getPrimeContacts(): List<PrimeContact> {
        val json = prefs.getString("prime_contacts_json", null)
        val out = mutableListOf<PrimeContact>()
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(PrimeContact(o.optString("name"), o.optString("number")))
                }
            } catch (_: Exception) {
            }
        } else {
            // legacy single prime contact
            val n = prefs.getString("prime_name", null)
            val num = prefs.getString("prime_number", null)
            if (!n.isNullOrBlank() && !num.isNullOrBlank()) out.add(PrimeContact(n, num))
        }
        return out
    }

    fun setPrimeContacts(list: List<PrimeContact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        prefs.edit().putString("prime_contacts_json", arr.toString()).apply()
    }
}
