package com.myra.assistant.license

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.myra.assistant.data.SecurePrefs
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

enum class Plan { FREE, TRIAL, PRO }

data class LicenseStatus(
    val valid: Boolean,
    val plan: Plan? = null,
    val expiresAtSec: Long = 0L, // 0 = lifetime
    val name: String = "",
    val message: String = ""
)

/**
 * Offline license check. Key = base64url(payloadJSON) + "." + base64url(ECDSA-P256 signature, raw r||s).
 * The owner signs keys on the admin page; the app only holds the public key, so keys cannot be forged.
 */
object LicenseManager {

    /** Short ID the user sends to the owner; keys can be locked to it. */
    fun deviceId(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val h = MessageDigest.getInstance("SHA-256").digest("myra:$id".toByteArray())
        val hex = h.joinToString("") { "%02X".format(it) }.substring(0, 12)
        return hex.chunked(4).joinToString("-")
    }

    fun status(ctx: Context): LicenseStatus {
        val key = SecurePrefs.licenseKey
        if (key.isBlank()) return bad("Activation key nahi daali gayi")
        return evaluate(ctx, key)
    }

    fun activate(ctx: Context, rawKey: String): LicenseStatus {
        val key = rawKey.trim().replace(Regex("\\s+"), "")
        if (key.isEmpty()) return bad("Key daalo")
        val st = evaluate(ctx, key)
        if (st.valid) SecurePrefs.licenseKey = key
        return st
    }

    /** TRIAL and PRO may control the phone; FREE is voice chat only. */
    fun allowsControl(ctx: Context): Boolean {
        val s = status(ctx)
        return s.valid && (s.plan == Plan.PRO || s.plan == Plan.TRIAL)
    }

    private fun evaluate(ctx: Context, key: String): LicenseStatus {
        val parts = key.split(".")
        if (parts.size != 2) return bad("Key ka format galat hai")
        val decoded = try {
            b64d(parts[0]) to b64d(parts[1])
        } catch (e: Exception) {
            return bad("Key ka format galat hai")
        }
        val payload = decoded.first
        val sig = decoded.second
        if (sig.size != 64) return bad("Key ka format galat hai")
        if (!verify(payload, sig)) return bad("Key asli nahi hai")

        val o = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return bad("Key kharab hai")
        }
        val plan = try {
            Plan.valueOf(o.optString("p"))
        } catch (e: Exception) {
            return bad("Key mein plan galat hai")
        }
        val exp = o.optLong("e", 0L)
        val dev = o.optString("d", "*")
        val name = o.optString("n", "")

        if (dev != "*" && !dev.equals(deviceId(ctx), ignoreCase = true)) {
            return bad("Ye key kisi doosre phone ke liye hai")
        }

        val now = System.currentTimeMillis() / 1000
        val last = SecurePrefs.lastSeenSec
        if (last > 0 && now + 86_400 < last) return bad("Phone ka date/time galat hai — sahi karo")
        if (now > last) SecurePrefs.lastSeenSec = now

        if (exp != 0L && now > exp) {
            return LicenseStatus(false, plan, exp, name, "Key expire ho gayi — nayi key lo")
        }
        return LicenseStatus(true, plan, exp, name, "OK")
    }

    private fun verify(payload: ByteArray, rawSig: ByteArray): Boolean = try {
        val pubBytes = Base64.decode(LicenseConfig.PUBLIC_KEY_B64, Base64.DEFAULT)
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(payload)
        s.verify(rawToDer(rawSig))
    } catch (e: Exception) {
        false
    }

    /** WebCrypto gives raw r||s (64 bytes); Java wants a DER SEQUENCE of two INTEGERs. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun enc(x: ByteArray): ByteArray {
            var i = 0
            while (i < x.size - 1 && x[i] == 0.toByte()) i++
            var v = x.copyOfRange(i, x.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = enc(raw.copyOfRange(0, 32)) + enc(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun b64d(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun bad(msg: String) = LicenseStatus(false, message = msg)
}
