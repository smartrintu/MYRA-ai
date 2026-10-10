package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import java.util.Locale

/** OFFLINE FALLBACK ONLY (when the Live session is down). Hinglish + English. */
object CommandParser {

    private val primeWords = listOf("close friend", "my love", "meri jaan", "jaan", "love", "girlfriend")

    fun parse(raw: String): AppCommand? {
        val t = raw.lowercase(Locale.getDefault()).trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null

        if (t.contains("volume")) {
            if (Regex("(up|badhao|badha|increase|zyada|tez)").containsMatchIn(t))
                return AppCommand("change_volume", mapOf("direction" to "up"))
            if (Regex("(down|kam|decrease|low|dheere)").containsMatchIn(t))
                return AppCommand("change_volume", mapOf("direction" to "down"))
        }
        if (t.contains("torch") || t.contains("flashlight")) {
            val off = Regex("\\b(off|band|bandh)\\b").containsMatchIn(t)
            return AppCommand("set_flashlight", mapOf("on" to (!off).toString()))
        }
        if (t.contains("wifi") || t.contains("wi-fi")) return AppCommand("open_wifi_settings")
        if (t.contains("bluetooth")) return AppCommand("open_bluetooth_settings")

        Regex("^(?:call|phone)\\s+(.+)$").find(t)?.let { return callCmd(it.groupValues[1]) }
        Regex("^(.+?)\\s+ko\\s+(?:call|phone)\\s*(?:karo|kar do|kardo|lagao)?$").find(t)
            ?.let { return callCmd(it.groupValues[1]) }

        Regex("^(?:close|exit)\\s+(.+)$").find(t)?.let { return AppCommand("close_app") }
        Regex("^(.+?)\\s+(?:band karo|band kar do|close karo)$").find(t)?.let { return AppCommand("close_app") }

        Regex("^(?:open|launch|start)\\s+(.+)$").find(t)
            ?.let { return AppCommand("open_app", mapOf("app_name" to it.groupValues[1].trim())) }
        Regex("^(.+?)\\s+(?:kholo|khol do|open karo|chalao|chalu karo|start karo)$").find(t)
            ?.let { return AppCommand("open_app", mapOf("app_name" to it.groupValues[1].trim())) }

        return null
    }

    private fun callCmd(target: String): AppCommand {
        val tt = target.trim().removePrefix("my ").removePrefix("mere ").removePrefix("meri ")
        return if (primeWords.any { tt.contains(it) })
            AppCommand("call_prime_contact", mapOf("index" to "0"))
        else
            AppCommand("make_call", mapOf("name_or_number" to tt))
    }
}
