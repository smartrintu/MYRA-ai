package com.myra.assistant.ai

import com.myra.assistant.data.SecurePrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deep Research: uses the first connector that has an API key
 * (Perplexity -> Tavily -> Claude). Call from a background thread.
 */
object ResearchClient {
    data class Result(val text: String, val source: String)

    private const val CLAUDE_MODEL = "claude-sonnet-5-5"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    fun hasAnyKey() = listOf("perplexity", "tavily", "claude").any { SecurePrefs.connectorKey(it).isNotBlank() }

    fun run(query: String): Result? {
        val p = SecurePrefs.connectorKey("perplexity")
        if (p.isNotBlank()) perplexity(p, query)?.let { return Result(it, "Perplexity") }
        val t = SecurePrefs.connectorKey("tavily")
        if (t.isNotBlank()) tavily(t, query)?.let { return Result(it, "Tavily") }
        val c = SecurePrefs.connectorKey("claude")
        if (c.isNotBlank()) claude(c, query)?.let { return Result(it, "Claude") }
        return null
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject? {
        return try {
            val rb = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            headers.forEach { (k, v) -> rb.header(k, v) }
            http.newCall(rb.build()).execute().use { r ->
                val txt = r.body?.string() ?: return null
                if (!r.isSuccessful) null else JSONObject(txt)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun perplexity(key: String, q: String): String? {
        val body = JSONObject()
            .put("model", "sonar-pro")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", q)))
        val j = post("https://api.perplexity.ai/chat/completions", body, mapOf("Authorization" to "Bearer $key"))
            ?: return null
        return j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
    }

    private fun tavily(key: String, q: String): String? {
        val body = JSONObject().put("query", q).put("include_answer", true)
            .put("max_results", 5).put("search_depth", "advanced")
        val j = post("https://api.tavily.com/search", body, mapOf("Authorization" to "Bearer $key"))
            ?: return null
        val sb = StringBuilder()
        val ans = j.optString("answer")
        if (ans.isNotBlank() && ans != "null") sb.append(ans).append("\n\n")
        val res = j.optJSONArray("results")
        if (res != null) {
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                sb.append("• ").append(o.optString("title")).append("\n")
                    .append(o.optString("content").take(220)).append("\n")
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun claude(key: String, q: String): String? {
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 1500)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        "Do a thorough, well-structured research briefing on the topic below. " +
                            "Use short sections and bullet points, be factual, mention uncertainty.\n\nTopic: $q"
                    )
                )
            )
        val j = post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        ) ?: return null
        val arr = j.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "text") sb.append(o.optString("text"))
        }
        return sb.toString().ifBlank { null }
    }
}
