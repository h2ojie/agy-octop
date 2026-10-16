# Issue 013: Antigravity 网页端风格聊天卡片吸顶折叠与自适应增高输入框

## 1. 背景与用户反馈

用户体验了 Google Antigravity 网页端（移动端适配）后提出界面升级需求：
1. **用户提问卡片化**：用户发送的消息置于现代质感的独立圆角卡片中（深色模式下为 `#1E1F20` 深灰微高亮底色，带细边框）。
2. **滚动吸顶与折叠**：当用户向上滑动浏览 AI 长篇回复时，当轮提问卡片自动吸附置顶于顶部（Sticky Header），并从多行完整模式平滑缩成单行（超出部分省略号截断并带轻微展开图标）。
3. **点击交互展开/折叠**：点击吸顶卡片即可展开完整提问文本与附件，再次点击重新收拢至单行。
4. **AI 回复全宽纯净布局**：去除 AI 外层笨重的灰底对话泡，直接以全宽 Markdown 呈现，并配备折叠思考摘要、HITL 审批卡片、附件与底部复制操作栏。
5. **输入框自适应延展与移除全屏弹窗**：底部输入容器对齐 Antigravity，打字时随内容由 1 行平滑扩展至 8 行（超出后内部滚动）；左下角保留 `+` 附件按钮与专家 Badge，右下角为圆形发送/停止按钮；彻底移除冗余的“点击全屏输入弹窗”。

---

## 2. 根因与架构设计

### 2.1 消息重组为 Turn 轮次结构
原先消息以纯扁平 `List<ChatMessage>` 渲染在 `LazyColumn` 中，用户消息与 AI 消息割裂，无法实现吸顶与关联。
- 引入 `ChatTurn(id, userMessage, aiMessages)` 数据结构。
- 编写 `groupMessagesIntoTurns(messages)`，将连续的用户提问与其后紧随的 AI 回复聚合为一个轮次。

### 2.2 Jetpack Compose 吸顶头 (`stickyHeader`) 与粘性状态判定
- 在 `LazyColumn` 中，针对每个轮次的 `userMessage` 声明 `stickyHeader(key = "turn_${turn.id}_user")`。
- 预计算每个轮次在 `LazyColumn` 中的绝对 Item 索引 `turnHeaderIndices`。
- 吸顶状态实时计算：
  $$isSticky = headerIndex != null \land firstVisibleItemIndex > headerIndex \land (nextHeaderIndex == null \lor firstVisibleItemIndex < nextHeaderIndex)$$
- 当 `isSticky == true` 时，默认状态下卡片呈现单行模式（`isCollapsed = true`），并显示展开箭头。用户点击后记录 `manualExpandedTurnId`，可在展开与折叠间无缝切换。
- **防止文字透出的遮罩设计**：在 `stickyHeader` 容器外层使用 `background(MaterialTheme.colorScheme.background)` 铺满全宽，彻底遮蔽向上滚动的 AI 文本，杜绝重叠与残影。

### 2.3 输入框 (`InputBar`) 质感对齐与全屏弹窗移除
- 外层容器采用 `RoundedCornerShape(22.dp)` 与 `#1E1F20` 质感配色。
- `BasicTextField` 配置 `minLines = 1, maxLines = 8`，并配合 `animateContentSize()` 实现自然高度延展。
- 下方 Action Row 左侧圆形 `+`（附件菜单与专家 Badge），右侧 Send/Stop 按钮。
- 完全剔除 `FullscreenInputModal` 与对应触发逻辑。
- 在页面中新增右下角/底部中央的快速回到底部浮动按钮（FAB），与 Antigravity Web 体验对齐。

---

## 3. 变更清单

1. `app/src/main/java/com/octop/client/ui/components/InputBar.kt`:
   - 重构为 Antigravity 质感圆角卡片，支持 1~8 行打字自增高与内部平滑滚动。
   - 左下角放置圆钮 `+` 与当前专家状态 Pill，右下角放置圆形发送/终止操作。
   - 移除 `onOpenFullscreen` 回调。

2. `app/src/main/java/com/octop/client/ui/components/ChatBubble.kt`:
   - 新增 `ChatTurn` 与 `groupMessagesIntoTurns` 聚合工具。
   - 实现 `UserPromptCard`：圆角卡片、单行折叠/多行展开、附件列表渲染、展开/收拢图标提示。
   - 实现 `AiResponseCard`：去泡化全宽纯净排版、思考与工具卡片折叠、HITL 审批卡片、富文本 Markdown 渲染、底部快捷复制按钮。
   - 保留 `ChatBubble` 兼容适配入口。

3. `app/src/main/java/com/octop/client/ui/ChatScreen.kt`:
   - 引入 `groupMessagesIntoTurns` 与 `turnHeaderIndices` 索引映射。
   - `LazyColumn` 改用 `stickyHeader` 承载各轮次用户卡片，`items` 承载 AI 回复。
   - 支持点击卡片展开/收拢 `manualExpandedTurnId`。
   - 添加快速滚动到底部浮动按钮。
   - 移除 `FullscreenInputModal` 及其相关代码。

4. `app/build.gradle.kts`:
   - 版本号升级至 `versionCode 15`, `versionName "1.0.14"`。

---

## 4. 验证结果

- `./gradlew assembleDebug` 构建通过。
- 编译产物打包归档并同步至本地、内网及 Google Drive，代码同步至 GitHub `jieiceone/octop-android`。
