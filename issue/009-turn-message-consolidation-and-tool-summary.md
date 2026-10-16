# Issue 009: 对话历史与网页端对不上（多步工具调用的中间回复分散展示为多条重复气泡，缺乏工具调用统计）

- **问题编号**：ISSUE-009
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `ChatViewModel.kt`:
    - 重构 `processDialogueHistory`，实现与 Web 前端（`groupConsecutiveAssistantMessages` + `splitAssistantTurn`）严格对齐的**轮次聚合（Turn Grouping）**算法；
    - 从后往前检索最后一条有效文本消息作为该轮智能体的权威最终回复，过滤并收拢中间过渡步骤文本；
    - 统计划分整轮调用的工具总次数（`toolCallCount`）并合并所有阶段的心智链；
    - 接入实时 WebSocket `onToolCall` 帧事件驱动，在流式生成期间实时反馈已调用工具计数；
    - 优化 `loadHistory` 流式暂存消息保护，防止重复追加已入库的本轮消息。
  - `Message.kt`:
    - `ChatMessage` 数据模型扩展 `toolCount: Int = 0` 字段。
  - `ChatBubble.kt`:
    - `ThinkingBlock` 折叠卡片全面支持工具调用次数与思考字数综合摘要（如 `🛠️ 已调用 11 次工具 · 💭 已深度思考 (821 字)`），点击一键展开/折叠完整思考链，实现与 Web 控制台进程摘要栏（`TurnProcessBlocks`）的信息架构统一。
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.8` (versionCode = 9)。
- **状态**：已解决 (Resolved)

---

## 一、 问题现象 (Issue Phenomenon)

用户在使用 Android 客户端与“育儿管家”等具备复杂工具调用能力的专家对话时，发现移动端展现与 Web 网页端明显对不上：
- **Web 端呈现**：
  - 用户提问：`带小孩我不相信有比哄睡还难的事了。灯灯哄了 40 分钟，一直翻来覆去，说头痒。`
  - 进程摘要：`已调用 11 次工具，3 次深度思考 >`（可折叠查看工具流水与思考）
  - 智能体气泡：**仅有 1 条**完整的最终答复（结合了医学来源、护理建议与后续核对）。
- **Android 客户端呈现**：
  - 用户提问下方连续弹出了 **3 个独立的 AI 气泡**：
    - **气泡 1**：显示 `[已深度思考 (402 字)]`，正文为调工具前的过渡语（“哄了40分钟...我查一下今晚怎么帮他缓解，也核对前两天的瘙痒记录”）；
    - **气泡 2**：显示 `[已深度思考 (417 字)]`，正文为第一批工具调用后的部分结论（“信息来源：NHS...现在头皮看着有什么变化？”）；
    - **气泡 3**：显示 `[已深度思考 (821 字)]`，正文为包含前述气泡 1 + 气泡 2 拼接而成的**完整答复**。
  - 结果：相同的内容在界面上重复渲染了 3 次，且完全没有工具调用的数量概览，界面繁冗错乱。

---

## 二、 根因分析 (Root Cause Analysis)

### 1. LangGraph 检查点多步数据落库机制
在智能体执行包含工具调用的长链路任务时，服务端的 `thread_messages` 表会顺序持久化执行过程中的所有动作：
- `seq = 37`: `human` 用户提问
- `seq = 38`: `ai` 包含第一阶段思考（404 字节）及中间过渡语，附带 `tool_calls`
- `seq = 39~52`: `tool` 工具执行（`current_time`、`grep`、`web_search`、`read`、`edit_file` 等 11 次工具交互）及中间思考（`seq = 44` 419 字节）
- `seq = 53`: `ai` 工具执行完毕后生成的主体答复
- `seq = 54`: `ai` 最终综合检查点消息，其 `content` 整合了本轮的全部文本（445 字符），其 `reasoning_content` 整合了全部阶段的思考（823 字符）

### 2. Web 前端机制：智能体轮次折叠（Assistant Turn Grouping）
在 Web 控制台源码（`splitAssistantTurn`）中：
- 将一个用户提问后跟随的所有 `ai` 和 `tool` 消息归入同一个 `AssistantTurn` 分组；
- **从后往前（Reverse Scan）检索**：以找到的第一条非空文本消息（即 `seq = 54`）作为唯一的 `answerMessage` 渲染为聊天气泡；
- 前面的所有中间步骤（`seq = 38~53`）全部收拢到进程流水中，并在顶部汇聚为一行简洁的状态栏：`已调用 11 次工具，3 次深度思考 >`。

### 3. Android 端的缺陷：无轮次概念，逐条实例化气泡
在原 `processDialogueHistory` 中：
```kotlin
// 旧逻辑：遍历每一条消息，只要不是 user 且 textContent 不为空就 new ChatMessage
if (textContent.isNotBlank()) {
    dialogue.add(ChatMessage(..., content = textContent, thinking = combinedThinking))
    currentTurnThinking.clear()
}
```
遍历到 `seq = 38` 时生成了气泡 1；遍历到 `seq = 53` 时生成了气泡 2；遍历到 `seq = 54` 时生成了气泡 3。导致过渡草稿、阶段输出与最终成品全部被展示在屏幕上。

---

## 三、 解决方案 (Solution Implementation)

### 1. 重构历史解析：轮次聚合算法 (`ChatViewModel.kt`)
与 Web 端逻辑完全对齐，构建 `flushAssistantTurn` 聚合器：
```kotlin
private fun processDialogueHistory(dtos: List<HistoryMessageDto>): List<ChatMessage> {
    val dialogue = mutableListOf<ChatMessage>()
    val currentTurnAiMessages = mutableListOf<HistoryMessageDto>()

    fun flushAssistantTurn() {
        if (currentTurnAiMessages.isEmpty()) return

        // 1. 从后往前找到最后一条有实质文本内容的消息作为权威最终回复
        var answerDto: HistoryMessageDto? = null
        var answerText = ""
        var answerThinking = ""
        for (i in currentTurnAiMessages.indices.reversed()) {
            val (text, th) = MessageParser.parse(currentTurnAiMessages[i].content, currentTurnAiMessages[i].thinking)
            if (text.isNotBlank()) {
                answerDto = currentTurnAiMessages[i]
                answerText = text
                answerThinking = th
                break
            }
        }

        // 2. 统计整轮所有步骤的工具调用次数并收集思考链
        val turnThinkingParts = mutableListOf<String>()
        var toolCallCount = 0
        for (dto in currentTurnAiMessages) {
            val role = (dto.role ?: "").lowercase()
            if (role == "tool") toolCallCount++
            // 扫描 content 中的 tool_use block
            // ...
            val (_, th) = MessageParser.parse(dto.content, dto.thinking)
            if (th.isNotBlank() && !turnThinkingParts.contains(th)) {
                turnThinkingParts.add(th)
            }
        }

        // 3. 合并全轮思考链
        val finalThinking = when {
            answerThinking.isNotBlank() && (turnThinkingParts.isEmpty() || turnThinkingParts.all { answerThinking.contains(it) }) ->
                answerThinking
            turnThinkingParts.isNotEmpty() ->
                turnThinkingParts.joinToString("\n\n---\n\n").trim()
            else -> answerThinking
        }

        // 4. 每轮只发射一条唯一的最终 AI 气泡
        if (answerDto != null && answerText.isNotBlank()) {
            dialogue.add(
                ChatMessage(
                    id = answerDto.id ?: UUID.randomUUID().toString(),
                    isUser = false,
                    content = answerText,
                    thinking = finalThinking,
                    isThinkingExpanded = false,
                    toolCount = toolCallCount,
                    timestamp = ...
                )
            )
        }
        currentTurnAiMessages.clear()
    }

    for (dto in dtos) {
        if (dto.role == "user") {
            flushAssistantTurn() // 结算上一轮
            dialogue.add(userMessage)
        } else {
            currentTurnAiMessages.add(dto)
        }
    }
    flushAssistantTurn() // 结算最后一轮
    return dialogue
}
```

### 2. 气泡视觉对齐：工具调用与心智链双重摘要 (`ChatBubble.kt`)
在 `ThinkingBlock` 卡片头部增加工具调用指标：
```kotlin
val title = when {
    isStreaming && toolCount > 0 -> "🛠️ 已调用 $toolCount 次工具 · 💭 正在深度思考..."
    isStreaming -> "💭 正在深度思考..."
    toolCount > 0 && wordCount > 0 -> "🛠️ 已调用 $toolCount 次工具 · 💭 已深度思考 ($wordCount 字)"
    toolCount > 0 -> "🛠️ 已调用 $toolCount 次工具"
    else -> "💭 已深度思考 ($wordCount 字)"
}
```
- 折叠时：单行紧凑展现，如 `🛠️ 已调用 11 次工具 · 💭 已深度思考 (821 字) ˇ`；
- 展开时：展开阅读完整的 821 字多阶段思考过程；
- 正文：直接呈现唯一的最终权威答复，消除所有中间碎片的重复干扰。

---

## 四、 验证与效果 (Verification)

1. **实际会话校验**：
   - 载入同一会话 `thr_01M4X8ABNC9JHK974NTH9TSX4E`（“儿子今晚回来，忽然给我个玩具...”）；
   - 用户提问第 3 轮（“带小孩我不相信有比哄睡还难的事了...”）原本堆叠的 3 个气泡，现聚合为**唯一的 1 个 AI 气泡**；
   - 顶部折叠栏显示：`🛠️ 已调用 11 次工具 · 💭 已深度思考 (821 字) ˇ`；
   - 正文完整显示最终结论，中间过渡碎片全部自动隐去，与 Web 网页端完全一致。
2. **构建与部署**：
   - 版本递增至 `v1.0.8` (versionCode = 9)；
   - 部署至 `/Volumes/tools/apk/octop-v1.0.8.apk`、`/Volumes/tools/apk/octop-v1.0.0.apk` 及远程服务器 `http://h2ojie2026.duckdns.org/octop.apk`。
