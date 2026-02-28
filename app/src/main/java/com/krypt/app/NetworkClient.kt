package com.krypt.app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "NetworkClient"

data class KryptMessage(
    val type: String,
    val from: String,
    val to: String,
    val payload: Any? = null
)

object NetworkClient {

    const val SERVER_URL = "ws://44.212.47.1:8000/ws"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var myUuid: String = ""
    private var myPublicKey: String = ""
    private var reconnecting = false

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 200)
    val incomingMessages: SharedFlow<String> = _incomingMessages

    val isConnected: Boolean get() = webSocket != null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // Large write timeout for file transfers
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun connect(uuid: String, publicKeyB64: String) {
        myUuid = uuid
        myPublicKey = publicKeyB64
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnecting = false
                val registration = JsonObject().apply {
                    addProperty("type", "register")
                    addProperty("uuid", uuid)
                    addProperty("public_key", publicKeyB64)
                }
                webSocket.send(gson.toJson(registration))
                Log.d(TAG, "Connected and registered as $uuid")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { _incomingMessages.emit(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                scheduleReconnect(uuid, publicKeyB64)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                if (code != 1000) scheduleReconnect(uuid, publicKeyB64)
            }
        })
    }

    private fun scheduleReconnect(uuid: String, publicKeyB64: String) {
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            delay(3000)
            webSocket = null
            connect(uuid, publicKeyB64)
        }
    }

    // Send with retry — returns true if sent, false after retries exhausted
    private fun safeSend(json: String, retries: Int = 3): Boolean {
        repeat(retries) { attempt ->
            val ws = webSocket
            if (ws != null) {
                if (ws.send(json)) return true
                Log.w(TAG, "send() returned false on attempt $attempt")
                Thread.sleep(100L * (attempt + 1))
            } else {
                Thread.sleep(200)
            }
        }
        Log.e(TAG, "safeSend failed after $retries attempts")
        return false
    }

    fun sendEncryptedMessage(to: String, payload: EncryptedPayload): Boolean {
        val msg = JsonObject().apply {
            addProperty("type", "message")
            addProperty("from", myUuid)
            addProperty("to", to)
            add("payload", gson.toJsonTree(payload))
        }
        return safeSend(gson.toJson(msg))
    }

    fun sendReceipt(to: String, messageId: Long, receiptType: String) {
        val msg = JsonObject().apply {
            addProperty("type", "message")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("receipt_type", receiptType)
            addProperty("message_ref_id", messageId)
        }
        safeSend(gson.toJson(msg))
    }

    fun sendReadReceipt(to: String) {
        val msg = JsonObject().apply {
            addProperty("type", "message")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("receipt_type", "read_all")
        }
        safeSend(gson.toJson(msg))
    }

    // Sends a single chunk — returns true on success
    fun sendFileChunk(to: String, chunk: EncryptedFileChunk): Boolean {
        val msg = JsonObject().apply {
            addProperty("type", "file_chunk")
            addProperty("from", myUuid)
            addProperty("to", to)
            add("payload", gson.toJsonTree(chunk))
        }
        return safeSend(gson.toJson(msg), retries = 5)
    }

    fun sendWebRTCOffer(to: String, sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_offer")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
        safeSend(gson.toJson(msg))
    }

    fun sendWebRTCAnswer(to: String, sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_answer")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
        safeSend(gson.toJson(msg))
    }

    fun sendICECandidate(to: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_ice")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("candidate", candidate)
            addProperty("sdpMid", sdpMid ?: "")
            addProperty("sdpMLineIndex", sdpMLineIndex)
        }
        safeSend(gson.toJson(msg))
    }

    fun sendStatus(encryptedPayload: EncryptedPayload) {
        val msg = JsonObject().apply {
            addProperty("type", "status")
            addProperty("from", myUuid)
            add("payload", gson.toJsonTree(encryptedPayload))
        }
        safeSend(gson.toJson(msg))
    }

    fun sendStatusPlain(fromUuid: String, content: String) {
        val msg = JsonObject().apply {
            addProperty("type", "status")
            addProperty("from", fromUuid)
            addProperty("content", content)
        }
        safeSend(gson.toJson(msg))
    }

    fun requestPublicKey(targetUuid: String) {
        val msg = JsonObject().apply {
            addProperty("type", "get_public_key")
            addProperty("from", myUuid)
            addProperty("target", targetUuid)
        }
        safeSend(gson.toJson(msg))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    fun parseMessage(raw: String): JsonObject? {
        return try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            null
        }
    }
}
