package com.myra.assistant.ai

/**
 * IMPORTANT: preview model IDs get retired regularly.
 * Verify at https://ai.google.dev/gemini-api/docs/live and edit ONLY this file.
 */
object GeminiModels {
    const val API_VERSION = "v1beta"

    /** Text model used to write websites (verify the ID in Google's docs; edit only here). */
    const val TEXT_MODEL = "models/gemini-2.5-flash"

    data class ModelOption(val label: String, val id: String)
    data class VoiceOption(val label: String, val id: String)

    val models = listOf(
        ModelOption("Native Audio (Human Voice) — DEFAULT", "models/gemini-2.5-flash-native-audio-preview-12-2025"),
        ModelOption("Flash Live (Fast)", "models/gemini-2.0-flash-live-001"),
        ModelOption("Native Audio Dialog (legacy)", "models/gemini-2.5-flash-preview-native-audio-dialog")
    )
    const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"

    val voices = listOf(
        VoiceOption("Aoede (Female)", "Aoede"),
        VoiceOption("Charon (Male)", "Charon"),
        VoiceOption("Kore (Female)", "Kore"),
        VoiceOption("Fenrir (Male)", "Fenrir"),
        VoiceOption("Puck (Male)", "Puck"),
        VoiceOption("Leda (Female)", "Leda"),
        VoiceOption("Orus (Male)", "Orus"),
        VoiceOption("Zephyr (Female)", "Zephyr")
    )
    const val DEFAULT_VOICE = "Aoede"
}
