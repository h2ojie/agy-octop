# Issue 043: 修复「界面没有刷新」——历史投影滞后导致待确认提问被云端快照抹除 (v1.0.44)

## 1. 现象与现场证据

### 1.1 用户报告

> 「查一下，我刚刚在这里发了个消息，但是界面好像没有刷新，我已经上传日志了，日志在 good drive 里面」

附带截图（2026-10-25 19:53:18 拍摄）显示：

- 顶部吸顶卡片为**旧**提问「查一下 gemin 额度」（小写 g）；
- AI 回复时间戳为 **10月24日 16:08**（旧一轮）；
- 底部输入框为空，模型选择器显示 `cli / Gemini 3.8 Flash`。

### 1.2 日志定位

移动端诊断日志经 Google Drive `octop-logs` 链路回传，落盘于
`~/.octop/workspaces/main/inbound/1792929203_octop_logs_20261025_195323.zip`，
解包后为 `octop_20261025.log`（725 行）。

关键窗口（19:52:45 ~ 19:53:23）：

```
[19:52:45.674] [WARN] [WS] WS failure for agent main (thread: null, code: null): Software caused connection abort
[19:52:45.684] [WARN] [WS] WS failure for agent main (thread: thr_01M5MTPP3AEGGZBYD9RZ329DTZ, code: null): Software caused connection abort
[19:52:45.795] [INFO] [WS] Scheduling WS reconnect in 2s for agent main
[19:52:46.605] [INFO] [WS] WS connected: agent=main, thread=thr_01M5MTPP3AEGGZBYD9RZ329DTZ, httpCode=101
[19:52:48.556] [INFO] [WS] WS connected: agent=main, thread=null, httpCode=101
[19:52:53.440] [INFO] [WS] WS connected: agent=main, thread=thr_01M2QZRAPEEBCPRGANB3F2CJAH, httpCode=101
[19:52:53.686] [INFO] [WS] WS closed for agent main, code=1000, reason=Reconnecting
[19:53:01.476] [INFO] [WS] WS closed for agent main, code=1000, reason=Reconnecting
[19:53:02.114] [INFO] [WS] WS connected: agent=main, thread=thr_01M2QZRAPEEBCPRGANB3F2CJAH, httpCode=101
[19:53:07.922] [DEBUG] [UI] AI message vertically dragged, collapsing prompt card: 85884a8d-930e-438e-afdc-714090fb8d11
```

### 1.3 服务端数据库核查（决定性）

线程 `thr_01M2QZRAPEEBCPRGANB3F2CJAH`（标题「查额度」，`channel_type=weixin`）的完整消息链：

| seq | role | created_at | 内容 |
| :--- | :--- | :--- | :--- |
| 414 | human | 10-24 16:05:45 | 查一下 gemin 额度（旧，小写 g） |
| 415/416/417 | ai/tool/ai | 10-24 16:06:54 ~ 16:08:26 | 旧答案（截图里显示的就是它） |
| **418** | **human** | **10-25 19:53:03** | **查一下 Gemini 额度（新，大写 G）** |
| 419 | ai | 10-25 19:55:58 | （空 content，带 tool_call 元数据） |
| 420 | tool | 10-25 19:56:07 | 额度表格 |
| 421 | ai | 10-25 19:57:13 | 完整额度表（最终答案） |

**结论：消息从未丢失，服务端已正常产出完整答案**（seq 421 的内容与用户截图中显示的表格一致）。
用户 19:53:18 截图、19:53:23 上传日志时，服务端仍在执行该轮（19:53:03 → 19:57:13，耗时 4 分 10 秒）。

### 1.4 两个必须澄清的排查陷阱

**陷阱一：WAL 快照导致"消息丢失"的误判。**
直接 `sqlite3 octop.db` 读到 seq 421；而 `sqlite3 'file:octop.db?immutable=1'`（忽略 WAL）只到 seq 417。
本案最初一度被误判为"服务端从未收到消息"，实为 WAL 未 checkpoint 造成的读取方式差异。

**陷阱二：客户端日志首行不是当前版本。**
`DiagnosticLogger.init()` 仅在进程启动时打印一次，日志文件跨会话追加。首行为
`Version=1.0.42 (43)`，但该行属于 01:39 的旧会话；本次事发时段的会话起始行为
`[2026-10-25 18:28:45.564] ... Version=1.0.43 (44)`。
**手机当时已经是 v1.0.43**（即已含 Issue 042 的自动置底修复），因此"装 v1.0.43 即可"不成立。

---

## 2. 根因分析

### 2.1 直接原因：历史投影滞后 + 客户端快照覆盖 = 待确认提问被抹除

服务端 `thread_messages` 并非实时日志，而是**回合结束后的整体投影**：

- `octop/infra/db/repos/thread_messages.py:231-280` → `append_if_ready()`，
  docstring 明写 *"Append one completed turn."*；
- 唯一调用点 `octop/infra/gateway/process/processor.py:1296`
  → `await self._record_turn_history(thread_id, history_tracker)`，
  该行位于 `async for chunk in ...` 循环**完全排空之后**，且被 `if stream_ok:` 包裹。

因此：本轮跑完之前（19:53:03 ~ 19:57:13），云端历史里**根本不存在 seq 418**。

客户端 `ChatViewModel.loadHistory()`（原 `ChatViewModel.kt:736`）在重连自愈时被触发，
其对齐逻辑分三支，其中「正常对齐云端快照」分支是**破坏性**的：

```kotlin
} else {
    // 正常对齐云端快照…
    val reconciled = serverMessages.toMutableList()
    …
    list.clear()          // ← 整表清空
    list.addAll(reconciled)   // ← 只写回云端快照
}
```

由于云端快照此刻尚无 seq 418，刚发出、正在等待的用户提问连同 AI 占位块被整体抹掉，
列表退回上一轮（seq 414/417）——**正是截图所见**。

触发路径（客户端日志与代码一一对应）：

1. 19:52:45 手机网络中断（`Software caused connection abort`），两个 WS 同时断开；
2. 19:53:02.114 目标会话 WS 重连成功 → `onConnectionChange(true)` → 触发 `loadHistory()`；
3. 此时本轮尚未开始（19:53:03），云端历史最新仍是 seq 417；
4. 19:53:03 用户消息发出（本地乐观插入 userMsg + aiPlaceholder）；
5. 19:53:07 某次 `loadHistory()` 再次执行 → `list.clear()` → **本地待确认提问被抹除**；
6. 19:53:18 截图：只剩旧轮次，用户判定"界面没有刷新"。

### 2.2 放大原因一：`turn_active` 误判导致流式锁被提前解除

`ChatViewModel.kt:783-786`（原）：

```kotlin
if (!turnActiveOnServer) {
    _streamingThreads.remove(threadId)
    stopStreamingWatchdog(threadId)
}
```

服务端的 `turn_active` 来自 `ws_hub._active_turns`（`octop/infra/gateway/ws/ws_hub.py:103-112`），
是**按连接维护的瞬时标记**，与历史投影一样存在滞后。
客户端据此解除流式锁后：`_isStreaming=false` → 红色停止按钮消失 →
`ChatScreen.kt:172-178` 的 85% 底部缓冲收拢至 16dp → **底部出现大片空白**，
进一步强化了"界面卡死"的观感。

### 2.3 放大原因二：60 秒看门狗误杀仍在运行的轮次

原 `startStreamingTimeoutWatchdog()` 硬编码 `delay(60_000L)`，60 秒内无任何帧即判死，
并把空占位块改写为「⚠️ 服务器响应超时，请重试或检查服务状态。」

而上游 provider 当时处于容量紧张（`MODEL_CAPACITY_EXHAUSTED` / `OVERLOADED_TOO_MANY_RETRIES_PER_REQUEST`），
实测首字延迟：

| 轮次 | 发出 | 首个 AI | 等待 |
| :--- | :--- | :--- | :--- |
| 10-25 18:29 | 18:29:33 | 18:32:29 | **176s** |
| 10-25 19:13 | 19:13:11 | 19:14:48 | 97s |
| 10-25 19:17 | 19:17:49 | 19:21:01 | **192s** |
| 10-25 19:35 | 19:35:13 | 19:35:41 | 28s |
| 10-25 19:40 | 19:40:59 | 19:44:23 | **204s** |
| 10-25 19:52 | 19:52:11 | 19:56:02 | **231s** |
| **本案 19:53** | **19:53:03** | **19:55:58** | **175s** |

首字延迟 97~231 秒，**系统性超过 60 秒看门狗**。即服务端正在正常作答，客户端却谎报超时。

### 2.4 次要问题：连接未就绪时发送的消息被直接丢弃

原 `executeSendTurn()`：

```kotlin
if (!ws.isConnected) {
    _toastMessage.value = "正在连接服务，请稍候重试..."
    ws.connect(targetTid)
    return          // ← 用户输入凭空消失，且永不重发
}
```

且排队队列仅由 `handleWsDone()` 消费（`checkAndDispatchNextQueuedMessage` 的唯一调用点），
一旦没有正在跑的流，就永远等不到 `done`，队列消息永久滞留。

### 2.5 可观测性缺口

`sendUserTurn()` 仅使用 `android.util.Log`（TAG `OctopWS`），**未写入 `DiagnosticLogger`**，
导致上传的诊断日志中完全看不到"发送"这一动作——本次排查只能靠 DB 侧推倒时间线。

---

## 3. 修复方案（v1.0.44）

### 3.1 修复一：绝不因云端快照抹除待确认的本地提问（`ChatViewModel.loadHistory`）

新增"待确认提问"识别：本地列表中存在用户消息、且**云端最后一条用户消息**与其内容不一致，
即判定本轮仍在服务端处理中（投影滞后）。

> 采用"与云端最后一条用户消息比对"而非"任意同文本消息"：用户连续两次发送「继续」时，
> 后者会误命中更早的同文本消息，把真正待确认的当前提问误判为已确认而抹掉。

对齐云端快照时改为**保留**本地待确认提问与 AI 占位块：

```kotlin
val pendingUserMsg = synchronized(list) { list.lastOrNull { it.isUser && !it.isStreaming } }
val serverLastUserContent = serverMessages.lastOrNull { it.isUser }?.content?.trim()
val serverHasPendingUser = pendingUserMsg != null &&
    serverLastUserContent != null &&
    serverLastUserContent == pendingUserMsg.content.trim()
val hasPendingUnackedUser = pendingUserMsg != null && !serverHasPendingUser
```

- `hasPendingUnackedUser` 为真时，走**保留分支**而非破坏性的 `else` 分支；
- 仅当云端确实没有该消息时才补回本地副本（避免云端已收录时重复显示两遍）；
- 同时**不再**依据滞后的 `turn_active` 解除流式锁（见 3.2）。

### 3.2 修复二：看门狗超时前先向云端核实（`startStreamingTimeoutWatchdog`）

60 秒无响应时，先查询云端 `turn_active`：

- 云端**仍在执行** → 不判死，继续等待（最多 4 轮 / 4 分钟），并把占位提示改为
  「⏳ 服务端仍在处理中（上游模型排队较慢），请稍候…」；
- 云端**已结束或查询失败** → 才判定超时并给出重试提示。

循环化实现（`while (waitedRounds < maxRounds)`）替代递归重入，避免看门狗 job 自我取消的竞态。

### 3.3 修复三：连接未就绪 / 发送失败时改为入队而非丢弃（`executeSendTurn`）

两种失败路径均改为把消息放入该会话的排队队列，并给出明确提示：

- `!ws.isConnected` → 「正在连接服务，消息已排队，连接成功后自动发送…」
- `sendUserTurn` 返回 false → 「发送失败，消息已排队，正在重新连接…」

### 3.4 修复四：新增队列补发通道（`dispatchQueuedMessagesOnConnect`）

在 `onConnectionChange(true)` 时触发，把因连接未就绪而入队的消息逐条补发。
若该会话此刻正在流式生成，则仍沿用原有语义等 `done` 后接力，避免并发压垮服务端。

### 3.5 修复五：发送链路写入诊断日志

- `ChatViewModel.sendMessage()`：记录 `dispatch` / `queued` 分支与队列长度；
- `ChatViewModel.executeSendTurn()`：记录 `sendUserTurn sent=...`；
- `OctopWebSocketManager.sendUserTurn()`：新增 `DiagnosticLogger.i(TAG_STATE, ...)`。

今后同类问题可直接从上传日志确认"消息是否真的发出"，无需再靠 DB 侧推演。

---

## 4. 验证与分发产物

- 编译：`./gradlew assembleDebug --offline` 通过，零报错；
- 版本：`v1.0.44`（VersionCode: 45）；
- 产物：`octop-v1.0.44.apk`；
- 分发：Google Drive `我的云端硬盘/octop-apk/` + 家庭 NAS `/Volumes/tools/apk/`；
- 历史 Issue 文档同步归档至 `issue/043-...md`。

### 4.1 回归影响评估

| 改动 | 影响面 | 风险 |
| :--- | :--- | :--- |
| loadHistory 保留待确认提问 | 仅在"本地有未确认提问"时改变对齐结果 | 低；云端已收录时不补回，不会重复显示 |
| `turn_active` 解除流式锁增加前置条件 | 仅影响存在未确认提问的会话 | 低；真实超时时最多多等 4 分钟 |
| 看门狗先核实云端 | 每次超时多一次 HTTP 查询 | 低；查询失败即按原逻辑判死 |
| 发送失败改入队 | 连接异常时消息不再丢失 | 低；队列卡片可见、可手动编辑/删除 |
| 连接建立补发队列 | 仅在队列非空且无流在跑时触发 | 低；逐条串行发送 |

### 4.2 仍存的上游问题（非本客户端修复范围）

服务端 `thread_messages` 仍是"回合结束后整体投影"，历史轮询在本轮执行期间无法看到新消息；
`turn.started` / `turn.ended` / `tool_call_chunk` / `steer.ack` 等帧客户端均未处理
（`OctopWebSocketManager.kt` 的 `when(type)` 无对应分支且无 `else` 兜底，未知帧被静默丢弃）；
服务端 `ModelRetryMiddleware` 无任何日志，上游排队与真实失败在服务端日志中不可区分。
这些属于服务端/协议层改进项，建议后续单独立项。
