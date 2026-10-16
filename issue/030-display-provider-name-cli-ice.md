# Issue 030: 支持模型服务商标识显示与消歧 (cli / ice)

## 1. 需求背景与痛点

### 1.1 痛点
- 系统中存在多个后端服务商（Provider），如 `cli` 与 `ice`，各自提供不同或同名的模型（如 `cli` 下有 `GPT-6 Astra`, `GPT-6 Luna`, `GPT-6.1 Sol`, `Gemini 3.8 Flash`, `Grok 4.7`, `Claude Opus 5.5`；`ice` 下同样有 `GPT-6 Astra`, `GPT-6 Luna`, `GPT-6.1 Sol`）；
- 在底部的模型选择弹窗中，之前仅展示了模型 displayName，导致出现重名的模型项（例如两个 `GPT-6 Luna`），用户无法区分哪一个是 `cli` 服务商，哪一个是 `ice` 服务商；
- 底部输入框胶囊与 AI 回复卡片底部此前也未明确标注模型所属服务商；
- 选中判定逻辑原先对不带 provider 前缀的 key 进行了弱匹配，容易造成同名模型在跨服务商时高亮异常。

### 1.2 目标效果
1. **模型切换弹窗（ModelSelectionBottomSheet）**：
   - 列表项中在模型名称右侧增加服务商标称微徽章（`[cli]` / `[ice]`），高质感微边框圆角样式，与 Antigravity 深色主题一致；
   - 思考强度选择二级视图标题栏中展示服务商标签；
   - 修复 `isSelected` 匹配算法：当 `selectedModelRef` 包含服务商路径时严格按照 `modelRef` 匹配，杜绝误选中。
2. **底部输入栏胶囊（InputBar）**：
   - 模型展示名称显示为 `服务商 / 模型名`（如 `ice / GPT-6 Luna` 或 `cli / Gemini 3.8 Flash`）。
3. **AI 回复卡片底部元数据（ChatBubble）**：
   - 底部元信息行格式化为 `服务商 / 模型名 · 生成时间`（如 `ice / GPT-6 Luna · 10月16日 17:39`）。

---

## 2. 核心架构与修改实现

### 2.1 服务商解析与格式化能力 (`ProviderModel.kt`)
- `ResolvedModel` 增加 `fullDisplayName` 计算属性：
  ```kotlin
  val fullDisplayName: String
      get() = if (providerName.isNotBlank()) "$providerName / $displayName" else displayName
  ```
- 增加 `extractProvider(raw: String): String`：
  - 支持从形如 `"cli/gemini-3.8-flash-high"`、`"ice / GPT-6 Luna"`、`"gpt-6-luna-cli"` 等多种格式中提取服务商代码。
- 增加 `formatModelWithProvider(raw: String, provider: String? = null): String`：
  - 自动识别并组合为 `"$prov / $name"`，若无服务商信息则安全降级为标准模型名。

### 2.2 模型选择弹窗体验重构 (`ModelSelectionBottomSheet.kt`)
- **模型列表行 (`ModelItemRow`)**：
  - 增加 `providerName` 微标签胶囊：未选中时采用优雅深灰半透明胶囊，选中时跟随主题主色高亮；
  - 修复 `isSelected` 精确匹配：
    ```kotlin
    val isSelected = if (selectedModelRef.contains("/")) {
        model.modelRef.equals(selectedModelRef, ignoreCase = true)
    } else {
        model.model.equals(selectedModelRef, ignoreCase = true)
    }
    ```
- **思考强度选择头部 (`EffortSelectionView`)**：
  - 增加服务商微标签，替换废弃的 `ArrowBack` 图标为 `Icons.AutoMirrored.Filled.ArrowBack`。

### 2.3 状态管理与全链路服务商联动 (`ChatViewModel.kt` & `ChatScreen.kt`)
- `ChatViewModel.resolveActiveModelDisplayName`:
  - 提取当前活跃模型的所属服务商并优先通过 `availableModels` 查询；
  - 统一调用 `formatModelWithProvider` 返回组合名称。
- `ChatViewModel.processDialogueHistory`:
  - 优先从 `composerContext` 或 `availableModels` 提取服务商，生成形如 `ice / GPT-6 Luna` 的持久化展示名称。
- `ChatScreen.kt`:
  - 输入框底部的 `currentModelName` 计算通过 `remember(currentSelectedModel, currentAgent, availableModels)` 并注入服务商前缀。

### 2.4 气泡卡片元信息呈现 (`ChatBubble.kt`)
- `AiResponseCard`:
  - `modelStr` 统一使用 `ResolvedModel.formatModelWithProvider(it)`；
  - 渲染结果为 `ice / GPT-6 Luna · 10月16日 17:39`。

---

## 3. 验证与分发
- 构建版本：`v1.0.31` (`versionCode = 32`)。
- APK 成功构建并通过分发。
