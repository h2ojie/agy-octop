# Issue 006: 消息发送后一直卡在“正在思考中...”，刷新后消息丢失，发送按钮永久锁死

- **问题编号**：ISSUE-006
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `OctopWebSocketManager.kt`:
    - 支持后端 LangGraph 模型的 `reasoning` 与 `thinking` 双事件流帧解析；
    - 在 WebSocket 帧中完整附带标准 OpenAI 规范 `messages: [{"role": "user", "content": text}]`；
    - 增加断线指数退避自动重连机制（`reconnectRunnable`，2秒自动重连）；
    - `sendUserTurn` 与 `subscribe` 增加真实网络发送校验与返回值监听；
    - 增加 `cancelTurn` 支持发送 `{"type": "cancel", "thread_id": "..."}`。
  - `ChatViewModel.kt`:
    - 发送前前置检查 WebSocket 活跃度，断开时保护用户输入内容不丢失并即时重连；
    - 发送失败时不清空输入框，弹窗提示并重试重连；
    - 引入 60 秒全局流式响应 Watchdog 巡检保护与 45 秒 Token 流心跳续约，杜绝死锁与永远卡死；
    - 切换会话（Thread）时复用同专家已有长连接并调用 `subscribe`，消除高频重建连接带来的竞态与丢包；
    - 增加 `stopStreaming()` 允许用户手动中断生成；
    - 矫正预置 8 大专家的 ID 映射（如 `AZYKMJ` 为热点记者，`main` 为小通）。
  - `InputBar.kt` & `ChatScreen.kt`:
    - 在流式生成中（`isStreaming == true`）将发送按钮动态变换为红色圆角“停止生成（■）”按钮；
    - 允许用户在任何意外卡顿或冗长推理中随时手动一键中断。
  - `OctopApiService.kt`:
    - `ApiClient.api` 动态响应 `NetworkConfig.baseUrl` 变更。
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.5` (versionCode = 6)。
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

用户在与“热点记者”（Agent ID: `AZYKMJ`）对话时，反馈如下现象：
1. **消息发送后假死**：用户输入问题后点击发送，界面在用户消息下方出现一个 AI 气泡，内容一直停留在 `*正在思考中...*`，无任何流式内容输出；
2. **发送按钮锁死**：右下角发送按钮置灰禁用，无法再次输入或发送新消息；
3. **刷新后消息蒸发**：用户切换会话或重新进入该对话后，发现刚才发送的那条消息和 AI 的思考中气泡全都不见了，对话历史回退到了发送前的状态。

---

## 二、 原因分析 (Root Cause Analysis)

经过对服务端源码（`ws.py`, `models.py`）、本地 SQLite 数据库运行日志（`octop.log`）及 Python 自动化测试脚本的双向抓包定位，发现核心症结由以下 4 层原因叠加导致：

### 1. 服务端下发的思考推理事件类型为 `reasoning`，客户端漏解析
- 在 Octop 的 LangGraph 执行架构中，模型推理产生的实时思考增量帧为：
  ```json
  {"type": "reasoning", "content": "...", "node": "model", "thread_id": "..."}
  ```
- 客户端 `OctopWebSocketManager.kt` 的 `onMessage` 仅针对 `"thinking"` 进行了捕获，未识别 `"reasoning"`。
- **后果**：即使后端在飞速下发思考内容，客户端全部当做未知帧丢弃，`thinking` 累积字数始终为 0，导致 UI 永远呈现初始占位文案 `*正在思考中...*`。

### 2. WebSocket 意外断连静默失败，发送内容压根未上云
- 原 `sendUserTurn` 实现：
  ```kotlin
  fun sendUserTurn(text: String, threadId: String? = null) {
      webSocket?.send(payload.toString()) // 忽略了 Boolean 返回值！
  }
  ```
- OkHttp 的 `webSocket.send()` 在连接已关闭或队列溢出时直接返回 `false`。原代码完全丢弃了此返回值；
- 手机锁屏、网络切换或空闲超期（NAT 超时）后，WebSocket 已断开；
- 当用户点击发送时：
  1. `sendMessage()` 先盲目在本地内存追加 `userMsg` 和 `aiPlaceholder`；
  2. 清空输入框 `_inputText.value = ""`；
  3. 设置 `_isStreaming.value = true`（导致发送按钮禁用）；
  4. 调用 `sendUserTurn`，OkHttp 返回 `false`，但没人处理！
- **后果**：服务端根本没收到这个 Turn 请求，数据库里自然没有记录；客户端又在傻傻等待 `token` 或 `done`，导致永远处于“思考中”；刷新时客户端从服务端拉取历史记录，刚才只在内存中存在的临时气泡被覆盖，因此消息彻底“消失”。

### 3. 会话（Thread）切换时无节制暴力销毁连接引发的竞态
- 在 `selectAgent` 与 `loadThreadsForAgent` 中：
  - 选中专家时立即创建了一个 WebSocket（`threadId = null`）；
  - 紧接着几毫秒后异步 HTTP 拉取到该专家的活跃 Thread，立刻又调用了 `connectWs(agentId, thread.threadId)`；
  - `connectWs` 内部直接调用 `disconnect()` 掐断前一个正在握手或刚连上的连接；
  - 用户如果在此间隙输入发送，消息就直接掉入了断网的黑洞。

### 4. 缺乏流式看门狗与用户手动熔断机制
- 一旦后端异常、模型卡顿或网络丢包，客户端缺少超时自愈机制；
- 用户端没有“停止生成”按钮，无法人工打断挂起状态，导致整个聊天窗口彻底瘫痪。

---

## 三、 解决方案 (Fix & Implementation)

### 1. 深度适配思考事件类型并规范载荷
在 `OctopWebSocketManager.kt` 中：
- `onMessage` 支持 `"thinking"` 与 `"reasoning"` 双模式聚合触发；
- `sendUserTurn` 构建 payload 时同时填充 `text` 和标准的 OpenAI 结构 `messages: [{"role": "user", "content": text}]`，彻底兼容服务端 `UserTurnWsFrame.to_turn_body()`。

### 2. 健壮的发送保障与自动断线重连
- `OctopWebSocketManager` 增加 `isConnected` 暴露与 `reconnectRunnable` 调度（非正常断开后 2 秒自动重连）；
- `sendUserTurn` 返回布尔结果；
- 在 `ChatViewModel.sendMessage()` 执行前：
  - 若连接未建立：弹窗提示“正在连接服务，请稍候重试...”，触发连接，**保留用户已输入的内容不被清空**；
  - 若调用 `sendUserTurn` 返回 `false`：弹窗提示“发送失败，正在重新连接...”，触发重连，**同样保留用户输入**；
  - 只有在数据真正递交给底层 Socket 队列后，才清空输入框并展示本地乐观更新气泡。

### 3. 长连接复用与线程订阅
- 修改 `selectThread` 与 `loadThreadsForAgent`：
  - 同一专家下切换不同 Thread 时，不再暴力关闭重开 Socket，而是直接复用当前长连接并向服务端下发 `{"type": "subscribe", "thread_id": ...}`；
  - 只有在跨专家切换时才创建新连接，杜绝频繁断连引发的丢包竞态。

### 4. 引入看门狗超时巡检与“停止生成”按钮
- **Watchdog 巡检**：
  - 发送消息后开启 60 秒全局兜底计时器；
  - 在接收到每个 `token` / `reasoning` 时续约 45 秒；
  - 若发生超时，自动解锁 `isStreaming = false` 并提示超时，气泡替换为异常说明，允许用户继续操作；
- **停止生成按钮**：
  - 在 `InputBar.kt` 中，当 `isStreaming == true` 时，右侧按钮变换为红色停止方块（`Icons.Default.Stop`）；
  - 点击即可向服务端下发 `{"type": "cancel", "thread_id": ...}` 并立即释放客户端流式锁。

### 5. 校正默认专家 ID
- 矫正 `AZYKMJ` 映射为“热点记者”，`main` 为“小通 · 通用助手”，与线上 SQLite 数据库精准对齐。

---

## 四、 验证与效果 (Verification)

1. **思考过程实时渲染**：向热点记者发送消息后，不再干等“正在思考中...”，而是实时接收后端的 `reasoning` 过程并渲染在可折叠的卡片中；
2. **断网与失败保护**：在断网或 Socket 未就绪时，用户输入的文字始终保留在输入框中，不会再出现“发送后直接蒸发”的情况；
3. **支持一键停止**：如果思考时间较长或想更换提问，随时点击红色停止方块即可打断，发送按钮立即复原，交互顺畅无阻。
