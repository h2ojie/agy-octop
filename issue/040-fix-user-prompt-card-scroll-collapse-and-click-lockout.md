# Issue 040: 修复用户消息框上下滑动自动收起及收起后点击无法重新展开的致命冲突

## 1. 现象与根本原因分析

### 1.1 业务现象
- **问题 1（滑动自动收起）**：用户在聊天界面点击展开提问卡片后，在屏幕上上下滑动浏览消息（“一直往上滑”），提问卡片突然毫无预警地自动缩回为单行折叠模式；
- **问题 2（收起后点击无响应锁定）**：在发生意外收拢后，用户连续轻触点击该折叠单行消息框，卡片完全失去响应，无论点击多少次都无法重新展开（“收起来之后再点就一直点不开”）。

### 1.2 根本原因诊断
从用户上传的最新诊断日志 `octop_20261023.log`（OPPO PHY120, Android 16 / SDK 36）中提取到了关键执行轨迹：
```
[2026-10-23 18:42:37.383] [DEBUG] [UI] LazyColumn touch dragged (outside prompt card), auto-collapsing prompt card: dbfc0fc5-211c-4ab9-ba41-697559595c43
[2026-10-23 18:42:38.116] [DEBUG] [UI] Prompt card toggled for turn: dbfc0fc5-211c-4ab9-ba41-697559595c43, expanding=true
[2026-10-23 18:42:39.158] [DEBUG] [UI] LazyColumn touch dragged (outside prompt card), auto-collapsing prompt card: dbfc0fc5-211c-4ab9-ba41-697559595c43
[2026-10-23 18:42:39.793] [DEBUG] [UI] Prompt card toggled for turn: dbfc0fc5-211c-4ab9-ba41-697559595c43, expanding=true
[2026-10-23 18:42:40.789] [DEBUG] [UI] LazyColumn touch dragged (outside prompt card), auto-collapsing prompt card: dbfc0fc5-211c-4ab9-ba41-697559595c43
```
随后用户多次点击该卡片，日志中 `Prompt card toggled` 记录为 0，彻底失去点击事件。深度分析代码揭示了三大根因：

1. **强行在列表拖拽时收拢状态的伪需求悖论**：
   - `ChatScreen.kt` 中挂载了 `val isListDragged by listState.interactionSource.collectIsDraggedAsState()`，并在 `LaunchedEffect(isListDragged)` 中执行 `activeExpandedTurnId = null`。
   - 当用户在聊天界面上下滑动阅读会话上下文时，只要手指拖动列表产生滚动，卡片就会被立即强制折叠。这种“滑动列表即自动收拢”完全违反了用户“展开后对比阅读”的预期，直接导致了“滑着滑着它自己收起来了”。
2. **底层 AI 消息卡片全屏点击拦截器劫持与穿透冲突**：
   - 在 `LazyColumn` 的 `items(turn.aiMessages)` 外层包裹了一个全宽的 `Box.clickable { if (activeExpandedTurnId != null) activeExpandedTurnId = null }`；底部视口缓冲 Spacer 也绑定了相同的全局点击清空逻辑。
   - 当列表向上滑动时，下方的 AI 回复卡片滑动到 `stickyHeader` 吸顶区域下方。用户点击置顶栏边缘或卡片间隙时，点击事件穿透到下层被 AI 卡片容器劫持，导致无论怎么点都被强行重置为 `null`；
   - 此外，`combinedClickable` 内部存在长按判定（400ms 延迟）以及与滚动容器的滑动摩擦竞争，手指轻触抬起过程只要伴随 1~2 像素抖动，手势即被判定为取消。
3. **吸顶容器缺少兜底全域点击监听**：
   - 置顶 Header 外层的 `Box` 存在 `padding(horizontal = 12.dp, vertical = 4.dp)`。若用户轻触在卡片外边距范围内，由于外层 Box 未挂载折叠点击，点击事件无法触发展开。

---

## 2. 解决方案与重构落地

### 2.1 彻底移除滑动强制折叠逻辑 (`ChatScreen.kt`)
- 废除 `val isListDragged by listState.interactionSource.collectIsDraggedAsState()` 及对应的 `LaunchedEffect(isListDragged)`。
- **确立全新交互标准**：
  - 用户手动展开提问卡片后，在列表中自由上下滑动、翻阅上下文、浏览工具调用等行为均**绝不改变卡片展开状态**；
  - 提问卡片始终保持用户期望的视图状态，直至用户主动点击“点击收拢”/折叠指示图标，或切换到其他对话会话时才归零。

### 2.2 清除所有下层点击劫持包裹层 (`ChatScreen.kt`)
- 彻底移除 `AiResponseCard` 外层的 `Box.clickable { activeExpandedTurnId = null }` 包裹层，直接平铺 `AiResponseCard`，释放 AI 卡片内链接、代码块复制与微交互；
- 移除 `bottom_viewport_spacer` 的 `clickable` 拦截器，根除背景图层冲突与穿透劫持。

### 2.3 构建立体无死角点击响应链路 (`ChatBubble.kt` & `ChatScreen.kt`)
1. **外层吸顶容器兜底展开**：
   - 在 `stickyHeader` 的外层 `Box` 上增加折叠态直接点击：
   ```kotlin
   Box(
       modifier = Modifier
           .fillMaxWidth()
           .background(MaterialTheme.colorScheme.background)
           .padding(horizontal = 12.dp, vertical = 4.dp)
           .then(
               if (isCollapsed) {
                   Modifier.clickable(
                       interactionSource = remember { MutableInteractionSource() },
                       indication = null
                   ) {
                       DiagnosticLogger.d(DiagnosticLogger.TAG_UI, "Sticky header Box clicked, expanding turn: ${turn.id}")
                       activeExpandedTurnId = turn.id
                   }
               } else Modifier
           )
   )
   ```
   - 保证即便手指点击在 12dp 水平外边距或 4dp 垂直安全间距处，也能 100% 灵敏展开卡片。
2. **卡片内部双重原生 `clickable` 响应**：
   - 替换容易导致手势丢失的 `combinedClickable`，卡片表面 `Surface` 与单行内容容器 `Row` 均挂载原生的轻量 `Modifier.clickable(interactionSource, indication = null, onClick = onToggleExpand)`；
   - 彻底解决长按等待超时、微触位移取消以及 Android 16 指针分发兼容性问题。

---

## 3. 产物与版本验证

1. **版本升级**：
   - `versionCode = 42`，`versionName = "1.0.41"`；
2. **构建结果**：
   - `./gradlew assembleDebug` 成功，APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`；
3. **全渠道多端分发落盘**：
   - 项目根目录：`octop-v1.0.41.apk`
   - 本地家庭 NAS：`/Volumes/tools/apk/octop-v1.0.41.apk`
   - Google Drive 云端硬盘：`我的云端硬盘/octop-apk/octop-v1.0.41.apk`
