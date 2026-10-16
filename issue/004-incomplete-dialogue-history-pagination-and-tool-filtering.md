# Issue 004: 对话历史展示不全（用户输入丢失、出现多张空白思考卡片）

- **问题编号**：ISSUE-004
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `OctopApiService.kt`: `getThreadHistory` 添加 `limit` 与 `offset` 查询参数（默认 200）
  - `Message.kt`: `HistoryMessageDto` / `HistoryResponse` / `ChatMessage` 结构扩展
  - `ChatViewModel.kt`: 完善历史分页拉取 `loadHistory` 与对话轮次合并算法 `processDialogueHistory`
  - `ChatBubble.kt`: 优化思考链与正文内容解析
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.3` (versionCode = 4)
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

用户在 Android 客户端查看与专家（例如“我的持仓专家” `70P5RA`）的已有会话时：
1. **用户输入内容完全不显示**：页面上只有最后的 AI 最终答复，网页端中用户提问的内容（如“为什么新的对话还是没有生成时自动命名”）在移动端消失不见；
2. **多张无正文的空白“已深度思考”卡片堆叠**：在 AI 回复前，连续渲染了 8 张带有“已深度思考 (xxx字)”但正文为空的气泡卡片；
3. **对话历史被截断**：长会话或经历过多步工具调用的会话，更早的轮次无法呈现。

---

## 二、 原因分析 (Root Cause Analysis)

通过深入检查服务端 SQLite 数据库（`thread_messages` 表）、API 实现及 Web 控制台前端处理逻辑，定位到以下两个核心根因：

### 1. 后端接口默认分页大小限制（Pagination Limit = 25）
- 后端接口 `GET /api/agents/{agent_id}/threads/{thread_id}/history` 默认取 `limit = 25` 条消息（上限为 200 条），查询方式为 `ORDER BY seq DESC LIMIT limit OFFSET offset`；
- 在 LangGraph 智能体复杂的执行过程中，单轮提问可能触发数十次甚至上百次工具调用（`tool_use` 与 `tool_result`）。以会话 `thr_01M4QZABGRMR1H6EWDVWCRV63Q` 为例，用户提问在 `seq = 179`，AI 最终答复在 `seq = 286`，中间经历了 106 条工具调用和中间思考记录，全表总消息量达 286 条；
- Android 客户端请求时未指定 `limit`，导致后端仅返回最新的 25 条（`seq = 262 ~ 286`）。用户的提问（`seq = 179`）直接被分页阶段完全截断，根本未进入前端数据层。

### 2. 中间工具调用的思考记录被错误实例化为独立气泡
- 在中间的工具调用步骤中，智能体会产生中间推理文本（`reasoning_content`），但正文 `content` 为空（或仅为 `tool_use`）；
- 移动端原有的解析逻辑只要检测到 `thinkingContent.isNotBlank()`，就会为该消息实例化一个独立的 `ChatMessage`；
- 导致中间 8 次工具调用的思考过程被当作 8 条独立的 AI 消息展示，形成了 8 个空白正文的思考卡片。

### 3. Web 端对齐逻辑
- 在 Web 控制台源码（`filterDialogueHistoryMessages` 与 `convertCallEntries`）中：
  - 纯工具调用步骤（`tool_use` / `tool_result`）会被合并或过滤，不作为独立对话气泡展示；
  - 只有包含真实对话正文（`hasDialogueContent`）或用户输入的消息才会作为对话项展示。

---

## 三、 解决方案 (Fix & Implementation)

### 1. 扩充 API 分页能力 (`OctopApiService.kt`)
Retrofit 接口中引入 `limit: Int = 200`（后端允许的最大单次 limit）与 `offset: Int = 0` 参数：
```kotlin
@GET("api/agents/{agent_id}/threads/{thread_id}/history")
suspend fun getThreadHistory(
    @Path("agent_id") agentId: String,
    @Path("thread_id") threadId: String,
    @Query("limit") limit: Int = 200,
    @Query("offset") offset: Int = 0
): Response<HistoryResponse>
```

### 2. 多页历史自动加载 (`ChatViewModel.kt`)
在 `loadHistory` 中支持分页循环（最多 5 页 / 1000 条底层记录），并将更早的分页数据正确前置拼接（`allRawPages.add(0, dtos)`），还原完整的时间序列：
```kotlin
val allRawPages = mutableListOf<List<HistoryMessageDto>>()
var currentOffset = 0
val pageSize = 200
var hasMore = true
var iterations = 0
val maxIterations = 5

while (hasMore && iterations < maxIterations) {
    val resp = ApiClient.api.getThreadHistory(
        agentId = agentId,
        threadId = threadId,
        limit = pageSize,
        offset = currentOffset
    )
    if (!resp.isSuccessful) break
    val body = resp.body() ?: break
    val dtos = body.messages ?: body.items ?: emptyList()
    if (dtos.isEmpty()) break

    allRawPages.add(0, dtos)
    hasMore = body.has_more
    currentOffset += pageSize
    iterations++
}
```

### 3. 智能轮次与思考链合并算法 (`processDialogueHistory`)
重构会话历史转换逻辑：
- **用户消息 (`role == "user" || role == "human"`)**：清空上一轮暂存思考，生成用户气泡；
- **智能体步骤 (`role == "assistant" || role == "ai"`)**：
  - 若包含 `thinkingContent`，累积到当前轮次的思考缓冲器 `currentTurnThinking`；
  - 若包含非空 `textContent`（最终答复），将缓冲器中累积的思考链合并为该回复的 `thinking` 字段，并生成单条完整的 AI 回复气泡；
- **纯工具步骤 (`role == "tool"`)**：忽略，避免在对话主屏产生噪音气泡；
- **历史思考卡片默认折叠**：`isThinkingExpanded = false`，保证主屏对话内容紧凑清晰，用户点击后方展开查看完整心智链。

---

## 四、 验证与效果 (Verification)

1. 会话 `thr_01M4QZABGRMR1H6EWDVWCRV63Q` 共 286 条底层记录，经过新算法处理后，干净整洁地还原为 4 条标准对话：
   - 轮次 1：用户首个提问 -> 专家首个答复（内嵌 19358 字完整思考链，默认折叠）
   - 轮次 2：用户追问（“为什么新的对话还是没有生成时自动命名”）-> 专家最终答复（内嵌 17160 字思考链，默认折叠）
2. 彻底消除了 8 张空白思考卡片堆叠的异常视觉；
3. 用户提问与其对应的 AI 答复按时间自然成对呈现，与网页端完全一致。
