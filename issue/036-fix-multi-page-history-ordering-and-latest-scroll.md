# Issue 036: 修复历史对话跨页拼接倒序与初次加载定位最新状态 (v1.0.37)

## 1. 需求背景与痛点
用户反馈并配图：
> “对话明显没有加载到最新的状态”

### 痛点根因分析：
1. **跨页历史消息拼接倒序严重断层**：
   - Octop 服务端数据库按 `ORDER BY seq DESC LIMIT 200 OFFSET ...` 查询消息，且在返回前针对该页执行了 `page.reverse()` 使单页内部呈现时间正序；
   - 移动端在长会话（如超过 200 条消息的「查额度」会话，总计 393 条记录）中拉取多页：
     - 第 1 页（`offset = 0`）：获取最新 200 条记录（seq 194 ~ 393，时间跨度 10月10日 ~ 10月22日）；
     - 第 2 页（`offset = 200`）：获取更早的 193 条记录（seq 1 ~ 193，时间跨度 9月17日 ~ 10月10日）；
   - 客户端原代码直接采用 `allRawPages.flatten()` 简单拼接，导致第 1 页（新消息）在前、第 2 页（旧消息）在后：
     `[seq 194 ~ 393 (新), seq 1 ~ 193 (旧)]`！
   - 整条会话在最末尾（列表最底部）呈现的竟然是 10月10日 的历史内容，而 10月22日 真正的最新回复被挤到了会话中间偏上位置；
   - 客户端更通过 `saveThreadToDisk` 将此倒序结构固化到了本地磁盘缓存中。
2. **切入会话缺少自动定位最新状态**：
   - 用户打开或切换会话时，LazyColumn 默认停留在首项或旧位置，没有自动定位至会话最底部的最新轮次。

---

## 2. 解决方案与实现详情

### 2.1 跨页分页反转展平与边界去重 (`ChatViewModel.kt`)
- 在 `loadHistory` 中，将获取的各页按 `allRawPages.reversed().flatten()` 处理：
  ```kotlin
  // 服务端按 seq DESC 分页并在每页内部 reverse()，导致较旧的页在后；
  // 必须将页序列反转 (allRawPages.reversed()) 后展平，才能使整条会话严格按时间正序排列至最新消息
  val rawDtos = allRawPages.reversed().flatten()
  val seenIds = mutableSetOf<String>()
  val allDtos = rawDtos.filter { dto ->
      val id = dto.id
      if (!id.isNullOrBlank()) {
          seenIds.add(id)
      } else {
          true
      }
  }
  val serverMessages = processDialogueHistory(allDtos, agentId, threadId)
  ```
  拼接结果变为：`[seq 1 ~ 193 (旧), seq 194 ~ 393 (新)]`，完整按时间正序贯穿至 10月22日 最新答复。

### 2.2 本地磁盘缓存时序校验与自愈 (`sanitizeCachedMessages`)
- 针对用户设备上已持久化的旧版倒序缓存文件，在 `loadThreadFromDisk` 读取时增加校验：
  ```kotlin
  private fun sanitizeCachedMessages(messages: List<ChatMessage>?): List<ChatMessage>? {
      if (messages.isNullOrEmpty()) return messages
      val hasTimeInversion = messages.zipWithNext().any { (prev, next) ->
          prev.timestamp > 0 && next.timestamp > 0 && prev.timestamp > next.timestamp + 5000L
      }
      return if (hasTimeInversion) messages.sortedBy { it.timestamp } else messages
  }
  ```
  网络返回前即可自愈本地缓存，避免闪烁。

### 2.3 会话切入与手动刷新自动置底 (`ChatScreen.kt`)
- 增加会话切换 / 首次加载监听，测量稳定后自动跳至最底部最新轮次：
  ```kotlin
  var lastScrolledThreadId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(currentThread?.threadId, turns.isNotEmpty()) {
      val tid = currentThread?.threadId
      if (!tid.isNullOrBlank() && turns.isNotEmpty() && lastScrolledThreadId != tid) {
          lastScrolledThreadId = tid
          kotlinx.coroutines.delay(80L)
          val total = listState.layoutInfo.totalItemsCount
          if (total > 0) {
              listState.scrollToItem((total - 1).coerceAtLeast(0))
          }
      }
  }
  ```
- 顶部栏刷新按钮点击后，同步平滑滚至最底最新轮次。

---

## 3. 验证要点
1. **超长会话加载验证**：
   - 打开「小通 · 通用助手 · 查额度」会话；
   - 滚动到底部，最新消息为 10月22日 19:44:58 的 Codex 额度查询结果，不再停留在 10月10日；
2. **切入会话与刷新自动置底**：
   - 进入会话或点击右上角刷新，界面自动展示最新轮次的提问与 AI 回答。
