package com.myra.assistant.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.myra.assistant.ai.GeminiModels
import com.myra.assistant.data.SecurePrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Asks Gemini (REST, text model) to write a complete single-file website and stores it. */
object WebsiteBuilder {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun newSitePrompt(description: String): String {
        val lang = when (SecurePrefs.language) {
            "BANGLA" -> "Bangla"
            "ENGLISH" -> "English"
            else -> "English (or simple Hinglish if the brief clearly asks for it)"
        }
        return """
            You are a world-class web designer and front-end developer.
            Build a COMPLETE, modern, beautiful and fully responsive website as ONE single HTML file
            (inline CSS and inline JavaScript, no build tools, no frameworks required).
            Requirements:
            - mobile-first layout, clean typography (Google Fonts are allowed), smooth animations, great colors
            - real-looking content for this business/person (no lorem ipsum); do not use broken external images,
              use CSS gradients, emoji or inline SVG instead
            - sections: hero with call-to-action, about, services/features, gallery or portfolio, testimonials,
              pricing or plans if it makes sense, contact form (front-end only) and footer
            - working navigation (smooth scroll, hamburger menu on mobile)
            Website language: $lang.
            Brief from the owner: $description
            Return ONLY the raw HTML, starting with <!DOCTYPE html>. No explanations and no markdown fences.
        """.trimIndent()
    }

    fun editSitePrompt(currentHtml: String, instructions: String): String = """
        You are a world-class web designer and front-end developer.
        Here is the current website as a single HTML file:
        ----- CURRENT HTML START -----
        $currentHtml
        ----- CURRENT HTML END -----
        Apply these changes: $instructions
        Return the COMPLETE updated HTML file only, starting with <!DOCTYPE html>. No explanations and no markdown fences.
    """.trimIndent()

    /** Streams the answer (SSE) so the code can be shown live in the terminal while it is being written.
     *  If a model is retired (404) or rejects a setting (400), it silently tries the next model. */
    @Throws(CommandException::class)
    fun generate(prompt: String, onChunk: ((String) -> Unit)? = null): String {
        val key = SecurePrefs.apiKey.trim()
        if (key.isEmpty()) throw CommandException("Gemini API key missing")
        var lastErr = "Gemini error"
        for (model in listOf(GeminiModels.TEXT_MODEL) + GeminiModels.TEXT_FALLBACKS) {
            for (thinking in listOf(true, false)) {
                val cfg = JSONObject().put("temperature", 0.8).put("maxOutputTokens", 20000)
                if (thinking) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", cfg)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/$model:streamGenerateContent?alt=sse&key=$key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                var code = 0
                val out: String? = http.newCall(req).execute().use { r ->
                    code = r.code
                    if (!r.isSuccessful) {
                        lastErr = "Gemini error ${r.code} ($model): ${(r.body?.string() ?: "").take(160)}"
                        null
                    } else {
                        readStream(r, onChunk)
                    }
                }
                if (out != null) return cleanHtml(out)
                if (code == 400 && thinking) continue            // retry same model without thinkingConfig
                if (code == 400 || code == 404 || code == 503) break // try the next model
                throw CommandException(lastErr)                   // key / quota problems: stop
            }
        }
        throw CommandException(lastErr)
    }

    private fun readStream(r: okhttp3.Response, onChunk: ((String) -> Unit)?): String {
        val src = r.body?.source() ?: throw CommandException("empty answer from Gemini")
        val sb = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val json = line.removePrefix("data:").trim()
            if (json.isEmpty()) continue
            val obj = try { JSONObject(json) } catch (_: Exception) { continue }
            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text") ?: ""
                if (t.isNotEmpty()) {
                    sb.append(t)
                    onChunk?.invoke(t)
                }
            }
        }
        if (sb.isBlank()) throw CommandException("empty answer from Gemini")
        return sb.toString()
    }

    private fun cleanHtml(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "").trim()
        if (!s.contains("<", ignoreCase = true)) throw CommandException("Gemini did not return a website")
        return s
    }

    fun slug(name: String?): String {
        val s = (name ?: "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (s.isEmpty()) "my-site" else s.take(40)
    }

    fun sitesDir(ctx: Context): File = File(ctx.filesDir, "websites").apply { mkdirs() }

    fun save(ctx: Context, slug: String, html: String): File {
        val f = File(sitesDir(ctx), "$slug.html")
        f.writeText(html)
        File(sitesDir(ctx), "last.html").writeText(html)
        return f
    }

    fun lastHtml(ctx: Context): String? {
        val f = File(sitesDir(ctx), "last.html")
        return if (f.exists()) f.readText() else null
    }

    /** Copies the page to Download/MYRA (Android 10+). Returns the visible path or null. */
    fun saveToDownloads(ctx: Context, slug: String, html: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$slug.html")
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MYRA")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
            "Download/MYRA/$slug.html"
        } catch (e: Exception) {
            null
        }
    }
}
