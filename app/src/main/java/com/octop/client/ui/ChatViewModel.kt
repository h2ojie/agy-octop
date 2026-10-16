package com.octop.client.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.octop.client.OctopApp
import com.octop.client.data.model.AgentItem
import com.octop.client.data.model.AttachmentItem
import com.octop.client.data.model.AttachmentStatus
import com.octop.client.data.model.ChatMessage
import com.octop.client.data.model.DEFAULT_RESOLVED_MODELS
import com.octop.client.data.model.HistoryMessageDto
import com.octop.client.data.model.HitlDecision
import com.octop.client.data.model.HitlPendingInfo
import com.octop.client.data.model.HitlResumeBody
import com.octop.client.data.model.MessageParser
import com.octop.client.data.model.QueuedMessage
import com.octop.client.data.model.ResolvedModel
import com.octop.client.data.model.ThreadItem
import com.octop.client.data.network.ApiClient
import com.octop.client.data.network.LoginRequest
import com.google.gson.JsonElement
import com.octop.client.data.network.NetworkConfig
import com.octop.client.data.network.OctopWebSocketManager
import com.octop.client.data.repository.OctopRepository
import com.octop.client.util.AttachmentUploadPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatViewModel : ViewModel() {

    private val repository = OctopRepository()

    private val _agents = MutableStateFlow<List<AgentItem>>(emptyList())
    val agents: StateFlow<List<AgentItem>> = _agents.asStateFlow()

    private val _currentAgent = MutableStateFlow<AgentItem?>(null)
    val currentAgent: StateFlow<AgentItem?> = _currentAgent.asStateFlow()

    private val _currentThread = MutableStateFlow<ThreadItem?>(null)
    val currentThread: StateFlow<ThreadItem?> = _currentThread.asStateFlow()

    private val _agentThreadsMap = MutableStateFlow<Map<String, List<ThreadItem>>>(emptyMap())
    val agentThreadsMap: StateFlow<Map<String, List<ThreadItem>>> = _agentThreadsMap.asStateFlow()

    private val _expandedAgentIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedAgentIds: StateFlow<Set<String>> = _expandedAgentIds.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _stagedAttachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    val stagedAttachments: StateFlow<List<AttachmentItem>> = _stagedAttachments.asStateFlow()

    // 会话级草稿箱：按 "$agentId:$threadId" 隔离存储每个会话未发送的文字与待发附件
    private val _conversationDrafts = ConcurrentHashMap<String, String>()
    private val _conversationStagedAttachments = ConcurrentHashMap<String, List<AttachmentItem>>()

    private fun getConversationKey(agentId: String?, threadId: String?): String {
        val aid = agentId.orEmpty()
        val tid = threadId ?: _agentActiveThreadId[aid] ?: "draft_$aid"
        return "$aid:$tid"
    }

    private fun currentConversationKey(): String? {
        val aid = _currentAgent.value?.agentId ?: return null
        val tid = _currentThread.value?.threadId ?: _agentActiveThreadId[aid] ?: "draft_$aid"
        return "$aid:$tid"
    }

    private fun saveCurrentDraft() {
        val key = currentConversationKey() ?: return
        val text = _inputText.value
        if (text.isNotEmpty()) {
            _conversationDrafts[key] = text
        } else {
            _conversationDrafts.remove(key)
        }

        val attachments = _stagedAttachments.value
        if (attachments.isNotEmpty()) {
            _conversationStagedAttachments[key] = attachments
        } else {
            _conversationStagedAttachments.remove(key)
        }
    }

    // 每个会话独立的排队消息队列：按 threadId 隔离存储，对齐 Antigravity 排队机制
    private val _conversationQueues = ConcurrentHashMap<String, MutableList<QueuedMessage>>()
    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    private fun updateCurrentQueuedMessages() {
        val aid = _currentAgent.value?.agentId
        val tid = _currentThread.value?.threadId ?: aid?.let { _agentActiveThreadId[it] } ?: "draft_$aid"
        val qList = _conversationQueues[tid]
        _queuedMessages.value = if (qList != null) {
            synchronized(qList) { qList.toList() }
        } else {
            emptyList()
        }
    }

    // 会话级模型与思考强度独立配置
    private val _threadSelectedModel = ConcurrentHashMap<String, String>()
    private val _threadSelectedEffort = ConcurrentHashMap<String, String>()

    private val _availableModels = MutableStateFlow<List<ResolvedModel>>(DEFAULT_RESOLVED_MODELS)
    val availableModels: StateFlow<List<ResolvedModel>> = _availableModels.asStateFlow()

    private val _currentSelectedModel = MutableStateFlow<String>("")
    val currentSelectedModel: StateFlow<String> = _currentSelectedModel.asStateFlow()

    private val _currentSelectedEffort = MutableStateFlow<String?>("high")
    val currentSelectedEffort: StateFlow<String?> = _currentSelectedEffort.asStateFlow()

    fun selectModel(modelRef: String, effort: String? = null) {
        val aid = _currentAgent.value?.agentId ?: return
        val tid = _currentThread.value?.threadId ?: _agentActiveThreadId[aid] ?: "draft_$aid"
        val key = getConversationKey(aid, tid)

        _threadSelectedModel[key] = modelRef
        if (effort != null) {
            _threadSelectedEffort[key] = effort
        }
        _currentSelectedModel.value = modelRef
        _currentSelectedEffort.value = effort

        // 若会话已在云端创建，异步持久化至后端
        if (!tid.startsWith("draft_")) {
            viewModelScope.launch {
                try {
                    val body = mutableMapOf<String, Any?>("model_ref" to modelRef)
                    if (effort != null) {
                        body["reasoning_effort"] = effort
                    }
                    repository.updateThreadComposer(aid, tid, body)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "updateThreadComposer failed: ${e.message}")
                }
            }
        }
    }

    private fun updateSelectedModelFor(agentId: String, threadId: String?) {
        val key = getConversationKey(agentId, threadId)
        val storedModel = _threadSelectedModel[key]
        val storedEffort = _threadSelectedEffort[key]

        val threadModel = _currentThread.value?.modelRef
        val threadEffort = _currentThread.value?.reasoningEffort

        val agent = _agents.value.firstOrNull { it.agentId == agentId } ?: _currentAgent.value
        val defaultModel = agent?.defaultModel ?: "cli/gemini-3.8-flash-high"

        val activeModel = storedModel?.ifBlank { null }
            ?: threadModel?.ifBlank { null }
            ?: defaultModel

        val activeEffort = storedEffort ?: threadEffort ?: "high"

        _currentSelectedModel.value = activeModel
        _currentSelectedEffort.value = activeEffort
    }

    fun resolveActiveModelDisplayName(agentId: String? = null, threadId: String? = null): String {
        val aid = agentId ?: _currentAgent.value?.agentId
        val tid = threadId ?: _currentThread.value?.threadId
        val key = if (aid != null) getConversationKey(aid, tid) else currentConversationKey()
        val threadItem = if (aid != null && tid != null) _agentThreadsMap.value[aid]?.find { it.threadId == tid } ?: _currentThread.value else _currentThread.value
        val rawModel = key?.let { _threadSelectedModel[it] }
            ?: _currentSelectedModel.value.ifBlank { null }
            ?: threadItem?.modelRef
            ?: _agents.value.firstOrNull { it.agentId == aid }?.defaultModel
            ?: _currentAgent.value?.defaultModel
            ?: ""
        val provider = ResolvedModel.extractProvider(rawModel).ifBlank {
            _availableModels.value.firstOrNull { it.modelRef == rawModel || it.model == rawModel }?.providerName
        }
        return ResolvedModel.formatModelWithProvider(rawModel, provider)
    }

    fun fetchResolvedModels() {
        viewModelScope.launch {
            repository.getResolvedModels().fold(
                onSuccess = { list ->
                    if (list.isNotEmpty()) {
                        _availableModels.value = list
                    }
                },
                onFailure = { e ->
                    Log.w("ChatViewModel", "fetchResolvedModels failed, using defaults: ${e.message}")
                }
            )
        }
    }

    private fun restoreDraftFor(agentId: String, threadId: String?) {
        val key = getConversationKey(agentId, threadId)
        _inputText.value = _conversationDrafts[key] ?: ""
        _stagedAttachments.value = _conversationStagedAttachments[key] ?: emptyList()
        updateCurrentQueuedMessages()
        updateSelectedModelFor(agentId, threadId)
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isFullscreenInput = MutableStateFlow(false)
    val isFullscreenInput: StateFlow<Boolean> = _isFullscreenInput.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _showLoginDialog = MutableStateFlow(NetworkConfig.authToken.isNullOrBlank())
    val showLoginDialog: StateFlow<Boolean> = _showLoginDialog.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // 1. 线程级消息缓存：threadId -> List<ChatMessage>
    private val _threadMessagesCache = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    // 轻量本地磁盘持久化缓存：存储于 filesDir/chat_cache/{threadId}.json，抗进程重建与内存回收
    private val gson = Gson()
    private val cacheDir by lazy {
        File(OctopApp.instance.filesDir, "chat_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun saveThreadToDisk(threadId: String, messages: List<ChatMessage>) {
        if (threadId.isBlank() || threadId.startsWith("draft_")) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(cacheDir, "$threadId.json")
                val json = gson.toJson(messages)
                file.writeText(json)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to save thread $threadId to disk", e)
            }
        }
    }

    private fun loadThreadFromDisk(threadId: String): List<ChatMessage>? {
        if (threadId.isBlank() || threadId.startsWith("draft_")) return null
        return try {
            val file = File(cacheDir, "$threadId.json")
            if (file.exists() && file.isFile) {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                gson.fromJson<List<ChatMessage>>(file.readText(), type)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to load thread $threadId from disk", e)
            null
        }
    }

    // 2. 当前正在流式生成的会话 ID 集合：threadId
    private val _streamingThreads = Collections.synchronizedSet(mutableSetOf<String>())

    // 3. 专家-会话复合粒度的 WebSocket 长连接池："$agentId:$threadId" -> OctopWebSocketManager
    private fun connKey(agentId: String, threadId: String): String = "$agentId:$threadId"
    private val wsManagers = ConcurrentHashMap<String, OctopWebSocketManager>()
    private val idleDisconnectJobs = ConcurrentHashMap<String, Job>()

    private fun scheduleIdleDisconnect(key: String, delayMs: Long = 60_000L) {
        idleDisconnectJobs[key]?.cancel()
        idleDisconnectJobs[key] = viewModelScope.launch {
            delay(delayMs)
            val parts = key.split(":")
            if (parts.size == 2) {
                val agentId = parts[0]
                val threadId = parts[1]
                val isCurrent = _currentAgent.value?.agentId == agentId &&
                    (_currentThread.value?.threadId == threadId || (_currentThread.value == null && threadId.startsWith("draft_")))
                val isStreaming = _streamingThreads.contains(threadId)
                if (!isCurrent && !isStreaming) {
                    Log.d("ChatViewModel", "Auto-disconnecting idle WebSocket for $key")
                    wsManagers.remove(key)?.disconnect()
                }
            }
        }
    }

    // 4. 每个正在生成的线程独立的看门狗超时监控
    private val watchdogJobs = ConcurrentHashMap<String, Job>()

    // 5. 专家当前激活的会话映射：agentId -> threadId (严格保证跨专家状态隔离与路由正确)
    private val _agentActiveThreadId = ConcurrentHashMap<String, String>()

    init {
        NetworkConfig.onAuthExpired = {
            viewModelScope.launch(Dispatchers.Main) {
                _loginError.value = "登录凭证已过期，请重新登录"
                _showLoginDialog.value = true
            }
        }
        loadAgents()
        fetchResolvedModels()
    }

    fun openLoginDialog() {
        _loginError.value = null
        _showLoginDialog.value = true
    }

    fun dismissLoginDialog() {
        _showLoginDialog.value = false
    }

    fun login(serverUrl: String, appKey: String, username: String, password: String) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            try {
                val cleanUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                NetworkConfig.baseUrl = cleanUrl
                NetworkConfig.appKey = appKey

                val result = repository.login(LoginRequest(username = username, password = password))
                result.fold(
                    onSuccess = { resp ->
                        val token = resp.accessToken
                        NetworkConfig.authToken = token
                        _showLoginDialog.value = false
                        _toastMessage.value = "登录成功，已连接 Octop！"
                        loadAgents()
                        fetchResolvedModels()
                    },
                    onFailure = { err ->
                        _loginError.value = if (err.message?.contains("AUTH_FAILED") == true) {
                            "用户名或密码错误"
                        } else {
                            "登录失败: ${err.localizedMessage ?: "请检查网络或配置"}"
                        }
                    }
                )
            } catch (e: Exception) {
                _loginError.value = "连接失败: ${e.localizedMessage ?: "请检查网络或服务器地址"}"
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            val result = repository.getAgents()
            result.fold(
                onSuccess = { list ->
                    if (list.isNotEmpty()) {
                        _agents.value = list
                        if (_currentAgent.value == null) {
                            selectAgent(list.first())
                        }
                    } else if (_agents.value.isEmpty()) {
                        _agents.value = defaultExperts
                        selectAgent(defaultExperts.first())
                    }
                },
                onFailure = {
                    if (_agents.value.isEmpty()) {
                        _agents.value = defaultExperts
                        selectAgent(defaultExperts.first())
                    }
                }
            )
        }
    }

    fun selectAgent(agent: AgentItem) {
        if (_currentAgent.value?.agentId == agent.agentId) return

        // 切换前暂存当前会话草稿（文字与附件）
        saveCurrentDraft()

        // 切换前，如果旧会话未在流式生成，触发 60s 空闲断连倒计时
        val oldAgent = _currentAgent.value
        val oldThread = _currentThread.value
        if (oldAgent != null && oldThread != null) {
            scheduleIdleDisconnect(connKey(oldAgent.agentId, oldThread.threadId))
        }

        _currentAgent.value = agent

        // 尝试恢复该专家之前选中的会话，避免切换时会话短暂空白
        val lastTid = _agentActiveThreadId[agent.agentId]
        if (lastTid != null) {
            val existingThread = _agentThreadsMap.value[agent.agentId]?.find { it.threadId == lastTid }
            _currentThread.value = existingThread ?: ThreadItem(
                threadId = lastTid,
                agentId = agent.agentId,
                title = "当前会话",
                isActive = true
            )
            // 先查内存，若无则从磁盘恢复
            var cached = _threadMessagesCache[lastTid]
            if (cached == null || cached.isEmpty()) {
                val diskCached = loadThreadFromDisk(lastTid)
                if (diskCached != null && diskCached.isNotEmpty()) {
                    _threadMessagesCache[lastTid] = Collections.synchronizedList(diskCached.toMutableList())
                    cached = _threadMessagesCache[lastTid]
                }
            }
            if (cached != null && cached.isNotEmpty()) {
                _messages.value = synchronized(cached) { cached.toList() }
            } else {
                _messages.value = emptyList()
            }
            _isStreaming.value = _streamingThreads.contains(lastTid)

            // 恢复该会话的专属草稿与待发附件
            restoreDraftFor(agent.agentId, lastTid)

            // 获取并激活该专家-会话专属的 WebSocket
            val ws = getOrCreateWs(agent.agentId, lastTid)
            _isConnected.value = ws.isConnected
        } else {
            _currentThread.value = null
            _messages.value = emptyList()
            _isStreaming.value = false

            // 恢复开场白草稿（若有）
            restoreDraftFor(agent.agentId, "draft_${agent.agentId}")

            val ws = getOrCreateWs(agent.agentId, "draft_${agent.agentId}")
            _isConnected.value = ws.isConnected
        }

        loadThreadsForAgent(agent.agentId)
    }

    fun toggleExpandAgent(agentId: String) {
        _expandedAgentIds.update { set ->
            if (set.contains(agentId)) set - agentId else set + agentId
        }
        loadThreadsForAgent(agentId)
    }

    fun selectThread(agent: AgentItem, thread: ThreadItem) {
        if (_currentAgent.value?.agentId == agent.agentId && _currentThread.value?.threadId == thread.threadId) {
            return
        }

        // 切换前暂存当前会话草稿（文字与附件）
        saveCurrentDraft()

        // 切换前，旧会话若非流式中，调度空闲断连
        val oldAgent = _currentAgent.value
        val oldThread = _currentThread.value
        if (oldAgent != null && oldThread != null && (oldAgent.agentId != agent.agentId || oldThread.threadId != thread.threadId)) {
            scheduleIdleDisconnect(connKey(oldAgent.agentId, oldThread.threadId))
        }

        _currentAgent.value = agent
        _currentThread.value = thread
        _agentActiveThreadId[agent.agentId] = thread.threadId

        // 1. 优先展示内存或本地磁盘缓存的对话，实现即点即看零等待抗断网
        var cached = _threadMessagesCache[thread.threadId]
        if (cached == null || cached.isEmpty()) {
            val diskCached = loadThreadFromDisk(thread.threadId)
            if (diskCached != null && diskCached.isNotEmpty()) {
                _threadMessagesCache[thread.threadId] = Collections.synchronizedList(diskCached.toMutableList())
                cached = _threadMessagesCache[thread.threadId]
            }
        }
        if (cached != null && cached.isNotEmpty()) {
            _messages.value = synchronized(cached) { cached.toList() }
        } else {
            _messages.value = emptyList()
        }

        // 2. 恢复目标会话的专属草稿与待发附件
        restoreDraftFor(agent.agentId, thread.threadId)

        // 3. 刷新当前选中会话的独立流式状态
        _isStreaming.value = _streamingThreads.contains(thread.threadId)

        // 4. 确保该 (agentId, threadId) 拥有独立的专属 WebSocket 连接
        val ws = getOrCreateWs(agent.agentId, thread.threadId)
        _isConnected.value = ws.isConnected
        if (ws.isConnected) {
            ws.subscribe(thread.threadId)
        } else {
            ws.connect(thread.threadId)
        }

        // 5. 延迟 400ms 同步拉取云端历史做权威对齐（避开服务端可能存在的 WAL 写入微小窗口）
        viewModelScope.launch {
            delay(400L)
            loadHistory(agent.agentId, thread.threadId)
        }
    }

    fun createNewThread(agent: AgentItem) {
        // 创建新会话前，先暂存当前会话的草稿
        saveCurrentDraft()

        viewModelScope.launch {
            val result = repository.createThread(agent.agentId)
            result.fold(
                onSuccess = { res ->
                    val newThread = ThreadItem(
                        threadId = res.threadId,
                        title = "新会话",
                        agentId = agent.agentId,
                        isActive = true
                    )
                    // 切换前，旧会话若非流式中，调度空闲断连
                    val oldAgent = _currentAgent.value
                    val oldThread = _currentThread.value
                    if (oldAgent != null && oldThread != null) {
                        scheduleIdleDisconnect(connKey(oldAgent.agentId, oldThread.threadId))
                    }

                    _currentAgent.value = agent
                    _currentThread.value = newThread
                    _agentActiveThreadId[agent.agentId] = newThread.threadId
                    _messages.value = emptyList()
                    _isStreaming.value = false

                    // 新会话默认草稿与待发附件为干净的空状态
                    restoreDraftFor(agent.agentId, newThread.threadId)

                    val ws = getOrCreateWs(agent.agentId, newThread.threadId)
                    if (ws.isConnected) {
                        ws.subscribe(newThread.threadId)
                    } else {
                        ws.connect(newThread.threadId)
                    }
                    loadThreadsForAgent(agent.agentId)
                    _toastMessage.value = "已创建新会话"
                },
                onFailure = { e ->
                    _toastMessage.value = "创建会话失败: ${e.localizedMessage ?: "网络错误"}"
                }
            )
        }
    }

    private fun loadThreadsForAgent(agentId: String) {
        viewModelScope.launch {
            val result = repository.getThreads(agentId)
            result.onSuccess { threads ->
                _agentThreadsMap.update { it + (agentId to threads) }
                if (_currentAgent.value?.agentId == agentId) {
                    val savedTid = _agentActiveThreadId[agentId] ?: _currentThread.value?.threadId
                    val threadInList = threads.find { it.threadId == savedTid }
                    if (threadInList != null) {
                        _currentThread.value = threadInList
                        _agentActiveThreadId[agentId] = threadInList.threadId
                        if (!_streamingThreads.contains(threadInList.threadId)) {
                            loadHistory(agentId, threadInList.threadId)
                        }
                    } else if (threads.isNotEmpty()) {
                        val activeThread = threads.firstOrNull { it.isActive } ?: threads.first()
                        _currentThread.value = activeThread
                        _agentActiveThreadId[agentId] = activeThread.threadId
                        val ws = getOrCreateWs(agentId, activeThread.threadId)
                        if (ws.isConnected) {
                            ws.subscribe(activeThread.threadId)
                        } else {
                            ws.connect(activeThread.threadId)
                        }
                        if (!_streamingThreads.contains(activeThread.threadId)) {
                            loadHistory(agentId, activeThread.threadId)
                        }
                    }
                }
            }
        }
    }

    private fun loadHistory(agentId: String, threadId: String) {
        viewModelScope.launch {
            try {
                val allRawPages = mutableListOf<List<HistoryMessageDto>>()
                var currentOffset = 0
                val pageSize = 200
                var hasMore = true
                var iterations = 0
                val maxIterations = 5
                var turnActiveOnServer = false

                while (hasMore && iterations < maxIterations) {
                    val respResult = repository.getThreadHistory(
                        agentId = agentId,
                        threadId = threadId,
                        limit = pageSize,
                        offset = currentOffset
                    )
                    val body = respResult.getOrNull() ?: break
                    if (iterations == 0) {
                        turnActiveOnServer = body.turn_active
                    }
                    val dtos = body.messages ?: body.items ?: emptyList()
                    if (dtos.isEmpty()) break

                    allRawPages.add(dtos)
                    hasMore = body.has_more
                    currentOffset += pageSize
                    iterations++
                }

                val allDtos = allRawPages.flatten()
                val serverMessages = processDialogueHistory(allDtos, agentId, threadId)
                val list = _threadMessagesCache.getOrPut(threadId) { mutableListOf() }
                val isStreamingNow = _streamingThreads.contains(threadId)

                synchronized(list) {
                    if (isStreamingNow) {
                        // 保留当前正在流式生成的本轮临时消息（用户消息与 AI 占位块），防止覆盖蒸发
                        val inFlightAi = list.lastOrNull { !it.isUser && it.isStreaming }
                        val inFlightUser = list.lastOrNull { it.isUser }

                        list.clear()
                        list.addAll(serverMessages)

                        // 仅与服务端历史中最后一条用户消息比对，防止历史中同名指令（如"继续"）被误判导致新消息被吞
                        if (inFlightUser != null && serverMessages.lastOrNull { it.isUser }?.content != inFlightUser.content) {
                            list.add(inFlightUser)
                        }
                        // 如果服务端尚未产生该轮的最终 AI 回复，保留/添加流式占位符
                        val agentModel = resolveActiveModelDisplayName(agentId, threadId)
                        if (list.isNotEmpty() && list.last().isUser) {
                            list.add(inFlightAi ?: ChatMessage(id = "ai_${UUID.randomUUID()}", isUser = false, isStreaming = true, isThinkingExpanded = true, modelName = agentModel))
                        }
                    } else if (turnActiveOnServer) {
                        // 云端检测到该会话仍在后台思考/执行，自动开启流式接力续接
                        _streamingThreads.add(threadId)
                        val ws = getOrCreateWs(agentId, threadId)
                        ws.subscribe(threadId)

                        val agentModel = resolveActiveModelDisplayName(agentId, threadId)
                        list.clear()
                        list.addAll(serverMessages)
                        if (list.isEmpty() || list.last().isUser) {
                            list.add(ChatMessage(id = "ai_${UUID.randomUUID()}", isUser = false, isStreaming = true, isThinkingExpanded = true, modelName = agentModel))
                        }
                    } else {
                        // 正常对齐云端快照，保守合并本地已生成的完整 AI 回复（避免 SQLite WAL 毫秒级异步落盘延迟导致新回复被擦除）
                        val localCompletedAi = list.lastOrNull { !it.isUser && it.content.isNotBlank() && !it.isStreaming }
                        val serverHasIt = localCompletedAi != null && serverMessages.any {
                            it.id == localCompletedAi.id || it.content.trim() == localCompletedAi.content.trim()
                        }
                        val reconciled = serverMessages.toMutableList()
                        if (localCompletedAi != null && !serverHasIt && reconciled.isNotEmpty() && reconciled.last().isUser) {
                            reconciled.add(localCompletedAi)
                        }
                        list.clear()
                        list.addAll(reconciled)
                    }
                    saveThreadToDisk(threadId, list.toList())
                }

                if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                    _messages.value = synchronized(list) { list.toList() }
                    _isStreaming.value = _streamingThreads.contains(threadId)
                }
            } catch (_: Exception) {}
        }
    }

    private fun processDialogueHistory(
        dtos: List<HistoryMessageDto>,
        agentId: String? = null,
        threadId: String? = null
    ): List<ChatMessage> {
        val dialogue = mutableListOf<ChatMessage>()
        val currentTurnAiMessages = mutableListOf<HistoryMessageDto>()
        var currentTurnUserDto: HistoryMessageDto? = null

        fun flushAssistantTurn() {
            if (currentTurnAiMessages.isEmpty()) return

            // 1. 从后往前找到最后一条有实质文本内容的消息作为该轮最终回复 (与 Web 端 splitAssistantTurn 保持严格一致)
            var answerDto: HistoryMessageDto? = null
            var answerText = ""
            var answerThinking = ""

            for (i in currentTurnAiMessages.indices.reversed()) {
                val dto = currentTurnAiMessages[i]
                val (text, th) = MessageParser.parse(dto.content, dto.thinking)
                if (text.isNotBlank()) {
                    answerDto = dto
                    answerText = text
                    answerThinking = th
                    break
                }
            }

            // 2. 统计整轮工具调用次数，并收集所有中间步骤的思考链
            val turnThinkingParts = mutableListOf<String>()
            var toolCallCount = 0
            for (dto in currentTurnAiMessages) {
                val role = (dto.role ?: "").lowercase()
                if (role == "tool") {
                    toolCallCount++
                }
                when (val c = dto.content) {
                    is List<*> -> {
                        for (block in c) {
                            if (block is Map<*, *>) {
                                val type = (block["type"] as? String)?.lowercase() ?: ""
                                if (type == "tool_use") toolCallCount++
                            }
                        }
                    }
                    else -> {}
                }

                val (_, th) = MessageParser.parse(dto.content, dto.thinking)
                if (th.isNotBlank() && !turnThinkingParts.contains(th)) {
                    turnThinkingParts.add(th)
                }
            }

            // 3. 决定最终展示的思考内容：
            val finalThinking = when {
                answerThinking.isNotBlank() && (turnThinkingParts.isEmpty() || turnThinkingParts.all { answerThinking.contains(it) }) ->
                    answerThinking
                turnThinkingParts.isNotEmpty() ->
                    turnThinkingParts.joinToString("\n\n---\n\n").trim()
                else ->
                    answerThinking
            }

            // 4. 精确解析当前轮次对应的实际模型名称 (优先该轮 AI 响应元数据 > 该轮用户发送的 composer_context > 会话/草稿绑定的 model_ref > 智能体兜底模型)
            val aid = agentId ?: _currentAgent.value?.agentId
            val tid = threadId ?: _currentThread.value?.threadId
            val key = if (aid != null) getConversationKey(aid, tid) else currentConversationKey()
            val threadItem = if (aid != null && tid != null) _agentThreadsMap.value[aid]?.find { it.threadId == tid } ?: _currentThread.value else _currentThread.value

            val userModelContext = (currentTurnUserDto?.composerContext?.get("model") as? String)
                ?: (currentTurnUserDto?.composerContext?.get("model_ref") as? String)

            val rawModel = currentTurnAiMessages.mapNotNull { it.modelName ?: it.model }
                .firstOrNull { it.isNotBlank() }
                ?: userModelContext
                ?: key?.let { _threadSelectedModel[it] }
                ?: threadItem?.modelRef
                ?: _agents.value.firstOrNull { it.agentId == aid }?.defaultModel
                ?: _currentAgent.value?.defaultModel
                ?: ""

            val provider = ResolvedModel.extractProvider(rawModel).ifBlank {
                ResolvedModel.extractProvider(userModelContext ?: "").ifBlank {
                    ResolvedModel.extractProvider(threadItem?.modelRef ?: "").ifBlank {
                        _availableModels.value.firstOrNull { it.modelRef == rawModel || it.model == rawModel }?.providerName
                    }
                }
            }
            val modelName = ResolvedModel.formatModelWithProvider(rawModel, provider)

            // 5. 发射该轮对应的唯一 AI 气泡
            if (answerDto != null && answerText.isNotBlank()) {
                val rawTs = if (answerDto.timestamp > 0) answerDto.timestamp else answerDto.created_at
                val ts = if (rawTs > 0) {
                    if (rawTs < 10_000_000_000L) rawTs * 1000L else rawTs
                } else {
                    System.currentTimeMillis()
                }
                val aiAttachments = MessageParser.parseAttachments(answerDto)

                dialogue.add(
                    ChatMessage(
                        id = answerDto.id ?: UUID.randomUUID().toString(),
                        isUser = false,
                        content = answerText,
                        thinking = finalThinking,
                        isThinkingExpanded = false,
                        toolCount = toolCallCount,
                        timestamp = ts,
                        attachments = aiAttachments,
                        modelName = modelName
                    )
                )
            } else if (finalThinking.isNotBlank() || toolCallCount > 0) {
                // 仅思考或工具调用阶段
                dialogue.add(
                    ChatMessage(
                        id = "thinking_${UUID.randomUUID()}",
                        isUser = false,
                        content = "",
                        thinking = finalThinking,
                        isThinkingExpanded = true,
                        toolCount = toolCallCount,
                        timestamp = System.currentTimeMillis(),
                        modelName = modelName
                    )
                )
            }

            currentTurnAiMessages.clear()
        }

        for (dto in dtos) {
            val role = (dto.role ?: "").lowercase()
            val isUser = role == "user" || role == "human"

            if (isUser) {
                // 结算前一轮智能体回复
                flushAssistantTurn()
                currentTurnUserDto = dto

                val (userText, _) = MessageParser.parse(dto.content, dto.thinking)
                val attachments = MessageParser.parseAttachments(dto)
                if (userText.isNotBlank() || attachments.isNotEmpty()) {
                    val rawTs = if (dto.timestamp > 0) dto.timestamp else dto.created_at
                    val ts = if (rawTs > 0) {
                        if (rawTs < 10_000_000_000L) rawTs * 1000L else rawTs
                    } else {
                        System.currentTimeMillis()
                    }
                    dialogue.add(
                        ChatMessage(
                            id = dto.id ?: UUID.randomUUID().toString(),
                            isUser = true,
                            content = if (userText.isNotBlank()) userText else "请查看并分析附件内容",
                            thinking = "",
                            isThinkingExpanded = false,
                            timestamp = ts,
                            attachments = attachments
                        )
                    )
                }
            } else if (role == "assistant" || role == "ai" || role == "tool") {
                currentTurnAiMessages.add(dto)
            }
        }

        // 结算最后一轮
        flushAssistantTurn()

        return dialogue
    }

    private fun getOrCreateWs(agentId: String, threadId: String): OctopWebSocketManager {
        val key = connKey(agentId, threadId)
        idleDisconnectJobs.remove(key)?.cancel()

        return wsManagers.getOrPut(key) {
            OctopWebSocketManager(
                agentId = agentId,
                initialThreadId = if (threadId.startsWith("draft_")) null else threadId,
                onToken = { tid, delta ->
                    handleWsToken(agentId, tid ?: threadId, delta)
                },
                onThinking = { tid, delta ->
                    handleWsThinking(agentId, tid ?: threadId, delta)
                },
                onToolCall = { tid, toolName ->
                    handleWsToolCall(agentId, tid ?: threadId, toolName)
                },
                onHitlRequired = { tid, hitlInfo ->
                    handleWsHitlRequired(agentId, tid ?: threadId, hitlInfo)
                },
                onDone = { tid ->
                    handleWsDone(agentId, tid ?: threadId)
                },
                onError = { tid, err ->
                    handleWsError(agentId, tid ?: threadId, err)
                },
                onConnectionChange = { connected ->
                    val currentTid = _currentThread.value?.threadId
                    val activeTid = _agentActiveThreadId[agentId]
                    val isCurrent = _currentAgent.value?.agentId == agentId &&
                        (currentTid == threadId || (threadId.startsWith("draft_") && (currentTid == null || currentTid == activeTid)))
                    if (isCurrent) {
                        _isConnected.value = connected
                    }
                },
                onThreadBound = { boundThreadId ->
                    handleWsThreadBound(agentId, boundThreadId)
                }
            ).also { it.connect(if (threadId.startsWith("draft_")) null else threadId) }
        }
    }

    private fun handleWsToolCall(agentId: String, threadId: String?, toolName: String) {
        val tid = threadId ?: _agentActiveThreadId[agentId] ?: return
        resetStreamingWatchdog(agentId, tid)
        val list = _threadMessagesCache.getOrPut(tid) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty() && !list.last().isUser) {
                val last = list.last()
                list[list.size - 1] = last.copy(toolCount = last.toolCount + 1, isStreaming = true)
            } else {
                list.add(ChatMessage(isUser = false, toolCount = 1, isStreaming = true, isThinkingExpanded = false))
            }
        }
        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
            _messages.value = synchronized(list) { list.toList() }
            _toastMessage.value = "正在调用工具: $toolName"
        }
    }

    private fun handleWsToken(agentId: String, threadId: String?, delta: String) {
        val tid = threadId ?: _agentActiveThreadId[agentId] ?: return
        resetStreamingWatchdog(agentId, tid)
        val list = _threadMessagesCache.getOrPut(tid) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty() && !list.last().isUser) {
                val last = list.last()
                list[list.size - 1] = last.copy(content = last.content + delta, isStreaming = true)
            } else {
                list.add(ChatMessage(isUser = false, content = delta, isStreaming = true, isThinkingExpanded = false, modelName = resolveActiveModelDisplayName(agentId, tid)))
            }
        }
        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
            _messages.value = synchronized(list) { list.toList() }
            _isStreaming.value = true
        }
    }

    private fun handleWsThinking(agentId: String, threadId: String?, delta: String) {
        val tid = threadId ?: _agentActiveThreadId[agentId] ?: return
        resetStreamingWatchdog(agentId, tid)
        val list = _threadMessagesCache.getOrPut(tid) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty() && !list.last().isUser) {
                val last = list.last()
                list[list.size - 1] = last.copy(thinking = last.thinking + delta, isStreaming = true, isThinkingExpanded = true)
            } else {
                list.add(ChatMessage(isUser = false, thinking = delta, isStreaming = true, isThinkingExpanded = true, modelName = resolveActiveModelDisplayName(agentId, tid)))
            }
        }
        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
            _messages.value = synchronized(list) { list.toList() }
            _isStreaming.value = true
        }
    }

    private fun handleWsHitlRequired(agentId: String, threadId: String, hitlInfo: HitlPendingInfo) {
        // 关键护栏：收到工具审批中断，暂停看门狗计时器并移除流式标记，防止用户思考等待超时且允许正常交互
        stopStreamingWatchdog(threadId)
        _streamingThreads.remove(threadId)
        val list = _threadMessagesCache.getOrPut(threadId) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty() && !list.last().isUser) {
                val last = list.last()
                list[list.size - 1] = last.copy(
                    hitlPending = hitlInfo,
                    isStreaming = false
                )
            } else {
                list.add(
                    ChatMessage(
                        id = "hitl_${UUID.randomUUID()}",
                        isUser = false,
                        hitlPending = hitlInfo,
                        isStreaming = false
                    )
                )
            }
        }
        saveThreadToDisk(threadId, synchronized(list) { list.toList() })

        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
            _messages.value = synchronized(list) { list.toList() }
            _isStreaming.value = false
            _toastMessage.value = "待人工审批操作"
        }
    }

    fun submitHitlDecision(agentId: String, threadId: String, approve: Boolean, rejectMessage: String? = null) {
        val list = _threadMessagesCache[threadId] ?: return
        synchronized(list) {
            val idx = list.indexOfLast { it.hitlPending != null && it.hitlResolved == null }
            if (idx >= 0) {
                val current = list[idx]
                list[idx] = current.copy(
                    hitlResolved = if (approve) "approved" else "rejected",
                    isStreaming = approve
                )
            }
        }
        saveThreadToDisk(threadId, synchronized(list) { list.toList() })

        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
            _messages.value = synchronized(list) { list.toList() }
        }

        val decision = if (approve) {
            HitlDecision(type = "approve")
        } else {
            HitlDecision(type = "reject", message = rejectMessage ?: "用户拒绝执行该操作")
        }

        if (approve) {
            _streamingThreads.add(threadId)
            if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                _isStreaming.value = true
            }
            resetStreamingWatchdog(agentId, threadId)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = ApiClient.api.resumeHitl(
                    agentId = agentId,
                    body = HitlResumeBody(thread_id = threadId, decisions = listOf(decision))
                )
                if (resp.isSuccessful && resp.body() != null) {
                    var doneHandled = false
                    resp.body()!!.charStream().buffered().use { source ->
                        var line: String? = source.readLine()
                        while (line != null) {
                            if (line.startsWith("data:")) {
                                val data = line.removePrefix("data:").trim()
                                if (data.isNotBlank() && data != "[DONE]") {
                                    try {
                                        val chunk = JsonParser.parseString(data).asJsonObject
                                        val type = chunk.get("type")?.asString ?: ""
                                        withContext(Dispatchers.Main) {
                                            when (type) {
                                                "token" -> handleWsToken(agentId, threadId, chunk.get("content")?.asString ?: "")
                                                "thinking", "reasoning" -> handleWsThinking(agentId, threadId, chunk.get("content")?.asString ?: "")
                                                "tool_call" -> handleWsToolCall(agentId, threadId, chunk.get("name")?.asString ?: "工具调用")
                                                "done" -> {
                                                    doneHandled = true
                                                    handleWsDone(agentId, threadId)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            line = source.readLine()
                        }
                    }
                    if (!doneHandled) {
                        withContext(Dispatchers.Main) {
                            handleWsDone(agentId, threadId)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        stopStreamingWatchdog(threadId)
                        _streamingThreads.remove(threadId)
                        if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                            _isStreaming.value = false
                            _toastMessage.value = "审批恢复失败: HTTP ${resp.code()}"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopStreamingWatchdog(threadId)
                    _streamingThreads.remove(threadId)
                    if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                        _isStreaming.value = false
                        _toastMessage.value = "恢复执行异常: ${e.localizedMessage ?: "网络异常"}"
                    }
                }
            }
        }
    }

    private fun handleWsDone(agentId: String, threadId: String?) {
        val tid = threadId ?: return
        stopStreamingWatchdog(tid)
        _streamingThreads.remove(tid)
        val list = _threadMessagesCache[tid]
        val fallbackModel = resolveActiveModelDisplayName(agentId, tid)
        if (list != null) {
            synchronized(list) {
                if (list.isNotEmpty() && !list.last().isUser) {
                    val last = list.last()
                    list[list.size - 1] = last.copy(
                        isStreaming = false,
                        isThinkingExpanded = false,
                        modelName = if (!last.modelName.isNullOrBlank()) ResolvedModel.formatModelName(last.modelName) else fallbackModel
                    )
                }
            }
            saveThreadToDisk(tid, synchronized(list) { list.toList() })
        }
        val isCurrent = _currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid
        if (isCurrent) {
            _messages.value = list?.let { synchronized(it) { it.toList() } } ?: emptyList()
            _isStreaming.value = false
        } else {
            // 后台会话生成完毕，调度 60 秒后自动空闲断连
            scheduleIdleDisconnect(connKey(agentId, tid))
        }

        // 自动调度当前会话排队消息的下一轮发送 (对齐 Antigravity 队列接力)
        checkAndDispatchNextQueuedMessage(agentId, tid)
    }

    private fun handleWsError(agentId: String, threadId: String?, err: String) {
        val tid = threadId ?: _agentActiveThreadId[agentId]
        if (tid != null) {
            stopStreamingWatchdog(tid)
            _streamingThreads.remove(tid)
            if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
                _isStreaming.value = false
                if (!err.contains("not found")) {
                    _toastMessage.value = err
                }
            }
        }
    }

    private fun handleWsThreadBound(agentId: String, boundThreadId: String) {
        _agentActiveThreadId[agentId] = boundThreadId
        val draftKey = "draft_$agentId"
        if (_streamingThreads.contains(draftKey)) {
            _streamingThreads.remove(draftKey)
            _streamingThreads.add(boundThreadId)
        }
        val draftMessages = _threadMessagesCache.remove(draftKey)
        if (draftMessages != null) {
            val list = _threadMessagesCache.getOrPut(boundThreadId) { mutableListOf() }
            synchronized(list) {
                list.addAll(draftMessages)
            }
            saveThreadToDisk(boundThreadId, synchronized(list) { list.toList() })
        }

        // 迁移排队消息队列
        val draftQueue = _conversationQueues.remove(draftKey)
        if (draftQueue != null) {
            val boundQ = _conversationQueues.getOrPut(boundThreadId) { mutableListOf() }
            synchronized(boundQ) {
                boundQ.addAll(draftQueue)
            }
            updateCurrentQueuedMessages()
        }

        // 迁移并绑定 WebSocket 连接键
        val draftConnKey = connKey(agentId, draftKey)
        val boundConnKey = connKey(agentId, boundThreadId)
        val oldWs = wsManagers.remove(draftConnKey)
        if (oldWs != null) {
            wsManagers[boundConnKey] = oldWs
        }

        // 迁移草稿会话选定的模型与思考强度
        val oldKey = "$agentId:draft_$agentId"
        val newKey = "$agentId:$boundThreadId"
        _threadSelectedModel.remove(oldKey)?.let { _threadSelectedModel[newKey] = it }
        _threadSelectedEffort.remove(oldKey)?.let { _threadSelectedEffort[newKey] = it }

        // 严格防窜门：只有在当前用户处于无会话或草稿新建状态时，才绑定到新创建的 Thread，切勿误伤覆盖用户已主动切换的其他活跃 Thread
        if (_currentAgent.value?.agentId == agentId) {
            val currentTid = _currentThread.value?.threadId
            if (currentTid == null || currentTid.startsWith("draft_")) {
                _currentThread.value = ThreadItem(
                    threadId = boundThreadId,
                    agentId = agentId,
                    title = "当前会话",
                    isActive = true
                )
                loadThreadsForAgent(agentId)
            }
        }
    }

    private fun startStreamingTimeoutWatchdog(agentId: String, threadId: String) {
        watchdogJobs[threadId]?.cancel()
        watchdogJobs[threadId] = viewModelScope.launch {
            delay(60_000L) // 60秒无任何响应兜底超时
            if (_streamingThreads.contains(threadId)) {
                _streamingThreads.remove(threadId)
                if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                    _isStreaming.value = false
                    _toastMessage.value = "服务器响应超时，已停止等待"
                }
                val list = _threadMessagesCache[threadId]
                if (list != null) {
                    synchronized(list) {
                        if (list.isNotEmpty() && !list.last().isUser) {
                            val last = list.last()
                            if (last.content.isEmpty() && last.thinking.isEmpty()) {
                                list[list.size - 1] = last.copy(
                                    content = "⚠️ 服务器响应超时，请重试或检查服务状态。",
                                    isStreaming = false,
                                    isThinkingExpanded = false
                                )
                            } else {
                                list[list.size - 1] = last.copy(isStreaming = false)
                            }
                        }
                    }
                    saveThreadToDisk(threadId, synchronized(list) { list.toList() })
                    if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                        _messages.value = synchronized(list) { list.toList() }
                    }
                }
            }
        }
    }

    private fun resetStreamingWatchdog(agentId: String, threadId: String) {
        watchdogJobs[threadId]?.cancel()
        watchdogJobs[threadId] = viewModelScope.launch {
            delay(45_000L) // 流式传输中单块 token 间隔超过 45 秒则超时
            if (_streamingThreads.contains(threadId)) {
                _streamingThreads.remove(threadId)
                if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                    _isStreaming.value = false
                    _toastMessage.value = "连接中断，生成已停止"
                }
                val list = _threadMessagesCache[threadId]
                if (list != null) {
                    synchronized(list) {
                        if (list.isNotEmpty() && !list.last().isUser) {
                            val last = list.last()
                            list[list.size - 1] = last.copy(isStreaming = false)
                        }
                    }
                    saveThreadToDisk(threadId, synchronized(list) { list.toList() })
                    if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
                        _messages.value = synchronized(list) { list.toList() }
                    }
                }
            }
        }
    }

    private fun stopStreamingWatchdog(threadId: String) {
        watchdogJobs[threadId]?.cancel()
        watchdogJobs.remove(threadId)
    }

    fun pickAttachment(context: Context, uri: Uri, isImage: Boolean) {
        pickAttachments(context, listOf(uri), isImage)
    }

    fun pickAttachments(context: Context, uris: List<Uri>, isImage: Boolean) {
        val agent = _currentAgent.value ?: return
        val agentId = agent.agentId
        if (uris.isEmpty()) return

        val convKey = currentConversationKey() ?: "$agentId:draft_$agentId"
        val newItems = mutableListOf<AttachmentItem>()
        val currentTime = System.currentTimeMillis()

        for ((idx, uri) in uris.withIndex()) {
            var filename = if (isImage) "image_${currentTime}_$idx.jpg" else "file_${currentTime}_$idx"
            var size: Long = 0
            var mimeType = context.contentResolver.getType(uri) ?: if (isImage) "image/jpeg" else "application/octet-stream"

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) cursor.getString(nameIdx)?.let { if (it.isNotBlank()) filename = it }
                        if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}

            if (size > 50 * 1024 * 1024) {
                _toastMessage.value = "文件 $filename 超过 50MB 上传限制"
                continue
            }

            newItems.add(
                AttachmentItem(
                    id = UUID.randomUUID().toString(),
                    localUriString = uri.toString(),
                    filename = filename,
                    mimeType = mimeType,
                    size = size,
                    isImage = isImage,
                    status = AttachmentStatus.PENDING,
                    uploadedAgentId = agentId,
                    targetConversationKey = convKey
                )
            )
        }

        if (newItems.isEmpty()) return

        val currentList = _conversationStagedAttachments[convKey] ?: emptyList()
        val updated = currentList + newItems
        _conversationStagedAttachments[convKey] = updated
        if (currentConversationKey() == convKey) {
            _stagedAttachments.value = updated
        }

        for (item in newItems) {
            executeUpload(context, item, agentId)
        }
    }

    private fun executeUpload(context: Context, item: AttachmentItem, agentId: String) {
        updateAttachmentStatus(item.id, item.targetConversationKey, AttachmentStatus.UPLOADING)

        viewModelScope.launch {
            val result = AttachmentUploadPipeline.upload(context, agentId, item)
            result.fold(
                onSuccess = { body ->
                    updateAttachmentSuccess(
                        id = item.id,
                        convKey = item.targetConversationKey,
                        workspacePath = body.workspacePath,
                        previewUrl = body.url,
                        filename = body.filename,
                        mimeType = body.mediaType
                    )
                },
                onFailure = { error ->
                    updateAttachmentStatus(
                        item.id,
                        item.targetConversationKey,
                        AttachmentStatus.ERROR,
                        error.localizedMessage ?: "上传失败"
                    )
                }
            )
        }
    }

    private fun updateAttachmentStatus(id: String, convKey: String?, status: AttachmentStatus, error: String? = null) {
        val targetKey = convKey ?: currentConversationKey() ?: return
        val list = _conversationStagedAttachments[targetKey] ?: emptyList()
        val updated = list.map {
            if (it.id == id) it.copy(status = status, errorMessage = error) else it
        }
        _conversationStagedAttachments[targetKey] = updated
        if (currentConversationKey() == targetKey) {
            _stagedAttachments.value = updated
        }
    }

    private fun updateAttachmentSuccess(
        id: String,
        convKey: String?,
        workspacePath: String,
        previewUrl: String,
        filename: String,
        mimeType: String
    ) {
        val targetKey = convKey ?: currentConversationKey() ?: return
        val list = _conversationStagedAttachments[targetKey] ?: emptyList()
        val updated = list.map {
            if (it.id == id) {
                it.copy(
                    status = AttachmentStatus.SUCCESS,
                    workspacePath = workspacePath,
                    previewUrl = previewUrl,
                    filename = filename,
                    mimeType = mimeType,
                    errorMessage = null
                )
            } else it
        }
        _conversationStagedAttachments[targetKey] = updated
        if (currentConversationKey() == targetKey) {
            _stagedAttachments.value = updated
        }
    }

    fun removeStagedAttachment(id: String) {
        val targetKey = currentConversationKey() ?: return
        val list = _conversationStagedAttachments[targetKey] ?: emptyList()
        val updated = list.filter { it.id != id }
        _conversationStagedAttachments[targetKey] = updated
        _stagedAttachments.value = updated
    }

    fun retryUpload(context: Context, id: String) {
        val item = _stagedAttachments.value.find { it.id == id } ?: return
        val agent = _currentAgent.value ?: return
        executeUpload(context, item, agent.agentId)
    }

    fun downloadAndOpenFile(context: Context, agentId: String, attachment: AttachmentItem) {
        val path = attachment.workspacePath ?: return
        val filename = attachment.filename.ifBlank { path.substringAfterLast('/') }
        val downloadUrl = NetworkConfig.getFileDownloadUrl(agentId, path)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _toastMessage.value = "正在下载 $filename..."
                }

                val request = okhttp3.Request.Builder()
                    .url(downloadUrl)
                    .header("X-Octop-App-Key", NetworkConfig.appKey)
                    .apply {
                        NetworkConfig.authToken?.let {
                            if (it.isNotBlank()) header("Authorization", "Bearer $it")
                        }
                    }
                    .build()

                val response = ApiClient.okHttpClient.newCall(request).execute()
                if (!response.isSuccessful || response.body == null) {
                    withContext(Dispatchers.Main) {
                        _toastMessage.value = "文件下载失败: HTTP ${response.code}"
                    }
                    return@launch
                }

                val downloadDir = File(context.cacheDir, "downloads").apply { if (!exists()) mkdirs() }
                val targetFile = File(downloadDir, filename)
                response.body!!.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile
                )

                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, attachment.mimeType.ifBlank { "*/*" })
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                withContext(Dispatchers.Main) {
                    try {
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        _toastMessage.value = "未找到可打开此文件的应用: $filename"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _toastMessage.value = "打开文件失败: ${e.localizedMessage ?: "网络异常"}"
                }
            }
        }
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        val agent = _currentAgent.value ?: return
        val currentTid = _currentThread.value?.threadId
            ?: _agentActiveThreadId[agent.agentId]
            ?: "draft_${agent.agentId}"

        val currentStaged = _stagedAttachments.value
        val isBusy = currentStaged.any { it.status == AttachmentStatus.PENDING || it.status == AttachmentStatus.UPLOADING }
        if (isBusy) {
            _toastMessage.value = "附件正在上传中，请稍候..."
            return
        }

        val successfulAttachments = currentStaged.filter { it.status == AttachmentStatus.SUCCESS }
        if (text.isEmpty() && successfulAttachments.isEmpty()) return

        val promptText = if (text.isBlank() && successfulAttachments.isNotEmpty()) "请查看并分析附件内容" else text

        // 如果当前会话正在流式生成，进入排队逻辑 (对齐 Antigravity: Queued Messages)
        if (_streamingThreads.contains(currentTid)) {
            val queuedMsg = QueuedMessage(
                text = promptText,
                attachments = successfulAttachments
            )
            val qList = _conversationQueues.getOrPut(currentTid) { mutableListOf() }
            synchronized(qList) {
                qList.add(queuedMsg)
            }
            updateCurrentQueuedMessages()

            // 清理当前会话的草稿文字与待发附件，清空输入框
            currentConversationKey()?.let { key ->
                _conversationDrafts.remove(key)
                _conversationStagedAttachments.remove(key)
            }
            _inputText.value = ""
            _stagedAttachments.value = emptyList()
            return
        }

        executeSendTurn(agent, currentTid, promptText, successfulAttachments)
    }

    private fun executeSendTurn(
        agent: AgentItem,
        currentTid: String,
        promptText: String,
        successfulAttachments: List<AttachmentItem>
    ) {
        val ws = getOrCreateWs(agent.agentId, currentTid)
        val targetTid = if (currentTid.startsWith("draft_")) null else currentTid
        if (!ws.isConnected) {
            _toastMessage.value = "正在连接服务，请稍候重试..."
            ws.connect(targetTid)
            return
        }

        val activeModel = _currentSelectedModel.value.ifBlank { agent.defaultModel }
        val activeEffort = _currentSelectedEffort.value

        val sent = ws.sendUserTurn(
            text = promptText,
            threadId = targetTid,
            attachments = successfulAttachments,
            model = activeModel,
            reasoningEffort = activeEffort
        )
        if (!sent) {
            _toastMessage.value = "发送失败，正在重新连接..."
            ws.reconnect()
            return
        }

        _agentActiveThreadId[agent.agentId] = currentTid

        // 发送成功，本地立即安全存储并显示
        val userMsg = ChatMessage(
            id = "user_${UUID.randomUUID()}",
            isUser = true,
            content = promptText,
            attachments = successfulAttachments,
            timestamp = System.currentTimeMillis()
        )
        val displayModel = resolveActiveModelDisplayName(agent.agentId, currentTid)
        val aiPlaceholder = ChatMessage(
            id = "ai_${UUID.randomUUID()}",
            isUser = false,
            isStreaming = true,
            isThinkingExpanded = true,
            modelName = displayModel,
            timestamp = System.currentTimeMillis()
        )

        // 清理当前会话的草稿文字与待发附件
        currentConversationKey()?.let { key ->
            _conversationDrafts.remove(key)
            _conversationStagedAttachments.remove(key)
        }
        _inputText.value = ""
        _stagedAttachments.value = emptyList()
        _streamingThreads.add(currentTid)
        _isStreaming.value = true

        val list = _threadMessagesCache.getOrPut(currentTid) { mutableListOf() }
        synchronized(list) {
            list.add(userMsg)
            list.add(aiPlaceholder)
        }
        saveThreadToDisk(currentTid, synchronized(list) { list.toList() })
        if (_currentAgent.value?.agentId == agent.agentId && _currentThread.value?.threadId == currentTid) {
            _messages.value = synchronized(list) { list.toList() }
        }

        startStreamingTimeoutWatchdog(agent.agentId, currentTid)
    }

    private fun checkAndDispatchNextQueuedMessage(agentId: String, tid: String) {
        val qList = _conversationQueues[tid] ?: return
        val nextMsg = synchronized(qList) {
            if (qList.isNotEmpty()) qList.removeAt(0) else null
        } ?: return

        viewModelScope.launch {
            delay(350L) // 留出短暂过渡时间，让上一轮生成的收尾状态平稳渲染
            updateCurrentQueuedMessages()
            val agent = _agents.value.firstOrNull { it.agentId == agentId } ?: _currentAgent.value
            if (agent != null) {
                executeSendTurn(agent, tid, nextMsg.text, nextMsg.attachments)
            }
        }
    }

    fun removeQueuedMessage(id: String) {
        val aid = _currentAgent.value?.agentId
        val tid = _currentThread.value?.threadId ?: aid?.let { _agentActiveThreadId[it] } ?: "draft_$aid"
        val qList = _conversationQueues[tid] ?: return
        synchronized(qList) {
            qList.removeAll { it.id == id }
        }
        updateCurrentQueuedMessages()
    }

    fun editQueuedMessage(id: String) {
        val aid = _currentAgent.value?.agentId
        val tid = _currentThread.value?.threadId ?: aid?.let { _agentActiveThreadId[it] } ?: "draft_$aid"
        val qList = _conversationQueues[tid] ?: return
        val msg = synchronized(qList) {
            val found = qList.firstOrNull { it.id == id }
            qList.removeAll { it.id == id }
            found
        } ?: return
        updateCurrentQueuedMessages()

        _inputText.value = msg.text
        _stagedAttachments.value = msg.attachments
    }

    fun sendQueuedNow(id: String) {
        val agent = _currentAgent.value ?: return
        val currentTid = _currentThread.value?.threadId
            ?: _agentActiveThreadId[agent.agentId]
            ?: "draft_${agent.agentId}"
        val qList = _conversationQueues[currentTid] ?: return
        val msg = synchronized(qList) {
            val found = qList.firstOrNull { it.id == id }
            qList.removeAll { it.id == id }
            found
        } ?: return
        updateCurrentQueuedMessages()

        // 若当前正在流式生成，先中断当前轮次，立即发送所选排队消息
        if (_streamingThreads.contains(currentTid)) {
            stopStreamingWatchdog(currentTid)
            _streamingThreads.remove(currentTid)
            val ws = wsManagers[connKey(agent.agentId, currentTid)]
            ws?.cancelTurn(currentTid)
        }

        executeSendTurn(agent, currentTid, msg.text, msg.attachments)
    }

    fun stopStreaming() {
        val agent = _currentAgent.value ?: return
        val tid = _currentThread.value?.threadId ?: _agentActiveThreadId[agent.agentId] ?: return

        stopStreamingWatchdog(tid)
        _streamingThreads.remove(tid)
        _isStreaming.value = false

        val ws = wsManagers[connKey(agent.agentId, tid)]
        ws?.cancelTurn(tid)

        val list = _threadMessagesCache[tid]
        if (list != null) {
            synchronized(list) {
                if (list.isNotEmpty() && !list.last().isUser) {
                    val last = list.last()
                    list[list.size - 1] = last.copy(isStreaming = false, isThinkingExpanded = false)
                }
            }
            saveThreadToDisk(tid, synchronized(list) { list.toList() })
            if (_currentAgent.value?.agentId == agent.agentId && _currentThread.value?.threadId == tid) {
                _messages.value = synchronized(list) { list.toList() }
            }
        }
        _toastMessage.value = "已停止生成"
    }

    fun setInputText(text: String) {
        _inputText.value = text
        currentConversationKey()?.let { key ->
            if (text.isNotEmpty()) {
                _conversationDrafts[key] = text
            } else {
                _conversationDrafts.remove(key)
            }
        }
    }

    fun setFullscreenInput(visible: Boolean) {
        _isFullscreenInput.value = visible
    }

    fun toggleThinking(messageId: String) {
        val tid = _currentThread.value?.threadId
            ?: _currentAgent.value?.agentId?.let { _agentActiveThreadId[it] }
        if (tid != null) {
            val list = _threadMessagesCache[tid]
            if (list != null) {
                synchronized(list) {
                    val idx = list.indexOfFirst { it.id == messageId }
                    if (idx >= 0) {
                        val old = list[idx]
                        list[idx] = old.copy(isThinkingExpanded = !old.isThinkingExpanded)
                    }
                }
            }
        }
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) msg.copy(isThinkingExpanded = !msg.isThinkingExpanded) else msg
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        watchdogJobs.values.forEach { it.cancel() }
        watchdogJobs.clear()
        idleDisconnectJobs.values.forEach { it.cancel() }
        idleDisconnectJobs.clear()
        wsManagers.values.forEach { it.disconnect() }
        wsManagers.clear()
    }

    companion object {
        val defaultExperts = listOf(
            AgentItem(agentId = "main", name = "小通 · 通用助手", description = "通用敏捷日常助理"),
            AgentItem(agentId = "KGZ2RP", name = "育儿管家", description = "成长教育与日程打理"),
            AgentItem(agentId = "AZYKMJ", name = "热点记者", description = "全球科技商业头条洞察"),
            AgentItem(agentId = "70P5RA", name = "我的持仓专家", description = "证券量化与资产配置参谋"),
            AgentItem(agentId = "5K92B4", name = "三轨参谋 · 伴行者", description = "工作/生活/健康第二大脑"),
            AgentItem(agentId = "WTNWE4", name = "运维工程师 Ops", description = "Linux/云服务器排障专家"),
            AgentItem(agentId = "EY93GH", name = "史官", description = "生活随笔与灵感记忆归档"),
            AgentItem(agentId = "ZBJ5S3", name = "家庭医生", description = "化验单解读与健康长期追踪")
        )
    }
}
