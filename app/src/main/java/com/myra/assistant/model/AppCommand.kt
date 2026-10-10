package com.myra.assistant.model

/** type = Gemini function name (open_app, make_call, ...). */
data class AppCommand(val type: String, val params: Map<String, String> = emptyMap())
