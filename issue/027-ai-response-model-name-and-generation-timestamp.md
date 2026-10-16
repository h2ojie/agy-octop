# Issue 027: AI 回复卡片底部展示模型名称与生成时间

## 1. 需求背景与目标
- 用户提出在 AI 回复卡片的最底部行（与复制按钮 `[❐]` 处于同一行）：
  1. 显示回答生成的时间，格式为月和日时间：`10月16日 14:34`；
  2. 调查数据层是否记录生成该回复的具体 Model，若有记录则一并在该行最左侧展示，格式为 `[模型名] · 10月16日 14:34`。

## 2. 数据层调研与定位
- **SQLite 数据库 (`thread_messages`)**：
  - `role = 'ai'` 的消息，其 `message_json` 字段内保存完整的 LangChain 结构，其中 `response_metadata.model_name` 字段完整记录了生成该回复的具体模型（例如 `gemini-3.8-flash-n`, `gpt-6.1-sol`, `gpt-6-astra`, `grok-4.7-build`, `claude-opus-5-5` 等）。
- **服务端接口 (`_serialize_history_message`)**：
  - 补充 `model_name` 投影输出：`entry["model_name"] = str(model_name)`。
- **Android 端 (`HistoryMessageDto` / `ChatMessage`)**：
  - `HistoryMessageDto` 增加 `@SerializedName("model_name") val modelName: String?` 与 `@SerializedName("model") val model: String?` 接收。
  - `ChatMessage` 增加 `val modelName: String? = null` 属性。

## 3. 核心改动

### 3.1 数据解析与模型归属对齐 (`ChatViewModel.kt`)
1. **历史记录对齐**：
   - 在 `processDialogueHistory` 中从当前轮次 AI DTO 中提取 `modelName ?: model`，若缺失则降级使用管家默认模型 `_currentAgent.value?.defaultModel?.substringAfterLast("/")`。
   - 时间戳规范化为毫秒（支持秒级与毫秒级时间戳自适应）：`if (rawTs < 10_000_000_000L) rawTs * 1000L else rawTs`。
2. **实时流式与占位符对齐**：
   - 发送时 `executeSendTurn` 为 AI 占位符打上当前管家默认模型 `agent.defaultModel?.substringAfterLast("/")` 与当前时间戳。
   - `handleWsDone` 在流式接收完毕时校验补充 `modelName`。

### 3.2 UI 底部快捷栏排版 (`ChatBubble.kt`)
- 在 `AiResponseCard` 底部快捷栏：
  - 将 `Row` 改为 `Arrangement.SpaceBetween` 两端对齐；
  - 左侧渲染 `Text("${modelName} · ${formatMessageTime(timestamp)}")`，采用 `11.5.sp` 小字号、`onSurfaceVariant.copy(alpha = 0.55f)`，单行省略；
  - 右侧保留 32dp 复制按钮 `[❐]`。
- 增加格式化工具函数 `formatMessageTime(timestamp: Long): String`，格式输出为 `M月d日 HH:mm`（例如 `10月16日 14:34`）。

## 4. 验证与分发
- 构建版本：`v1.0.28` (`versionCode = 29`)。
- APK 成功打包并分发至：
  - `/Volumes/tools/apk/octop-v1.0.28.apk`
  - `/Users/konglingling/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-apk/octop-v1.0.28.apk`
  - `./octop-v1.0.28.apk`
