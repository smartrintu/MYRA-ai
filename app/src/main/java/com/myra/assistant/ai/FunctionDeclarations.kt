package com.myra.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Gemini function-calling schema for all phone actions. */
object FunctionDeclarations {

    private fun prop(type: String, desc: String) =
        JSONObject().put("type", type).put("description", desc)

    private fun fn(
        name: String,
        desc: String,
        props: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, v) }
            val params = JSONObject().put("type", "OBJECT").put("properties", p)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            o.put("parameters", params)
        }
        return o
    }

    fun build(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by name (YouTube, WhatsApp, Chrome, ...).",
            mapOf("app_name" to prop("STRING", "App name, e.g. youtube")), listOf("app_name")))
        put(fn("close_app", "Close/minimize the current app by going to the home screen."))
        put(fn("make_call", "Call a contact by name or a phone number.",
            mapOf("name_or_number" to prop("STRING", "Contact name or phone number")), listOf("name_or_number")))
        put(fn("send_sms", "Open the SMS composer with a message for a contact or number.",
            mapOf(
                "name_or_number" to prop("STRING", "Contact name or phone number"),
                "message" to prop("STRING", "Message text")
            ), listOf("name_or_number", "message")))
        put(fn("send_whatsapp", "Open a WhatsApp chat with a prefilled message and press send automatically when accessibility is on.",
            mapOf(
                "name_or_number" to prop("STRING", "Contact name or phone number"),
                "message" to prop("STRING", "Message text")
            ), listOf("name_or_number")))
        put(fn("call_prime_contact", "Call one of the user's prime contacts.",
            mapOf("index" to prop("INTEGER", "0 for the first prime contact (default), 1 for second, ...")), emptyList()))
        put(fn("message_prime_contact", "Message one of the user's prime contacts.",
            mapOf(
                "index" to prop("INTEGER", "Prime contact index, default 0"),
                "message" to prop("STRING", "Message text")
            ), listOf("message")))
        put(fn("change_volume", "Raise or lower the media volume.",
            mapOf("direction" to prop("STRING", "up or down")), listOf("direction")))
        put(fn("set_flashlight", "Turn the flashlight/torch on or off.",
            mapOf("on" to prop("BOOLEAN", "true to turn on, false to turn off")), listOf("on")))
        put(fn("open_wifi_settings", "Open the WiFi / internet settings panel (cannot toggle silently)."))
        put(fn("open_bluetooth_settings", "Open the Bluetooth settings (cannot toggle silently)."))
        put(fn("answer_call", "Answer the currently ringing phone call."))
        put(fn("reject_call", "Reject / end the currently ringing phone call."))
        put(fn("go_back", "Press the Back button."))
        put(fn("click_on_screen_text", "Tap a button or item on the current screen that shows this text.",
            mapOf("text" to prop("STRING", "Visible text to tap")), listOf("text")))
        put(fn("type_text", "Type text into the focused input field on screen.",
            mapOf("text" to prop("STRING", "Text to type")), listOf("text")))
        put(fn("scroll_screen", "Scroll the current screen.",
            mapOf("direction" to prop("STRING", "up or down")), listOf("direction")))
        put(fn("play_youtube", "Search YouTube for a song or video and start playing it. Use this whenever the user wants to play/listen to a song or watch a video.",
            mapOf("query" to prop("STRING", "Song or video name, e.g. 'Tum Hi Ho Arijit Singh'")), listOf("query")))
        put(fn("search_web", "Search Google for something.",
            mapOf("query" to prop("STRING", "Search text")), listOf("query")))
        put(fn("open_url", "Open a web link (http/https).",
            mapOf("url" to prop("STRING", "Full URL")), listOf("url")))
        put(fn("navigate_to", "Start Google Maps navigation to a place.",
            mapOf("place" to prop("STRING", "Place name or address")), listOf("place")))
        put(fn("media_control", "Control the currently playing media.",
            mapOf("action" to prop("STRING", "play_pause, next, previous or stop")), listOf("action")))
        put(fn("system_action", "Phone system buttons/gestures.",
            mapOf("action" to prop("STRING", "home, back, recents, notifications, quick_settings, lock_screen, screenshot or power_menu")), listOf("action")))
        put(fn("set_alarm", "Set an alarm.",
            mapOf(
                "hour" to prop("INTEGER", "Hour in 24h format (0-23)"),
                "minute" to prop("INTEGER", "Minute (0-59)"),
                "label" to prop("STRING", "Optional label")
            ), listOf("hour", "minute")))
        put(fn("set_timer", "Start a countdown timer.",
            mapOf("seconds" to prop("INTEGER", "Duration in seconds")), listOf("seconds")))
        put(fn("read_screen", "Read the visible text on the current screen so you can describe it or decide what to tap."))
        put(fn("unlock_phone", "Wake the screen and unlock the phone. Works fully only if the phone has a swipe-only lock; with a PIN/pattern/fingerprint the user must unlock it personally. Safe to call when already unlocked."))
    }
}
