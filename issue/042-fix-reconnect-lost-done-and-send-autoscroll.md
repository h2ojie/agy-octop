# Issue 042: 修复断网重连导致丢失结束帧状态卡死及发新消息未自动置底

## 1. 现象与日志分析

### 1.1 业务现象
用户反馈并附截图：
> “我给助手发的最后一个消息不是这个，没显示出来。上传日志了。”
> 截图中，界面停留在上一轮历史提问“把 数据库放 nas 上呢...”，提问框下方呈现大片空白黑屏，底部输入框右侧显示红色的停止生成按钮（处于流式生成假死态）。

### 1.2 日志与数据库排查
1. **服务端核查**：
   - 用户发送的最新提问为：“`安卓没找到接入 webdev 的入口啊`”（附带了一张 Obsidian 设置截图 `1000017230.jpg`）；
   - 在数据库中（`seq=102`），服务端已成功接收该提问；
   - 在 `seq=103`，AI 已经完整生成了对 Obsidian 安装 `Remotely Save` 插件并接入 WebDAV 的详尽解答并落盘数据库。
2. **移动端日志诊断**：
   从移动端诊断日志 `octop_20261025.log` 中定位到：
   ```
   [2026-10-25 01:40:57] 用户发送 user_1ccd55da...
   [2026-10-25 01:41:19] [WARN] [WS] WS failure for agent main: Software caused connection abort
   [2026-10-25 01:41:20] [INFO] [WS] WS connected: agent=main, thread=thr_01M5MTPP3AEGGZBYD9RZ329DTZ
   ```
   - 手机在生成途中底层网络遭遇系统级断连（`Software caused connection abort`）；
   - 1 秒后 WebSocket 自动重连，但由于服务端此时已经完成了本次生成，后续不再向该 WebSocket 下发流式 token 或 `done` 结束帧；
   - 客户端由于**在重连发生时未自动比对云端最新会话快照**，导致：
     - `_isStreaming` 一直保留为 `true`，输入框显示红色停止按钮；
     - 底部 85% 视口缓冲（`bottomSpacerAnimatable`）被持续撑起，下方变黑；
     - 云端已生成的完整回答未能自动同步到本地。
3. **列表未跟随新消息自动置底**：
   - `ChatScreen.kt` 原逻辑仅在切换会话时执行 `listState.scrollToItem`，用户在当前会话中发送新提问时，列表并未自动跟随新轮次滚至最底。

---

## 2. 解决方案与架构重构

### 2.1 重连自动核验云端完成态与补拉同步 (`ChatViewModel.kt`)
在 `onConnectionChange` 中增加自愈逻辑：
```kotlin
onConnectionChange = { connected ->
    val currentTid = _currentThread.value?.threadId
    val activeTid = _agentActiveThreadId[agentId]
    val isCurrent = _currentAgent.value?.agentId == agentId &&
        (currentTid == threadId || (threadId.startsWith("draft_") && (currentTid == null || currentTid == activeTid)))
    if (isCurrent) {
        _isConnected.value = connected
    }
    if (connected && !threadId.startsWith("draft_")) {
        // 核心自愈：网络重连成功后，如果该会话处于流式等待或当前会话，自动拉取云端历史核对完成态，防止断线丢失 done 帧导致假死卡住
        if (_streamingThreads.contains(threadId) || isCurrent) {
            viewModelScope.launch {
                loadHistory(agentId, threadId)
            }
        }
    }
}
```
一旦断线重连，自动调用 `loadHistory`，服务端若指示 `turnActiveOnServer == false`，立即解除流式状态，将最新消息与 AI 完成答复无缝对齐展示。

### 2.2 发送新消息与轮次增加自动平滑滚至最底 (`ChatScreen.kt`)
在 `ChatScreen.kt` 中监听 `turns.size` 与 `isStreaming` 变更：
```kotlin
// 当产生新交互轮次（如发送新提问）或进入流式响应时，平滑滚动定位至最新消息（列表底部）
var lastTurnCount by remember { mutableIntStateOf(turns.size) }
LaunchedEffect(turns.size, isStreaming) {
    if (turns.size > lastTurnCount || (isStreaming && turns.isNotEmpty())) {
        lastTurnCount = turns.size
        kotlinx.coroutines.delay(60L)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            listState.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    } else {
        lastTurnCount = turns.size
    }
}
```
用户点击发送瞬间，列表立即自动平滑滚至最新生成的提问与思考卡片处，视野绝对连贯。

---

## 3. 产物与版本验证

1. **版本升级**：
   - `versionCode = 44`，`versionName = "1.0.43"`；
2. **多端分发落盘**：
   - 本地根目录：`octop-v1.0.43.apk`
   - 家庭 NAS：`/Volumes/tools/apk/octop-v1.0.43.apk`
   - Google Drive 云端硬盘：`我的云端硬盘/octop-apk/octop-v1.0.43.apk`
