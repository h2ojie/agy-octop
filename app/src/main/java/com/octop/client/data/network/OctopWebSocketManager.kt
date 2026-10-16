package com.octop.client.data.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.octop.client.data.model.HitlActionRequest
import com.octop.client.data.model.HitlPendingInfo
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class OctopWebSocketManager(
    val agentId: String,
    val initialThreadId: String? = null,
    private val onToken: (threadId: String?, content: String) -> Unit,
    private val onThinking: (threadId: String?, content: String) -> Unit,
    private val onToolCall: (threadId: String?, name: String) -> Unit,
    private val onHitlRequired: (threadId: String?, hitlInfo: HitlPendingInfo) -> Unit,
    private val onDone: (threadId: String?) -> Unit,
    private val onError: (threadId: String?, message: String) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onThreadBound: ((String) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    var isConnected: Boolean = false
        private set
    private var isManuallyClosed = false
    var activeThreadId: String? = initialThreadId
        private set

    private val reconnectRunnable = Runnable {
        if (!isManuallyClosed && !isConnected) {
            Log.d("OctopWS", "Auto-reconnecting WebSocket for agent $agentId (thread $activeThreadId)...")
            connect(activeThreadId)
        }
    }

    fun connect(threadId: String? = null) {
        isManuallyClosed = false
        mainHandler.removeCallbacks(reconnectRunnable)
        if (threadId != null) {
            activeThreadId = threadId
        }

        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false

        val url = NetworkConfig.getWsUrl(agentId)
        val requestBuilder = Request.Builder().url(url)
            .header("X-Octop-App-Key", NetworkConfig.appKey)

        NetworkConfig.authToken?.let {
            if (it.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $it")
            }
        }

        val request = requestBuilder.build()
        val client = ApiClient.okHttpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS) // 放宽心跳至 30 秒，降低移动端待机耗电与基带唤醒
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("OctopWS", "WebSocket connected for agent $agentId (thread: $activeThreadId)")
                mainHandler.post {
                    onConnectionChange(true)
                    // 如果有已有会话，发送 subscribe 续接状态
                    activeThreadId?.let { subscribe(it) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    val type = json.get("type")?.asString ?: "user_turn"
                    val parsedTid = json.get("thread_id")?.asString ?: activeThreadId

                    mainHandler.post {
                        if (!parsedTid.isNullOrBlank()) {
                            val wasDraftOrNull = (activeThreadId == null || activeThreadId!!.startsWith("draft_"))
                            activeThreadId = parsedTid
                            if (wasDraftOrNull) {
                                onThreadBound?.invoke(parsedTid)
                            }
                        }

                        when (type) {
                            "token" -> {
                                val content = json.get("content")?.asString ?: ""
                                onToken(parsedTid, content)
                            }
                            // 兼容 LangGraph 与服务端 reasoning 与 thinking 双字段
                            "thinking", "reasoning" -> {
                                val content = json.get("content")?.asString ?: ""
                                onThinking(parsedTid, content)
                            }
                            "tool_call" -> {
                                val name = json.get("name")?.asString ?: "工具调用"
                                onToolCall(parsedTid, name)
                            }
                            "hitl_required" -> {
                                val req = json.getAsJsonObject("request")
                                val actionRequests = mutableListOf<HitlActionRequest>()
                                if (req != null && req.has("action_requests")) {
                                    val arr = req.getAsJsonArray("action_requests")
                                    if (arr != null) {
                                        for (elem in arr) {
                                            if (elem.isJsonObject) {
                                                val obj = elem.asJsonObject
                                                val name = obj.get("name")?.asString ?: "tool"
                                                val desc = obj.get("description")?.asString
                                                val argsMap = mutableMapOf<String, Any?>()
                                                val argsObj = obj.getAsJsonObject("args")
                                                if (argsObj != null) {
                                                    for (entry in argsObj.entrySet()) {
                                                        val v = entry.value
                                                        argsMap[entry.key] = if (v.isJsonPrimitive) v.asString else v.toString()
                                                    }
                                                }
                                                actionRequests.add(HitlActionRequest(name = name, args = argsMap, description = desc))
                                            }
                                        }
                                    }
                                }
                                val pendingId = req?.get("pending_id")?.asString ?: json.get("pending_id")?.asString
                                val hitlInfo = HitlPendingInfo(
                                    pendingId = pendingId,
                                    actionRequests = actionRequests,
                                    rawJson = text
                                )
                                onHitlRequired(parsedTid, hitlInfo)
                            }
                            "done" -> onDone(parsedTid)
                            "error" -> {
                                val msg = json.get("message")?.asString ?: "未知错误"
                                if (msg.contains("not found")) {
                                    activeThreadId = null
                                }
                                onError(parsedTid, msg)
                            }
                            "turn_status" -> {
                                val active = json.get("active")?.asBoolean ?: false
                                if (!active) onDone(parsedTid)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("OctopWS", "Failed to parse ws frame: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.w("OctopWS", "WebSocket failure: ${t.localizedMessage}")
                mainHandler.post {
                    onConnectionChange(false)
                    onError(activeThreadId, t.localizedMessage ?: "WebSocket 连接断开")
                    if (!isManuallyClosed) {
                        mainHandler.postDelayed(reconnectRunnable, 2000)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("OctopWS", "WebSocket closed: code=$code, reason=$reason")
                mainHandler.post {
                    onConnectionChange(false)
                    if (!isManuallyClosed && code != 1000) {
                        mainHandler.postDelayed(reconnectRunnable, 2000)
                    }
                }
            }
        })
    }

    fun reconnect() {
        connect(activeThreadId)
    }

    fun sendUserTurn(
        text: String,
        threadId: String? = null,
        attachments: List<com.octop.client.data.model.AttachmentItem> = emptyList(),
        model: String? = null,
        reasoningEffort: String? = null
    ): Boolean {
        val ws = webSocket
        if (ws == null || !isConnected) {
            Log.w("OctopWS", "sendUserTurn failed: socket not connected")
            return false
        }
        val targetTid = threadId ?: activeThreadId
        if (!targetTid.isNullOrBlank()) {
            activeThreadId = targetTid
        }

        val promptText = if (text.isBlank() && attachments.isNotEmpty()) "请查看并分析附件内容" else text

        val payload = JsonObject().apply {
            addProperty("type", "user_turn")
            addProperty("text", promptText)
            if (!targetTid.isNullOrBlank()) {
                addProperty("thread_id", targetTid)
            }
            if (!model.isNullOrBlank()) {
                addProperty("model", model)
                addProperty("default_model", model)
            }
            if (!reasoningEffort.isNullOrBlank()) {
                addProperty("reasoning_effort", reasoningEffort)
            }
            val msgArr = JsonArray().apply {
                val userMsg = JsonObject().apply {
                    addProperty("role", "user")
                    if (attachments.isEmpty()) {
                        addProperty("content", promptText)
                    } else {
                        val contentArr = JsonArray().apply {
                            if (promptText.isNotBlank()) {
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", promptText)
                                })
                            }
                            for (att in attachments) {
                                val path = att.workspacePath ?: continue
                                add(JsonObject().apply {
                                    addProperty("type", if (att.isImage) "image" else "file")
                                    addProperty("workspace_path", path)
                                    addProperty("filename", att.filename)
                                    addProperty("media_type", att.mimeType)
                                })
                            }
                        }
                        add("content", contentArr)
                    }
                }
                add(userMsg)
            }
            add("messages", msgArr)
        }
        val sent = ws.send(payload.toString())
        Log.d("OctopWS", "sendUserTurn result: sent=$sent, threadId=$targetTid, attachments=${attachments.size}")
        return sent
    }

    fun subscribe(threadId: String): Boolean {
        activeThreadId = threadId
        val ws = webSocket
        if (ws == null || !isConnected) return false
        val payload = JsonObject().apply {
            addProperty("type", "subscribe")
            addProperty("thread_id", threadId)
        }
        return ws.send(payload.toString())
    }

    fun cancelTurn(threadId: String? = null): Boolean {
        val ws = webSocket
        if (ws == null || !isConnected) return false
        val targetTid = threadId ?: activeThreadId
        if (targetTid.isNullOrBlank()) return false
        val payload = JsonObject().apply {
            addProperty("type", "cancel")
            addProperty("thread_id", targetTid)
        }
        return ws.send(payload.toString())
    }

    fun disconnect() {
        isManuallyClosed = true
        mainHandler.removeCallbacks(reconnectRunnable)
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false
    }
}
