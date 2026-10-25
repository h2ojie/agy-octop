# Issue 044: 修复停止按钮常亮——未处理 turn 生命周期帧与工具阶段失联 (v1.0.45)

## 1. 现象

用户截图为 2026-10-25 21:28，界面状态：

- 顶部吸顶卡片：「查一下 Gemini 额度」（**大写 Gemini**，即 19:53 那一轮）；
- 已渲染完整额度表，时间戳 **10月25日 19:57**（该轮已成功答完）；
- **输入框右侧是红色停止按钮**，而非发送箭头；
- 用户提问：「现在这个是什么状态？为什么发送按钮是一个停止的命令」。

即：**答案已经显示出来了，但界面仍认为"正在生成"**。

## 2. 现场核查（直连线上服务端）

### 2.1 turn_active 持续为 true

```
GET /api/agents/main/threads/thr_01M2QZRAPEEBCPRGANB3F2CJAH/history
→ turn_active = true          # 19:57 那轮早已完成，标记却仍挂着
```

对 45 个线程做全量扫描：**只有这 1 个线程 `turn_active=true`**，排除全局性缺陷。

### 2.2 实际存在第二轮（20:44 发起，与 19:57 那轮无关）

`trajectory_events` 显示：

| 时间 | 事件 |
| :--- | :--- |
| 19:53:03 | user「查一下 Gemini 额度」 |
| 19:57:13 | assistant 完整额度表（**✅ 已答完，即截图所见**） |
| **20:44:37** | **user「能查一下 Gemini 账号支持 gemin-4-argon 吗」**（source=dashboard） |
| 20:46:57 ~ 21:41:29 | **26 次工具调用**（25 次 ssh 到 `54.150.38.139`） |
| 21:41:29 之后 | 无任何新工具事件 |

**关键**：手机上看到的额度表属于 19:57 那轮，而 20:44 那轮**从未产出答案**，且它一直在跑。停止按钮反映的是 20:44 这轮 —— 所以按钮亮着本身是"正确"的，问题在于**这轮永远不结束，客户端也不会告诉用户发生了什么**。

### 2.3 WS 帧实测（直接订阅该线程）

```
FRAME type=turn_status           | {'active': True}
FRAME type=steer.queue_snapshot  | {'queue_length': 0, 'turn_id': '01M5SHETYAZERFJTK17T9T2RK2'}
FRAME type=turn.resumed          | {'phase': 'awaiting_tools', 'turn_id': '...'}
（之后再无任何帧）
```

`turn_id` ULID 解码 = **2026-10-25 20:44:37**，与 2.2 完全吻合。

## 3. 根因分析

### 3.1 根因一（服务端/上游）：工具循环后陷入无上限的模型重试（真正的"卡死"）

最后 5 次工具调用的结果呈现明显的**信息收集饱和 + 反复重试同一目标**：

```
call 1: ssh ... python3 读 /opt/cliproxyapi/cli-proxy-api（二进制）→ 乱码解析失败
call 2: 同目标，扫描 JSON 边界 → 输出一堆 "Success parsing JSON from 70xxxxxx"
call 3: 同目标 → 终于取到 gemini-4-argon-high 定义
call 4: 同目标 → 取到 gemini-4-argon（google）定义
call 5: 读 /var/lib/cliproxyapi/auths/antigravity-jieice02@gmail.com.json
        → PermissionError: [Errno 13] Permission denied
```

26 次调用中 **11 次是在反复读取同一个二进制文件**，属于典型的"工具结果不能收敛"。

随后（21:41 起）进入模型请求重试：

```
21:41:37 Retrying request to /chat/completions in 0.464470 seconds
21:41:39 Retrying request to /chat/completions in 59.000000 seconds
21:48:17 Retrying request to /chat/completions in 59.000000 seconds
```

`59s` 来自上游 `Retry-After` 响应头：`openai/_base_client.py::_calculate_retry_timeout` 中
`retry_after is not None and 0 < retry_after <= 60: return retry_after`。

**没有任何"回合级"总时长上限**：

- `openai.DEFAULT_MAX_RETRIES = 2`（重试次数有限）；
- 但 `openai.DEFAULT_TIMEOUT = httpx.Timeout(timeout=600, connect=5.0)` —— **单次请求可挂 600 秒**；
- harness 的 `ModelRetryMiddleware` 仅 `max_retries=2`，**且全程零日志**（`langchain/agents/middleware/model_retry.py` 中 `logger` 出现次数为 0）；
- 仓库内搜不到 `turn_timeout` / `max_turn` 之类的兜底。

因此该轮在 `turn_active=true` 状态下持续挂起，**55 分钟以上**无产出、无错误、无日志（仅两条 SDK INFO 重试行）。

### 3.2 根因二（客户端）：完全不识别服务端的 turn 生命周期帧

`OctopWebSocketManager.onMessage` 的 `when(type)` 旧实现只有 7 个分支：

```kotlin
"token" / "thinking","reasoning" / "tool_call" / "hitl_required" / "done" / "error" / "turn_status"
```

**且没有 `else` 兜底**。于是服务端实际推送的下列帧全部被静默丢弃：

| 帧类型 | 服务端发出位置 | 客户端 |
| :--- | :--- | :--- |
| `turn.started` | `ws_channel.py:169-178` | ❌ 无分支 |
| `turn.resumed` | `ws_channel.py:277-286` | ❌ 无分支 |
| `turn.ended` | `ws_channel.py:333-343` | ❌ 无分支 |
| `tool_call_chunk` | harness 协议（`TOOL_CALL_CHUNK`） | ❌ 只认 `tool_call` |
| `tool_result` | `ws_channel.py` | ❌ 无分支 |
| `steer.ack/queued/drained/discarded/queue_snapshot` | steer 协调器 | ❌ 无分支 |

实测证据：`grep -c "turn.started\|turn.resumed\|turn.ended\|steer\."` 在客户端源码中为 **0**。

### 3.3 根因三（客户端）：`turn_status` 只处理 active=false

```kotlin
"turn_status" -> {
    val active = json.get("active")?.asBoolean ?: false
    if (!active) onDone(parsedTid)     // active=true 时 什么都不做
}
```

服务端明确告知 `active=true`（本轮仍在执行），客户端却毫无响应 —— 既不更新提示，也无法据此抑制看门狗误判。

### 3.4 根因四（客户端）：看门狗硬超时会误杀长工具调用

- `startStreamingTimeoutWatchdog`：60s × 4 轮 = **4 分钟**上限；
- `resetStreamingWatchdog`：**45 秒**无 token 即判"连接中断，生成已停止"。

而工具调用（ssh、构建、长命令）**合法耗时数分钟到数十分钟**，期间服务端不会推任何 `token`。
本案中 26 次工具调用跨越 55 分钟，任一环节都会触发误判。

v1.0.44 已让 `startStreamingTimeoutWatchdog` 在超时前核验云端 `turn_active`，但
`resetStreamingWatchdog`（45 秒那条）仍是硬超时，且两处都设了轮次上限，云端持续活跃时仍会到期判死。

## 4. 修复方案（v1.0.45）

### 4.1 补齐 turn 生命周期帧处理（`OctopWebSocketManager.kt`）

新增分支：

```kotlin
"tool_call_chunk" -> { ... onToolCall(...) }        // 兼容 harness 真实帧名
"tool_result"     -> { onToolResult(parsedTid) }    // 刷新活跃时间
"turn.started", "turn.resumed" -> { onTurnResumed(parsedTid, phase) }
"turn.ended"      -> { onDone(parsedTid) }
"steer.ack", "steer.queued", "steer.drained",
"steer.discarded", "steer.queue_snapshot" -> { /* 记录日志 */ }
else -> { DiagnosticLogger.d(... "WS unhandled frame type=...") }   // 兜底，杜绝静默丢弃
```

`turn_status` 的 `active=true` 分支改为调用 `onTurnResumed(parsedTid, phase)`。

### 4.2 新增 `handleWsTurnResumed`（`ChatViewModel.kt`）

服务端确认本轮仍在执行时：

1. 把线程重新标记进 `_streamingThreads`（保证输入框显示"排队发送/停止"语义正确）；
2. **重置看门狗**（服务端在跑，不应按"无响应"判死）；
3. 当占位块仍为空时，按阶段给出可见提示，取代空白等待：

| phase | 提示 |
| :--- | :--- |
| `awaiting_tools` / `between_model_steps` | ⚙️ 服务端仍在执行工具调用，请稍候… |
| `streaming_text` | ⏳ 服务端仍在生成中，请稍候… |
| 其他 | ⏳ 服务端仍在处理中，请稍候… |

### 4.3 看门狗改为"以云端状态为准"

新增共享方法：

```kotlin
private suspend fun isTurnActiveOnServer(agentId: String, threadId: String): Boolean
```

- `startStreamingTimeoutWatchdog`：仅当云端确认 `turn_active == false` 才判超时；绝对上限放宽至 240 轮（4 小时）作为协程兜底。
- `resetStreamingWatchdog`：45 秒静默后先查云端，若仍在执行则**继续等待**而非谎报"连接中断"（上限 240 轮）。

> 设计取舍：宁可让用户按停止按钮主动取消，也不要谎报超时打断一轮正常的长工具调用。用户始终保有最终控制权。

## 5. 服务端侧遗留问题（本次未改，建议后续立项）

这些属于服务端/协议层，客户端无法修复：

1. **无回合级总时长上限**。`openai` 单请求可挂 600s，`Retry-After` 可再叠加 59s×N，
   且 harness `ModelRetryMiddleware` **零日志**（`logger` 出现 0 次），
   导致"上游排队"与"真实失败"在服务端日志中不可区分。
2. **`turn_active` 无兜底复位**。`mark_turn_idle()` 仅在 `ws_channel.py` 的 `finally` 中调用；
   轮次若因重试长期不结束，标记就一直挂着。建议增加基于 `last_activity` 的陈旧标记回收。
3. **工具失败不收敛**。26 次调用中 11 次重复读同一二进制、并反复撞同一 `PermissionError`，
   缺少"同一目标连续失败 N 次即终止该子任务"的护栏。

### 5.1 本次的服务端应急处置

已通过 WS 发送 `cancel` 帧（与 App 停止按钮同一路径）：

```
→ cancel frame sent
← steer.discarded / done
→ turn_active = False     ✅ 已复位
→ turn_active=true 的全库扫描：0 个
```

## 6. 验证与分发

- 编译：`./gradlew assembleDebug --offline` 通过，零报错；
- 版本：`v1.0.45`（VersionCode: 46）；
- 分发：Google Drive `我的云端硬盘/octop-apk/` + 家庭 NAS `/Volumes/tools/apk/`。

### 6.1 回归影响

| 改动 | 影响面 | 风险 |
| :--- | :--- | :--- |
| 新增帧分支 | 仅扩展认知范围，原分支未改动 | 低 |
| `else` 兜底日志 | 未知帧仅记录，不改变行为 | 低 |
| `handleWsTurnResumed` 重设 `_streamingThreads` | 服务端确认运行时使输入框进入流式语义 | 低；以服务端权威状态为准 |
| 看门狗以云端状态为准 | 长工具调用不再被误杀 | 低；保留 4 小时协程兜底 + 用户可主动停止 |
