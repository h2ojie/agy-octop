# Issue 028: 输入框底部模型切换与思考强度 (Reasoning Effort) 弹窗

## 1. 需求背景与目标
- 用户提出在输入框底部增加模型切换能力，样式与 Antigravity Web 版保持一致：
  1. 输入框底部胶囊展示当前模型名称（如 `Gemini 3.8 Flash`）；
  2. 点击胶囊弹出暗色风格的 Bottom Sheet（Material3 弹窗）；
  3. 列表中展示可用模型（分组为 "Model"），当前选中的模型右侧打勾 `✓`；
  4. 支持推理强度的模型（如 `Gemini 3.8 Flash`, `Claude Opus 5.5`, `GPT-6.1 Sol`, `Grok 4.7` 等）在右侧展示当前思考强度（如 `High`）以及右箭头 `>`；
  5. 点击支持推理强度的模型或右箭头可二级钻取至思考强度设置界面，支持切换选择 `Low`, `Medium`, `High`, `Extra High`；
  6. 底部不保留 "View Usage" 按钮；
  7. 切换模型和思考强度后，立即通过 REST API (`PATCH /api/agents/{agent_id}/threads/{thread_id}`) 更新当前会话 composer，并在 WebSocket 发送提问时携带 `model` 和 `reasoning_effort`。

## 2. 方案设计与数据建模

### 2.1 数据模型 (`ProviderModel.kt`)
- `ResolvedModel`:
  - `modelRef`: 模型唯一引用（例如 `cli/gemini-3.8-flash-high`, `ice/gpt-6.1-sol`, `cli/claude-opus-5-5`）；
  - `displayName`: 友好展示名称（例如 `Gemini 3.8 Flash`, `GPT-6.1 Sol`, `Claude Opus 5.5`）；
  - `reasoningConfig`: 推理配置元数据（是否支持 reasoning、当前 effort、支持的 effort 列表）；
  - `effortDisplayName`: 将 `low`/`medium`/`high`/`xhigh` 转换为 `Low`/`Medium`/`High`/`Extra High`。

### 2.2 接口与网络层支持
1. **获取模型列表** (`GET /api/providers/resolved`):
   - `OctopApiService.getResolvedModels()` 返回服务端当前所有已启用的可用模型。
2. **会话配置持久化** (`PATCH /api/agents/{agent_id}/threads/{thread_id}`):
   - `updateThreadComposer` 提交 `composer: { model_ref: "...", reasoning_effort: "..." }`，持久化至 Octop 服务端。
3. **实时提问透传** (`OctopWebSocketManager.sendUserTurn`):
   - 在 WebSocket 发送 `user_turn` 时携带 `"model"`, `"default_model"`, `"reasoning_effort"`，确保调度器准确指派目标大模型与思考深度。

### 2.3 状态管理与会话隔离 (`ChatViewModel.kt`)
- `_availableModels`: 缓存拉取到的可用模型列表（若离线或未拉取则使用预置模型兜底）；
- `_currentSelectedModel`: 当前会话选中的 `modelRef`；
- `_currentSelectedEffort`: 当前会话选中的思考强度；
- `selectModel(modelRef, effort)`: 更新内存状态，并在有激活 thread 时自动异步 PATCH 同步服务端；
- `restoreDraftFor(key)`: 切换会话时，自动从会话元数据或草稿池恢复该会话对应的模型与思考强度；
- 发送提问与 AI 占位符卡片自动打上当前选中的模型名称。

### 2.4 UI 交互与视觉规范 (`ModelSelectionBottomSheet.kt` + `InputBar.kt`)
- 完全遵照 Antigravity Web 暗色风格：
  - 容器背景 `Color(0xFF1E1F22)`，表面色彩 `Color(0xFF2B2D31)`；
  - 顶部分组标签 `"Model"`；
  - 列表项支持高亮态、选中勾选态 (`✓`)、思考强度胶囊（`High` 等）；
  - 二级视图提供返回按钮 `←`、模型名称副标题、思考强度列表 (`Low`, `Medium`, `High`, `Extra High`) 与勾选交互；
  - 输入框底部胶囊展示选中模型的精简名称，点击流畅唤起 ModalBottomSheet。

## 3. 验证与分发
- 构建版本：`v1.0.29` (`versionCode = 30`)。
- 编译通过 (`BUILD SUCCESSFUL in 1m 12s`)。
- APK 成功打包并分发至：
  - `/Volumes/tools/apk/octop-v1.0.29.apk`
  - `/Users/konglingling/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-apk/octop-v1.0.29.apk`
  - `./octop-v1.0.29.apk`
