package com.myra.assistant.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommandException(message: String) : Exception(message)

/** Executes phone actions. Every function returns "success: ..." or "error: ..." for Gemini. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>().applicationContext

    fun execute(type: String, params: Map<String, String>, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                run(type, params)
            } catch (e: CommandException) {
                "error: ${e.message}"
            } catch (e: Exception) {
                "error: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun run(type: String, p: Map<String, String>): String = when (type) {
        "open_app" -> openApp(p["app_name"] ?: throw CommandException("app name missing"))
        "close_app" -> {
            requireAccessibility()
            AccessibilityHelperService.instance?.closeCurrentApp()
            "success: went to home screen (apps cannot be force-closed)"
        }
        "make_call" -> makeCall(p["name_or_number"] ?: throw CommandException("contact missing"))
        "send_sms" -> sendSms(
            p["name_or_number"] ?: throw CommandException("contact missing"), p["message"] ?: ""
        )
        "send_whatsapp" -> sendWhatsApp(
            p["name_or_number"] ?: throw CommandException("contact missing"), p["message"] ?: ""
        )
        "call_prime_contact" -> makeCall(primeContact(p["index"]).number)
        "message_prime_contact" -> sendSms(primeContact(p["index"]).number, p["message"] ?: "")
        "change_volume" -> changeVolume(p["direction"] ?: "up")
        "set_flashlight" -> setFlashlight(p["on"]?.toBooleanStrictOrNull() ?: (p["on"] != "false"))
        "open_wifi_settings" -> {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else Settings.ACTION_WIFI_SETTINGS
            start(Intent(action))
            "success: WiFi panel opened — user must flip the switch (apps cannot toggle WiFi)"
        }
        "open_bluetooth_settings" -> {
            start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "success: Bluetooth settings opened — user must flip the switch"
        }
        "answer_call" -> answerCall()
        "reject_call" -> rejectCall()
        "go_back" -> {
            requireAccessibility()
            if (AccessibilityHelperService.instance?.goBack() == true) "success: pressed back"
            else "error: could not press back"
        }
        "click_on_screen_text" -> {
            requireAccessibility()
            val t = p["text"] ?: throw CommandException("text missing")
            if (AccessibilityHelperService.instance?.clickOnText(t) == true) "success: tapped '$t'"
            else "error: could not find a tappable '$t' on screen"
        }
        "type_text" -> {
            requireAccessibility()
            val t = p["text"] ?: throw CommandException("text missing")
            if (AccessibilityHelperService.instance?.typeText(t) == true) "success: typed text"
            else "error: no input field found on screen"
        }
        "scroll_screen" -> {
            requireAccessibility()
            val svc = AccessibilityHelperService.instance
            val ok = if (p["direction"] == "up") svc?.scrollUp() else svc?.scrollDown()
            if (ok == true) "success: scrolled" else "error: nothing scrollable found"
        }
        else -> "error: unknown command $type"
    }

    // ------------------------------------------------------------------ helpers

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun requireAccessibility() {
        if (AccessibilityHelperService.instance == null) {
            toast("Accessibility ON karo: MYRA ko allow karo")
            try { start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            throw CommandException("accessibility service is disabled; opened accessibility settings for the user")
        }
    }

    private fun primeContact(indexStr: String?) : com.myra.assistant.data.PrimeContact {
        val list = SecurePrefs.getPrimeContacts()
        val idx = indexStr?.toIntOrNull() ?: 0
        return list.getOrNull(idx)
            ?: throw CommandException("no prime contact at index $idx — user must add one in Settings")
    }

    // ------------------------------------------------------------------ apps

    private val appMap = mapOf(
        "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient", "twitter" to "com.twitter.android",
        "x" to "com.twitter.android", "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android", "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock", "phone" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts", "play store" to "com.android.vending",
        "amazon" to "in.amazon.mShop.android.shopping", "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm", "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user", "zoom" to "us.zoom.videomeetings",
        "meet" to "com.google.android.apps.meetings", "teams" to "com.microsoft.teams",
        "tiktok" to "com.zhiliaoapp.musically", "discord" to "com.discord",
        "linkedin" to "com.linkedin.android"
    )

    private fun openApp(nameRaw: String): String {
        val name = nameRaw.lowercase().trim()
        if (name == "settings") {
            start(Intent(Settings.ACTION_SETTINGS))
            return "success: Settings opened"
        }
        val pm = ctx.packageManager

        appMap[name]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { start(it); return "success: opened $nameRaw" }
        }

        // Fallback: fuzzy match on installed launcher apps
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(main, 0).filter {
            val label = it.loadLabel(pm).toString().lowercase()
            label == name || label.contains(name) || name.contains(label)
        }
        val hit = matches.firstOrNull()
        if (hit != null) {
            pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.let {
                start(it)
                return "success: opened ${hit.loadLabel(pm)}"
            }
        }
        throw CommandException("app '$nameRaw' not found on this phone")
    }

    // ------------------------------------------------------------------ contacts & messaging

    private fun resolveNumber(target: String): String {
        val t = target.trim()
        if (Regex("^\\+?[0-9][0-9\\s-]{4,}$").matches(t)) return t.replace(Regex("[\\s-]"), "")

        SecurePrefs.getPrimeContacts().firstOrNull { it.name.equals(t, ignoreCase = true) }
            ?.let { return it.number }

        if (!granted(Manifest.permission.READ_CONTACTS))
            throw CommandException("contacts permission not granted")

        val found = linkedMapOf<String, String>() // name -> number
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$t%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                if (!found.containsKey(n)) found[n] = c.getString(1) ?: ""
            }
        }
        return when {
            found.isEmpty() -> throw CommandException("no contact named '$t'")
            found.size > 1 -> {
                val exact = found.entries.firstOrNull { it.key.equals(t, ignoreCase = true) }
                exact?.value?.replace(Regex("[\\s-]"), "")
                    ?: throw CommandException("multiple matches: ${found.keys.take(5).joinToString(", ")} — ask which one")
            }
            else -> found.values.first().replace(Regex("[\\s-]"), "")
        }
    }

    private fun makeCall(target: String): String {
        val number = resolveNumber(target)
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (granted(Manifest.permission.CALL_PHONE)) {
            start(Intent(Intent.ACTION_CALL, uri))
            "success: calling $target"
        } else {
            start(Intent(Intent.ACTION_DIAL, uri))
            "success: dialer opened for $target (call permission not granted, user must press call)"
        }
    }

    private fun sendSms(target: String, message: String): String {
        val number = resolveNumber(target)
        start(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", message)
        )
        return "success: message app opened for $target — user must press send"
    }

    private fun sendWhatsApp(target: String, message: String): String {
        val digits = resolveNumber(target).filter { it.isDigit() }
        if (digits.isEmpty()) throw CommandException("invalid number")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(message))))
        return "success: WhatsApp chat opened for $target — user must press send"
    }

    // ------------------------------------------------------------------ device

    private fun changeVolume(direction: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (direction.equals("down", true)) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return "success: volume $direction"
    }

    private fun setFlashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw CommandException("no flashlight on this device")
        cm.setTorchMode(id, on)
        return "success: flashlight ${if (on) "on" else "off"}"
    }

    @Suppress("MissingPermission")
    private fun answerCall(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS))
            throw CommandException("ANSWER_PHONE_CALLS permission not granted")
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.acceptRingingCall()
        return "success: call answered"
    }

    @Suppress("MissingPermission")
    private fun rejectCall(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS))
            throw CommandException("ANSWER_PHONE_CALLS permission not granted")
        if (Build.VERSION.SDK_INT < 28) throw CommandException("rejecting calls needs Android 9 or newer")
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val ok = tm.endCall()
        return if (ok) "success: call rejected" else "error: could not end the call"
    }
}
