# Issue 019: 大模型回复过程中支持排队发消息 (对齐 Antigravity 队列交互)

## 1. 背景与用户诉求
用户反馈：
> “增加这个跟 antigravity 一样
> 大模型在恢复过程中 如果有输入 可以排队发消息
> 发上去之后放到队列里面，样式也跟 antigravity 一样”
> 并上传了 Antigravity Web 手机端的交互截图（`media_1792079255965_a904e691.jpg` 与 `media_1792079250799_516c580a.jpg`）。

### 核心交互规范 (对标 Antigravity)：
1. **流式生成中支持继续输入与排队发送**：
   - 当大模型正在生成（"Working"）时，输入框不锁死，用户可继续输入文字或选择附件。
   - 输入框右侧按钮动态切换：
     - 若输入框有文字或附件：显示**蓝色排队发送圆形按钮**（带向右/向上发送箭头 `->`）。
     - 若输入框为空：保持显示**暗灰底色+红圆角方形精致停止按钮**。
2. **排队消息卡片 (`Queued Messages`)**：
   - 用户在生成中点击发送后，消息不会强行打断当前流式，而是立即进入该会话的专属排队列表。
   - 输入框清空，输入框上方即时弹起 `Queued Messages` 卡片。
   - 顶部显示：`Queued Messages` + 计数徽标（如 `1`） + `Sends after agent finishes working` + 展开/收起折叠箭头。
   - 卡片展开时列出排队中的消息，右侧具备三项快捷操作：
     - `->`（立即发送/插队：中止当前流式并立即发送该消息）
     - ✏️（编辑：将排队消息取出放回输入框重新编辑）
     - 🗑️（删除：从队列中移除）
3. **自动队列接力**：
   - 当大模型上一轮回复流式完成（`handleWsDone`）后，系统自动检测该会话排队队列，若存在排队消息，平滑出队第一条并自动触发发送，开始新的一轮生成。
4. **会话隔离与草稿绑定**：
   - 队列严格按会话隔离（`threadId`），切换会话或专家时互不干扰；新会话由 draft 绑定至服务端实际 threadId 时自动迁移排队队列。

## 2. 解决方案与实施细节

### 2.1 数据模型扩展 (`Message.kt`)
```kotlin
data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<AttachmentItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
```

### 2.2 视图模型队列管理与自动派发 (`ChatViewModel.kt`)
- `_conversationQueues`: 维护 `threadId -> MutableList<QueuedMessage>` 线程安全映射。
- `sendMessage()`:
  - 检查当前线程 `_streamingThreads.contains(currentTid)`。
  - 若正在生成，组装 `QueuedMessage` 放入当前会话队列，清空输入框与暂存附件，更新 `queuedMessages` 流。
  - 若未在生成，直接执行 `executeSendTurn()`。
- `handleWsDone()`:
  - 在生成完毕标记落定后，调用 `checkAndDispatchNextQueuedMessage(agentId, tid)` 自动取出队列中最早的消息并发送。
- 快捷操作：
  - `removeQueuedMessage(id)`: 移除指定排队项。
  - `editQueuedMessage(id)`: 移除并将其文本与附件还原至输入框。
  - `sendQueuedNow(id)`: 中断当前可能存在的流式并立即发送指定消息。
- `handleWsThreadBound()`: 草稿创建新会话后，自动将临时 draft key 的队列迁移到服务端返回的 boundThreadId。

### 2.3 Antigravity 样式排队卡片 (`QueuedMessagesCard.kt`)
- 圆角矩形 Surface（`RoundedCornerShape(18.dp)`），配合系统深浅主题色与 0.75dp 细微边框。
- 顶部 Header 显示排队数量徽标与 `Sends after agent finishes working` 说明。
- 支持展开/折叠动画与即时删除、编辑、插队操作。

### 2.4 输入栏动态状态切换 (`InputBar.kt`)
```kotlin
val showQueueSend = isStreaming && canSend

if (isStreaming && !showQueueSend) {
    // 流式中且无输入：显示精致停止按钮
    Surface(shape = CircleShape, color = Color(0xFF282A2D), ...) {
        Box(modifier = Modifier.size(10.dp).background(Color(0xFFEA4335), RoundedCornerShape(2.dp)))
    }
} else {
    // 正常发送 或 流式中排队发送 (蓝底箭头按钮)
    val sendBgColor = when {
        showQueueSend -> Color(0xFF0084FF)
        canSend -> if (isDark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
        else -> if (isDark) Color(0xFF2A2C2E) else Color(0xFFE5E7EB)
    }
    ...
}
```

### 2.5 布局整合 (`ChatScreen.kt`)
在 `Scaffold.bottomBar` 内将 `QueuedMessagesCard` 与 `InputBar` 垂直布局，确保排队卡片悬浮于输入框上方，随 IME 键盘同步上升与下降。

## 3. 验证与分发
1. 执行 `./gradlew assembleDebug`，编译通过。
2. 版本号升级至 `1.0.20`（versionCode: 21）。
3. 同步 APK 至本地、局域网共享盘、Google Drive，并推送代码至 GitHub `origin main`。
