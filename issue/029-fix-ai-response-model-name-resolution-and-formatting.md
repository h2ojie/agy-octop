# Issue 029: 修复 AI 回复卡片模型名称归属与格式化解析错误

## 1. 现象与原因深度排查

### 1.1 异常现象
- 用户在聊天界面将模型切换为 `GPT-6 Luna` 并发送消息；
- 后端与 Web 端均正确调度并显示 `gpt-6-luna`；
- 但 Android 客户端在生成及刷新历史后，AI 回复卡片底部错误显示为：
  `gemini-3.8-flash-high · 10月16日 17:39`。

### 1.2 根因定位与链路分析
1. **历史数据解析级联降级缺陷 (`processDialogueHistory`)**：
   - 当服务端返回的单个 AI 消息缺少 `model_name`（或旧服务进程尚未重载投影输出）时，原逻辑直接回退为智能体的默认模型：`_currentAgent.value?.defaultModel?.substringAfterLast("/")`（即 `gemini-3.8-flash-high`）。
   - 忽略了关键上下文：
     - 当轮用户消息中携带的 `composer_context`（明确记录了该轮用户指定的 `model: "ice/gpt-6-luna"`）；
     - 当前会话的持久化配置 `thread.model_ref`（记录了 `ice/gpt-6-luna`）；
     - 当前会话选中的内存配置 `_threadSelectedModel[key]`。
2. **原始模型标识未做规范格式化**：
   - 之前直接使用 `substringAfterLast("/")` 截取字符串，导致呈现的是技术性底层 ID（如 `gemini-3.8-flash-high`），而非 Antigravity 风格的友好展示名（如 `Gemini 3.8 Flash`、`GPT-6 Luna`）。
3. **流式占位符与结束对齐弱一致性**：
   - 在 `handleWsToken`、`handleWsThinking`、`handleWsDone` 及流式拉取历史时，未统一使用会话上下文解析器，导致本地内存中的模型展示名称被误覆盖。

---

## 2. 核心改动

### 2.1 增加用户轮次模型上下文解析 (`Message.kt`)
- `HistoryMessageDto` 增加 `@SerializedName("composer_context") val composerContext: Map<String, Any?>? = null`；
- 服务端投影在用户消息上的 `composer_context: {"model": "ice/gpt-6-luna"}` 能够被完整反序列化。

### 2.2 强化全链路模型层级解析算法 (`ChatViewModel.kt`)
1. **新增 `resolveActiveModelDisplayName(agentId, threadId)` 统一调度器**：
   - 判定优先级：
     `当前会话选定模型 (_threadSelectedModel) > 激活状态模型 (_currentSelectedModel) > 会话配置 (thread.modelRef) > 智能体默认模型 (agent.defaultModel)`
   - 统一通过 `ResolvedModel.formatModelName(...)` 格式化为标准展示名称。
2. **重构 `processDialogueHistory` 历史轮次装配器**：
   - 引入 `currentTurnUserDto` 追踪当前轮次的用户提问 DTO；
   - 解析优先级：
     `AI 响应元数据 (dto.modelName ?: dto.model) > 用户发送时的模型上下文 (currentTurnUserDto.composerContext.model) > 会话选定模型 (_threadSelectedModel) > 会话配置 (thread.modelRef) > 智能体默认模型`；
   - 彻底杜绝误降级为智能体默认模型的问题。
3. **修复流式与断线接力占位符**：
   - `handleWsToken`、`handleWsThinking`、`handleWsDone`、`executeSendTurn` 统一接入 `resolveActiveModelDisplayName`，保证流式生成中和生成完毕后模型名称始终与选中配置严格一致。

### 2.3 健壮化模型格式化与渲染 (`ProviderModel.kt` + `ChatBubble.kt`)
- `ResolvedModel.formatModelName` 增强为大小写不敏感且自幂等：
  - 支持 `gpt-6-luna`、`ice/gpt-6-luna`、`GPT-6 Luna` 统一标准化为 `GPT-6 Luna`；
  - 支持 `gemini-3.8-flash-high`、`cli/gemini-3.8-flash-high` 统一标准化为 `Gemini 3.8 Flash`。
- `ChatBubble.kt` 在底部展示前增加幂等格式化与 `remember` 优化，无论本地历史残留何种底层字符串，均渲染为清爽高质感的模型名称。

---

## 3. 验证与分发
- 构建版本：`v1.0.30` (`versionCode = 31`)。
- 编译通过 (`BUILD SUCCESSFUL in 46s`)。
- APK 成功打包并分发至：
  - `/Volumes/tools/apk/octop-v1.0.30.apk`
  - `/Users/konglingling/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-apk/octop-v1.0.30.apk`
  - `./octop-v1.0.30.apk`
