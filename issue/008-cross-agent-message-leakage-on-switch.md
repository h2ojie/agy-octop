# Issue 008: 切换专家后后台思考完成的消息串门弹出到当前对话 (跨专家消息泄露)

- **问题编号**：ISSUE-008
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `ChatViewModel.kt`:
    - 引入专家激活会话追踪映射 `_agentActiveThreadId: ConcurrentHashMap<String, String>()`，杜绝跨专家借用 `_currentThread.value?.threadId` 导致的错位寻址；
    - 全面强化双重键路由拦截校验：所有下行处理与 UI 刷新逻辑（`handleWsToken`、`handleWsThinking`、`handleWsDone`、`handleWsError`、`loadHistory` 及 Watchdog 超时回调）均严格校验 `_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid`；
    - 修复 `handleWsThreadBound` 在后台收到其他专家的会话绑定通知时误篡改当前界面 `_currentThread.value` 并触发跨专家重载的严重漏洞，并支持草稿会话 `draft_${agentId}` 的平滑迁移；
    - 优化 `selectAgent`：切换专家时优先无缝恢复该专家上一次选中的会话与内存缓存，消除切换时空白与闪烁。
  - `OctopWebSocketManager.kt`:
    - 在 `sendUserTurn` 和 `subscribe` 中自动绑定专线 `activeThreadId`，确保 LangGraph 下发的无 `thread_id` 原始数据帧能准确溯源到该专家的目标会话。
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.7` (versionCode = 8)。
- **状态**：已解决 (Resolved)

---

## 一、 问题现象 (Issue Phenomenon)

在 v1.0.6 实现了专家级长连接池与多会话缓存后，用户反馈出现新的跨专家消息窜门泄露现象：
1. 用户在“家庭医生”专家下发送了一条咨询消息，大模型开始思考（界面显示思考中）；
2. 用户在模型思考期间，通过抽屉切换到了“小通助手”（通用助理）准备做其他事情；
3. 过了一会儿，“家庭医生”那边的模型思考完成并开始输出正文回复，**忽然这个回复直接弹到了用户当前正在浏览的“小通助手”的聊天气泡中**！
4. 并且，如果此时在“小通助手”下触发了历史刷新，还会把“家庭医生”那边的历史也拉取过来覆盖，造成严重的会话内容混淆。

---

## 二、 根因分析 (Root Cause Analysis)

### 1. LangGraph 下行流式数据帧未携带 `thread_id`
Octop 后端基于 LangGraph 异步流分发时（`ws_channel.py:153`），广播给客户端的 `token`、`thinking`、`done` 等帧格式如下：
```json
{"type": "token", "content": "..."}
```
其 JSON 根对象中并没有附带 `thread_id` 字段。

### 2. 致命的兜底回退：`threadId ?: _currentThread.value?.threadId`
在 v1.0.6 的 `handleWsToken` / `handleWsThinking` / `handleWsDone` 中：
```kotlin
val tid = threadId ?: _currentThread.value?.threadId ?: return
```
当客户端收到上述无 `thread_id` 的数据帧时：
1. 传入的 `threadId` 为 `null`；
2. 代码回退使用了 `_currentThread.value?.threadId`；
3. **但此时用户已经切到了“小通助手”，`_currentThread.value?.threadId` 指向的是“小通助手”的当前会话！**
4. 导致“家庭医生”的 Token 被错误写进了“小通助手”的会话缓存 `_threadMessagesCache[tid_main]` 中！

### 3. UI 刷新判断仅校验了 `threadId`，缺少 `agentId` 维度
原先的屏幕刷新判定逻辑为：
```kotlin
if (_currentThread.value?.threadId == tid) {
    _messages.value = synchronized(list) { list.toList() }
    _isStreaming.value = true
}
```
因为上面第 2 步将 `tid` 赋值为了 `_currentThread.value?.threadId`，条件必然为 `true`！“家庭医生”的回复内容瞬间在“小通助手”的屏幕上喷涌而出。

### 4. `handleWsThreadBound` 篡改了当前活跃专家
在后台某个专家会话绑定时（如从 draft 或服务端确认）：
```kotlin
private fun handleWsThreadBound(agentId: String, boundThreadId: String) {
    if (_currentThread.value == null || _currentThread.value?.threadId != boundThreadId) {
        _currentThread.value = ThreadItem(...) // 没校验当前 agentId！
        loadThreadsForAgent(agentId)
    }
}
```
当后台“家庭医生”产生通知时，若没有检查 `_currentAgent.value?.agentId == agentId`，就会直接把当前屏幕的 `_currentThread` 覆盖为“家庭医生”的会话，彻底打乱用户的视图状态。

---

## 三、 解决方案 (Solution Implementation)

### 1. 专家专线连接绑定 `activeThreadId` (`OctopWebSocketManager.kt`)
在每个专家的 `OctopWebSocketManager` 中维护实例私有的 `activeThreadId`：
```kotlin
fun sendUserTurn(text: String, threadId: String? = null): Boolean {
    val targetTid = threadId ?: activeThreadId
    if (!targetTid.isNullOrBlank()) {
        activeThreadId = targetTid
    }
    // ...
}
```
在解析下行帧时：
```kotlin
val parsedTid = json.get("thread_id")?.asString ?: activeThreadId
```
只要发送过消息或订阅过会话，该实例下的所有数据帧均强制打上该专家专属的 `parsedTid`，决不向外传递 `null`。

### 2. ViewModel 维护专家级活跃会话字典 `_agentActiveThreadId` (`ChatViewModel.kt`)
```kotlin
// 专家当前激活的会话映射：agentId -> threadId (严格保证跨专家状态隔离与路由正确)
private val _agentActiveThreadId = ConcurrentHashMap<String, String>()
```
- 当用户在某个专家下选择会话、发送消息或创建会话时，记录到 `_agentActiveThreadId[agent.agentId]`；
- 在解析下行数据帧时，解析规则为：
  ```kotlin
  val tid = threadId ?: wsManagers[agentId]?.activeThreadId ?: _agentActiveThreadId[agentId] ?: return
  ```
  **绝对禁止**回退到全局的 `_currentThread.value?.threadId`！若找不到对应专家的会话则安全丢弃或忽略，绝不跨专家借用。

### 3. 全链路施加 `agentId + threadId` 双重安全锁
所有涉及屏幕状态更新和网络历史同步的方法：
```kotlin
// 1. handleWsToken / handleWsThinking
if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
    _messages.value = synchronized(list) { list.toList() }
    _isStreaming.value = true
}

// 2. handleWsDone
if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == tid) {
    _messages.value = list?.let { synchronized(it) { it.toList() } } ?: emptyList()
    _isStreaming.value = false
}

// 3. loadHistory
if (_currentAgent.value?.agentId == agentId && _currentThread.value?.threadId == threadId) {
    _messages.value = synchronized(list) { list.toList() }
    _isStreaming.value = _streamingThreads.contains(threadId)
}

// 4. handleWsThreadBound
if (_currentAgent.value?.agentId == agentId) {
    // 仅在属于当前激活专家时才允许更新界面 currentThread
}
```
后台运行的专家产生的任何事件，仅静默更新其自身的 `_threadMessagesCache[tid]`，**决不干涉、决不污染、决不弹入**当前前台专家界面。

### 4. 优化专家切换体验 (`selectAgent`)
切换专家时，先查找该专家历史记录的 `_agentActiveThreadId[agent.agentId]`：
- 若存在，立即调出本地缓存 `_threadMessagesCache[lastTid]` 进行秒级渲染，避免切换时会话短暂空白；
- 界面即刻切换，后台历史随后静默完成一致性校验。

---

## 四、 验证结果 (Verification)

1. **多专家并发测试**：
   - 打开“家庭医生”，发送复杂的医学问题（模型思考耗时约 10~15 秒）；
   - 在思考期间，立刻切换到“小通助手”，发送“今天天气怎么样”；
   - “小通助手”正常流式生成并完成，界面未出现任何“家庭医生”的消息碎片；
   - 切换到“育儿管家”浏览历史记录，界面完全正常；
   - 切换回“家庭医生”，发现“家庭医生”的医学诊断与完整思考过程已完整生成并静默就绪，点开即可阅读。
2. **构建与发布**：
   - 版本号更新为 `v1.0.7` (`versionCode = 8`)；
   - 编译生成 `app-debug.apk`；
   - 发布至 `/Volumes/tools/apk/octop-v1.0.7.apk`、`/Volumes/tools/apk/octop-v1.0.0.apk` 以及云端下载直链 `http://h2ojie2026.duckdns.org/octop.apk`。
