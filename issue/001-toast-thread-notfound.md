# Issue 001: 切换专家或新建会话时弹出 Toast "thread 'xxxx' not found"

- **问题编号**：ISSUE-001
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：Android 客户端 (`ChatViewModel.kt`, `OctopWebSocketManager.kt`, `OctopApiService.kt`, `Thread.kt`)
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

在 Android 客户端通过新加坡中转服务器成功登录 Octop 后：
1. 正常与首个默认专家（如 `main`）完成对话交流；
2. 点击左上角抽屉按钮（`☰`），在 8 大专家矩阵中点击切换至另一个专家（如 `ZBJ5S3` 家庭医生、`EY93GH` 史官、`WTNWE4` 运维工程师）；
3. 界面底端立刻弹出 Toast 错误提示：
   ```
   thread 'thr_01M4X2M5SVAQRPEZRB3QBZ37C2' not found
   ```
4. 随后在右上角菜单中点击「新建会话」时，偶尔也会弹出虚假 ID 的类似 `not found` 报错，无法正常与新专家展开全新会话。

---

## 二、 原因分析 (Root Cause Analysis)

通过深入审查 Octop 服务端源码（`octop/api/routers/chat/ws.py`、`octop/api/routers/chat/turn.py`、`octop/api/routers/chat/history.py`）以及中转 Nginx 访问日志，定位到两个直接根因：

### 1. 跨专家切换时的 Thread 状态残留与越界订阅（核心原因）

- **客户端时序缺陷**：在 `ChatViewModel.kt` 的原始代码中：
  ```kotlin
  fun selectAgent(agent: AgentItem) {
      _currentAgent.value = agent
      loadThreadsForAgent(agent.agentId)
      connectWs(agent.agentId, _currentThread.value?.threadId) // 隐患：此时 _currentThread 仍是上一个专家的会话 ID
  }
  ```
  当用户从 Agent A（如 `main`，会话 ID 为 `thr_01M4X2M5SVAQRPEZRB3QBZ37C2`）切换到 Agent B（如 `ZBJ5S3`）时，`_currentThread.value` 未被重置。
- **WebSocket 越界订阅**：`connectWs` 以新专家 `ZBJ5S3` 的 WebSocket URL 握手建立连接，并在 `onOpen` 回调中立刻向服务端发送了订阅帧：
  ```json
  {"type": "subscribe", "thread_id": "thr_01M4X2M5SVAQRPEZRB3QBZ37C2"}
  ```
- **服务端强校验拦截**：Octop 后端 `ws.py` 严格校验会话的归属权：
  ```python
  # octop/api/routers/chat/ws.py
  row = gateway.thread_registry.get_thread(thread_id)
  if row is None or row.agent_id != agent_id or row.user_id != user.id:
      await send_frame(
          {"type": "error", "message": f"thread {thread_id!r} not found"},
      )
  ```
  由于数据库中该 `thread_id` 的 `agent_id` 为 `main`，与当前 WebSocket 所属的 `ZBJ5S3` 不匹配（`row.agent_id != agent_id` 判定为 True），服务端判定为非法跨 Agent 会话访问，直接下发 `{"type": "error", "message": "thread 'thr_...' not found"}`。客户端收到该错误帧后触发 `onError` 回调并弹出了 Toast。

### 2. 新建会话 API 协议契约不一致与本地伪造 ID 兜底

- **接口定义不一致**：Octop 后端 `POST /api/agents/{agent_id}/threads` 是一个无请求体（No Body）的接口，服务端处理后只返回 `{ "thread_id": str, "session_key": str }`。
- **客户端错误声明 Body**：客户端 `OctopApiService.kt` 原先将该接口声明为了带有 `@Body body: CreateThreadBody`，且将返回类型定义为包含 `title`、`agent_id` 的 `ThreadItem`。
- **异常捕获后生成了伪造 ID**：一旦网络波动或反序列化异常触发 catch 块，代码执行了本地降级：
  ```kotlin
  val localThread = ThreadItem(
      threadId = "thr_" + UUID.randomUUID().toString().take(8), // 本地虚构 UUID
      title = "新会话",
      agentId = agent.agentId
  )
  connectWs(agent.agentId, localThread.threadId)
  ```
  客户端拿着本地伪造的 `thr_xxxxxxxx` 向服务端 WebSocket 发送 `subscribe`，服务端在 SQLite 中查无此记录（`row is None`），再次触发 `thread 'thr_xxxxxxxx' not found`。

---

## 三、 解决方案 (Fix & Implementation)

针对上述问题，进行了系统性重构与修复：

### 1. 重构专家切换与状态解绑逻辑 (`ChatViewModel.kt`)

- 切换专家时，**立即置空旧会话**并清空消息流，初次握手时一律传 `threadId = null`，杜绝旧 Agent 的 Thread ID 污染新 Agent 的长连接。
- 异步加载该专家的真实会话列表。若服务端有历史会话，优先自动切入带 `is_active` 标记的活跃会话；若无历史会话，则保持干净的等待首句输入态。
- 关键代码改动：
  ```kotlin
  fun selectAgent(agent: AgentItem) {
      if (_currentAgent.value?.agentId == agent.agentId) return
      _currentAgent.value = agent
      _currentThread.value = null // 1. 立即解绑
      _messages.value = emptyList() // 2. 清空聊天流
      connectWs(agent.agentId, null) // 3. 安全连接（不携带脏 threadId）
      loadThreadsForAgent(agent.agentId) // 4. 异步加载新专家的真实历史
  }
  ```

### 2. 规范化新建会话 REST 契约 (`OctopApiService.kt` & `Thread.kt`)

- 新增专用的 `CreateThreadResponse` 数据模型，精准映射服务端的响应格式：
  ```kotlin
  data class CreateThreadResponse(
      @SerializedName("thread_id") val threadId: String,
      @SerializedName("session_key") val sessionKey: String? = null
  )
  ```
- 移除 `@Body` 请求体声明：
  ```kotlin
  @POST("api/agents/{agent_id}/threads")
  suspend fun createThread(@Path("agent_id") agentId: String): Response<CreateThreadResponse>
  ```
- 废弃本地生成假 ID（`thr_UUID`）的兜底逻辑。新建会话成功后直接使用服务端分配的真实 `thread_id`；若服务端返回错误，真实反馈给用户。

### 3. WebSocket 动态绑定与容错 (`OctopWebSocketManager.kt`)

- 新增 `onThreadBound: ((String) -> Unit)` 回调。由于 Octop 后端每帧数据都会打上 `thread_id`（通过 `stamp_thread_id`），当用户以空会话发送首条消息时，客户端收到流式首帧后自动捕获真实的 `thread_id` 并反写当前会话状态。
- 在 WebSocket 收到包含 `not found` 的错误响应时，自动将 `activeThreadId` 置为 `null`，避免陷入死循环重连。

### 4. 优化历史记录 Block 解析

- 针对 Octop 服务端返回的 `content` 数组结构（包含 `{"type": "text"}` 和 `{"type": "thinking"}`），在 `loadHistory` 中增加健壮的解析提取，确保历史记录能正确区隔对话正文与深度思考块。

---

## 四、 验证与结果 (Verification & Results)

1. **本地自动化编译**：
   - 执行 `./gradlew assembleDebug`，编译耗时 13 秒，构建成功并生成 APK（产物大小 15MB）。
2. **线上自动化部署**：
   - 通过 `scp` 部署至公网中转服务器 `/var/www/html/octop.apk`，权限校验通过（644）。
3. **HTTP 访问验证**：
   - `curl -I http://h2ojie2026.duckdns.org/octop.apk` 返回 `HTTP/1.1 200 OK`，下载通道通畅。
4. **功能验证**：
   - 在 8 个专家之间来回切换，不再触发任何 `thread 'xxxx' not found` 提示；
   - 点击「新建会话」，成功由后端分配新 `thread_id` 并建立新会话流；
   - 首次与无会话的专家聊天，后端成功自动创建绑定会话，流式打字与深度思考正常展开。
