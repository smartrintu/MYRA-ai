package com.myra.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.license.LicenseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Gemini Live API over WebSocket (BidiGenerateContent).
 * Handles setup, audio in/out, transcripts, function calls, session resumption, goAway and backoff reconnect.
 */
class GeminiLiveClient(private val context: Context) {

    // Called on main thread unless noted
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onToolCall: ((id: String, name: String, args: JSONObject) -> Unit)? = null

    // Called on the socket thread (low latency / ordering with audio)
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var gen = 0
    @Volatile private var manualClose = false
    @Volatile private var resumeHandle: String? = null
    @Volatile private var lastAudioSentAt = 0L
    private var retry = 0
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile
    var isConnected = false
        private set

    companion object {
        private const val MAX_RETRIES = 8
    }

    // ---------------------------------------------------------------- public API

    fun connect(fresh: Boolean = false) {
        if (fresh) resumeHandle = null
        manualClose = false
        retry = 0
        openSocket()
    }

    fun disconnect() {
        manualClose = true
        gen++
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        isConnected = false
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
    }

    /** Fresh session with the current settings (voice/model/personality). */
    fun reconnect() {
        disconnect()
        connect(fresh = true)
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    fun sendAudio(pcm: ByteArray) {
        if (!isConnected) return
        lastAudioSentAt = System.currentTimeMillis()
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", b64)
            )
        )
        socket?.send(msg.toString())
    }

    fun sendAudioStreamEnd() {
        if (!isConnected) return
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
    }

    fun sendText(text: String) {
        if (!isConnected) return
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    fun sendToolResponse(id: String, name: String, result: String) {
        val fr = JSONObject().put("id", id).put("name", name)
            .put("response", JSONObject().put("result", result))
        val msg = JSONObject().put(
            "toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr))
        )
        socket?.send(msg.toString())
    }

    // ---------------------------------------------------------------- connection

    private fun openSocket() {
        val key = SecurePrefs.apiKey.trim()
        if (key.isEmpty()) {
            main.post { onError?.invoke("API key missing — Settings mein daalo") }
            return
        }
        val old = socket
        socket = null
        try { old?.cancel() } catch (_: Exception) {}

        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.${GeminiModels.API_VERSION}.GenerativeService.BidiGenerateContent?key=$key"
        val myGen = ++gen
        http.newWebSocket(Request.Builder().url(url).build(), newListener(myGen))
    }

    private fun newListener(myGen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (myGen != gen) return
            socket = webSocket
            sendSetup(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (myGen != gen) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (myGen != gen) return
            handleMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (myGen != gen) return
            handleClosed(code, "closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (myGen != gen) return
            handleClosed(-1, "failure: ${t.message}")
        }
    }

    private fun handleClosed(code: Int, reason: String) {
        isConnected = false
        keepAliveJob?.cancel()
        main.post { onDisconnected?.invoke(reason) }
        if (manualClose) return

        if (code == 1007 || code == 1008) {
            main.post { onError?.invoke("Server ne reject kiya: $reason") }
        }
        if (retry >= MAX_RETRIES) {
            main.post { onError?.invoke("Connection fail ho gaya: $reason") }
            return
        }
        val delayMs = min(1000L shl retry, 15000L)
        retry++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualClose) openSocket()
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("model", SecurePrefs.model)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", SecurePrefs.voice)
                    )
                ))
                put("temperature", 0.9)
            })
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", PromptBuilder.build(context)))
            ))
            if (LicenseManager.allowsControl(context)) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", FunctionDeclarations.build())))
            }
            // Faster turn-taking: MYRA decides you finished speaking after ~0.35 s of silence instead of the slow default.
            put("realtimeInputConfig", JSONObject().put(
                "automaticActivityDetection", JSONObject()
                    .put("disabled", false)
                    .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    .put("prefixPaddingMs", 20)
                    .put("silenceDurationMs", 350)
            ))
            put("sessionResumption", JSONObject().apply {
                resumeHandle?.let { put("handle", it) }
            })
            put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            put("outputAudioTranscription", JSONObject())
            put("inputAudioTranscription", JSONObject())
        }
        ws.send(JSONObject().put("setup", setup).toString())
    }

    // ---------------------------------------------------------------- incoming

    private fun handleMessage(text: String) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }

        if (obj.has("setupComplete")) {
            isConnected = true
            retry = 0
            lastAudioSentAt = System.currentTimeMillis()
            startKeepAlive()
            main.post { onConnected?.invoke() }
        }

        obj.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        try { onAudioReceived?.invoke(Base64.decode(data, Base64.DEFAULT)) } catch (_: Exception) {}
                    }
                }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
                main.post { onOutputTranscript?.invoke(t) }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
                main.post { onInputTranscript?.invoke(t) }
            }
            if (sc.optBoolean("interrupted")) onInterrupted?.invoke()
            if (sc.optBoolean("turnComplete")) main.post { onTurnComplete?.invoke() }
        }

        obj.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                main.post { onToolCall?.invoke(id, name, args) }
            }
        }

        obj.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (u.optBoolean("resumable", true) && h.isNotEmpty()) resumeHandle = h
        }

        if (obj.has("goAway")) {
            // Server will close soon — reconnect proactively with the resumption handle.
            retry = 0
            try { socket?.close(1000, "goAway") } catch (_: Exception) {}
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(8000)
                if (isConnected && System.currentTimeMillis() - lastAudioSentAt > 7500) {
                    sendAudio(ByteArray(1280)) // ~40 ms of silence
                }
            }
        }
    }
}
