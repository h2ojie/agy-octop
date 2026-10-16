# Issue 011: 会话级输入框草稿箱机制（Draft per Thread）与切会话软键盘自动收起

- **问题编号**：ISSUE-011
- **发现时间**：2026-10-15
- **修复时间**：2026-10-15
- **涉及模块**：
  - `app/build.gradle.kts`: 版本递增至 `v1.0.12` (versionCode = 13)；
  - `Message.kt`: `AttachmentItem` 增加 `targetConversationKey: String?`，实现待发附件精确绑定至具体会话；
  - `ChatViewModel.kt`:
    - 引入会话级草稿箱映射表 `_conversationDrafts` 与 `_conversationStagedAttachments`（Key 格式 `"$agentId:$threadId"`）；
    - 实现 `saveCurrentDraft()` 与 `restoreDraftFor()` 闭环，在 `selectAgent`、`selectThread`、`createNewThread` 时自动“保存当前草稿 -> 恢复目标会话草稿”；
    - `sendMessage()` 发送成功后自动清理对应会话的草稿记录；
    - `setInputText()` 实时双向同步至草稿缓存；
    - 上传完成回调精准写入对应会话的附件列表，杜绝跨会话串传或覆盖；
  - `ChatScreen.kt`:
    - 引入 `LocalFocusManager` 与 `LocalSoftwareKeyboardController`；
    - 在点击侧边栏菜单图标、选择专家、选择会话、新建会话时，主动调用 `focusManager.clearFocus()` 与 `keyboardController?.hide()`，使软键盘自动收起；
  - `InputBar.kt`:
    - 发送图标替换为官方无废弃的 `Icons.AutoMirrored.Filled.Send`。
- **状态**：已解决 (Resolved)

---

### 一、 问题背景与用户痛点

1. **草稿跨会话泄漏与误发风险**：
   用户在“通用助手”输入框输入文本（例如“哈哈哈”或长文本问题）后，通过左侧抽屉切换到“育儿管家”或其他专家对话，输入框内容依旧是刚才的文字，未发生任何改变或清空。若用户在另一个专家下误按发送，容易导致错误的上下文污染或隐私泄露。
2. **长文本思绪容易丢失**：
   若用户在会话 A 构思一段长文本，中途切换到会话 B 查询，若在会话 B 删改文字，会导致会话 A 的输入内容被覆盖永久丢失。
3. **软键盘悬浮遮挡新会话视野**：
   在输入文字状态下展开抽屉切换会话，软键盘始终悬浮不退，遮挡新会话的第一屏历史消息底部。

---

### 二、 修复方案与核心代码逻辑

1. **会话 Key 精准识别**：
   - 采用 `"$agentId:$threadId"` 作为唯一会话命名空间，开场白未发消息时采用 `"$agentId:draft_$agentId"`。
2. **实时暂存与无缝恢复**：
   - 切换会话时，自动执行 `saveCurrentDraft()` 暂存离开会话的文字和附件；
   - 根据目标会话 ID 执行 `restoreDraftFor()`，若目标会话有记忆草稿则直接恢复，若无则重置为干净空白状态；
   - 消息发送成功后自动从字典中清除该会话的暂存。
3. **软键盘生命周期智能管理**：
   - 展开抽屉或点击会话条目时，立即触发聚焦清理与键盘收起，保证页面场景切换时的清爽。

---

### 三、 验证结果

- `./gradlew assembleDebug` 构建通过。
- 切换专家或历史会话，输入框内容自动保存与隔离恢复；新会话呈现空白输入栏；切换瞬间软键盘平滑退下。
