# Issue 007: 切换专家或会话时导致正在生成的任务丢失、新专家发消息无响应

- **问题编号**：ISSUE-007
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `ChatViewModel.kt`:
    - 废弃全局单一的 `wsManager`，构建专家级长连接池 `wsManagers = ConcurrentHashMap<String, OctopWebSocketManager>()`，切换专家时保持后台连接常驻接收；
    - 废弃全局单一的 `_messages`，构建线程级缓存 `_threadMessagesCache = ConcurrentHashMap<String, MutableList<ChatMessage>>()`，实现多会话秒切与状态持久；
    - 废弃全局单一的 `_isStreaming` 布尔标志，改为多线程并发状态追踪 `_streamingThreads = Collections.synchronizedSet(mutableSetOf<String>())`，UI 状态仅与当前展示会话动态联动，杜绝跨会话误伤拦截；
    - `loadHistory` 实现智能增量合并保护：在云端历史拉取完成后，保留本地正在流式接收的尾部消息，并根据云端 `turn_active` 状态自动续接订阅，彻底消除消息覆盖蒸发现象；
    - 独立分发各个线程的 60 秒全局兜底与 45 秒 Token 心跳 Watchdog 任务。
  - `OctopWebSocketManager.kt`:
    - 所有下行帧回调（`onToken`, `onThinking`, `onDone`, `onError`, `onToolCall`）升级为附带精准 `threadId`，确保后台接收到的数据准确归属到对应会话。
  - `Message.kt`:
    - `HistoryResponse` DTO 增加服务端 `turn_active: Boolean = false` 解析支持。
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.6` (versionCode = 7)。
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

用户在客户端进行多专家或多会话并行交互时，遇到以下连环故障：
1. **新专家发消息完全无响应**：用户在“热点专家”发出一条长文本任务，模型正在处理中；用户此时通过侧边栏抽屉切换到“育儿专家”想发另一条消息，发现“育儿专家”没有任何反应，无法发送；
2. **切回原专家后消息全空蒸发**：用户从“育儿专家”再次切回“热点专家”，发现刚才在“热点专家”发送的消息和 AI 生成中的回复全都不见了；
3. **但服务端实际已经执行**：例如用户发的那条消息是让大模型修改任务名称或配置，在系统后台/网页端看到名称最后确实已经成功改动，但在 Android 客户端界面上却看不见这条交互历史。

---

## 二、 原因分析 (Root Cause Analysis)

### 1. 全局单一的 `_isStreaming` 导致跨专家被连锁死锁
- 原代码中，流式生成状态被设计为一个全局单一的 `_isStreaming = MutableStateFlow(false)`；
- 当“热点专家”在流式生成中，`_isStreaming` 被设为 `true`；
- 当用户切换到“育儿专家”时，`_isStreaming` **并未重置**；
- 此时用户在“育儿专家”输入内容点击发送：
  ```kotlin
  fun sendMessage() {
      if (text.isEmpty() || _isStreaming.value) return // 直接被全局锁拦截！
  }
  ```
  同时，输入栏右侧的发送按钮因为 `isStreaming == true` 被动态替换成了停止方块（■）。用户误以为是“育儿专家卡死了没有响应”，实际是代码将另一个专家的流式锁应用到了当前专家。

### 2. 全局单一的 `wsManager` 在切换专家时暴力断开后台长连接
- 原代码中仅持有一个 `wsManager: OctopWebSocketManager?`；
- 每次调用 `selectAgent` 时，直接触发 `connectWs(agent.agentId, null)`，其内部第一行就是：
  ```kotlin
  wsManager?.disconnect()
  ```
- 这直接导致“热点专家”正在握手和流式下发的 WebSocket 连接被手机端强行掐断！
- 客户端与云端脱钩后，后续所有的思考增量、Token 增量和完成帧再也无法抵达客户端。

### 3. 本地无会话消息隔离缓存，全量覆盖冲毁在途消息
- 原代码中只有一组全局的 `_messages` StateFlow：
  - 切换离开“热点专家”时，直接执行了 `_messages.value = emptyList()`，手机内存中的发送内容和思考卡片瞬间被清空；
  - 切换回到“热点专家”时，触发 `loadHistory`；
  - 由于大模型还在执行中或长连接断开导致该轮尚未完全写入云端数据库的 `thread_messages` 归档表，云端 HTTP `getThreadHistory` 返回的仅是上一轮的历史记录；
  - `loadHistory` 拿到云端历史后，直接 `_messages.value = parsedMessages` 覆盖了整个列表；
  - 本地由于没有做未提交消息保护，刚才发的消息彻底丢失。

---

## 三、 解决方案 (Fix & Implementation)

### 1. 专家级长连接池（Multi-Agent WebSocket Pool）
- 在 `ChatViewModel` 中引入 `ConcurrentHashMap<String, OctopWebSocketManager>`；
- 切换专家时不再断开旧专家的连接，所有专家的 WebSocket 保持独立驻留；
- `OctopWebSocketManager` 的所有事件分发均携带 `threadId`，使得后台即便切走了界面，数据依然能精准路由。

### 2. 会话级本地消息缓存（Per-Thread Message Cache）
- 引入 `_threadMessagesCache = ConcurrentHashMap<String, MutableList<ChatMessage>>()`；
- 所有在途消息、用户发送回显、模型流式 Token 优先且实时沉淀在目标 `threadId` 的本地列表中；
- 切换会话时直接从本地缓存秒开渲染，无需经历网络白屏等待；
- 切换走再切换回来，后台已接收完成的消息完整保留在屏幕上。

### 3. 会话独立流式状态控制（Per-Thread Streaming State）
- 使用 `_streamingThreads: Set<String>` 记录各线程的独立流式状态；
- `_isStreaming` 仅向 UI 反映**当前正在查看的会话**是否在生成；
- “热点专家”在后台生成，“育儿专家”在前台立即可用、独立发送，支持真正的多专家多任务并行互动。

### 4. `loadHistory` 智能增量合并与流式接力保护
- 当 `loadHistory` 异步拉取云端记录返回时：
  - 若检测到该会话本地正处于流式生成中，保留本地缓存尾部的临时在途消息，并仅前置合并历史，严禁覆盖在途轮次；
  - 若云端返回 `turn_active == true`（说明用户刷新了应用或从外部切回，而服务端仍未跑完），自动标记流式并触发 WebSocket `subscribe` 接力续听，保障最终一致性。

---

## 四、 验证与效果 (Verification)

1. 在“热点专家”中发送长耗时任务，大模型进入深度思考与工具调用；
2. 立即从侧边栏切换至“育儿专家”，输入栏恢复正常发送状态，发送消息后“育儿专家”立即响应；
3. 再次切回“热点专家”，刚才的任务没有丢失，界面直接呈现已经思考完成（或正在继续生成中）的完整答复与定时任务修改反馈。
