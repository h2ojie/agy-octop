# Issue 017: 发送新消息后立刻将提问置顶吸顶到页面上方 (Antigravity 网页端交互对齐)

## 1. 背景与用户诉求
用户反馈：
> “也学 antigravity，消息发送之后立马就把最新消息置顶到页面上方”
> “发送个暂停按钮太大，不美观，抄antigravity 的样式”

在 Antigravity 网页版手机端交互中：
1. 用户点击发送后，**新提问卡片会立即吸顶置顶到视口最上方**（`headerIndex, offset = 0`），而不是把整个屏幕推到最底下。
2. 提问卡片下方留出大面积开阔视口，供 AI 进行思考（"Working" / "正在思考中..."）和流式生成回复。
3. 暂停/停止生成按钮与发送按钮采用精确 28dp 圆形设计，深色背景（`#282A2D`）内嵌红色圆角方形（`#EA4335`），小巧精致。

## 2. 根因分析
1. 之前的滚动策略使用 `LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length)` 粗暴调用 `animateScrollToItem(messages.size - 1)`：
   - 每次发送消息或 AI 每流式吐出一个 token，都强制滚动到最底部的 `messages.size - 1` 项。
   - 这不仅导致用户的提问被挤到输入框上方或者滚出视口，而且在流式生成中会产生持续不断的动画打架与抖动。
2. 在 `LazyColumn` 结构中，每一轮对话由 `turn.userMessage`（作为 `stickyHeader`）和多个 `turn.aiMessages` 组成，因此 `LazyColumn` 的 item 索引与 `messages` 的索引不一致。需要通过 `turnHeaderIndices` 定位当前交互轮次的头部索引。

## 3. 解决方案与实施细节

### 3.1 监听新增用户提问，立即置顶吸顶
在 `ChatScreen.kt` 中：
```kotlin
// 监听新增用户提问：消息发送之后立马把最新提问消息置顶吸顶到页面最上方 (对齐 Antigravity 网页版交互)
var lastHandledUserMsgId by remember { mutableStateOf<String?>(null) }
val latestUserMsg = remember(messages) { messages.lastOrNull { it.isUser } }

LaunchedEffect(latestUserMsg?.id) {
    val userMsg = latestUserMsg
    if (userMsg != null && userMsg.id != lastHandledUserMsgId) {
        lastHandledUserMsgId = userMsg.id
        val turnIndex = turns.indexOfFirst { it.userMessage?.id == userMsg.id }
        val headerIndex = if (turnIndex >= 0) turnHeaderIndices[turnIndex] else null
        if (headerIndex != null) {
            listState.animateScrollToItem(headerIndex, 0)
        } else {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.animateScrollToItem(total - 1)
        }
    }
}
```
- 当用户发送新消息时，`latestUserMsg.id` 发生变化，触发 `animateScrollToItem(headerIndex, 0)`。
- 该轮次的提问卡片以自然卡片状态精准停靠在屏幕最顶端，AI 占位符与思考卡片在其下方立即展开。

### 3.2 流式生成跟读智能滚动
在 `ChatScreen.kt` 中：
```kotlin
// 流式生成时，如果用户已在查看底部内容且内容超出视口，跟随新内容轻度滚动到底部
val lastAiMsgContentLength = remember(messages) {
    messages.lastOrNull { !it.isUser }?.content?.length ?: 0
}
LaunchedEffect(lastAiMsgContentLength) {
    if (isStreaming && lastAiMsgContentLength > 0) {
        val total = listState.layoutInfo.totalItemsCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (total > 0 && lastVisible >= total - 1) {
            val lastItemInfo = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            if (lastItemInfo != null && (lastItemInfo.offset + lastItemInfo.size) > viewportHeight) {
                listState.animateScrollToItem(total - 1)
            }
        }
    }
}
```
- 若 AI 文本未超出视口高度，绝不触发额外滚动，提问卡片始终稳居顶部。
- 仅当输出内容超出视口且用户正处于底部跟读时，才轻度跟随；若用户手动向上滑动查看历史，则绝不打扰。

### 3.3 停止按钮精确对齐 Antigravity 样式
在 `InputBar.kt` 中：
- 外层为 `Surface(shape = CircleShape, color = Color(0xFF282A2D), modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onStop))`。
- 内部居中放置 10dp 红珊瑚色圆角方形（`Color(0xFFEA4335)`，`RoundedCornerShape(2.dp)`）。

## 4. 验证与分发
1. 执行 `./gradlew assembleDebug`，编译通过。
2. 版本号升级至 `1.0.18`（versionCode: 19）。
3. 同步 APK 至本地、局域网共享盘、Google Drive，并推送代码至 GitHub `origin main`。
