# Issue 021: 回复完成后收拢底部缓冲空间并支持发送后自动收起输入法 (v1.0.22)

## 1. 背景与用户诉求
用户反馈：
> “现在发消息是置顶了，但是最后回复完还能往上拉也有点奇怪，能处理吗
> 还有，我输入完点击发送，顺便把输入法收起来”
> 并上传了实际截图（`media_1792081704737_16008cfc.jpg`）。

### 痛点定位：
1. **回复完成后底部仍能上拉大面积空白**：
   - 在 `v1.0.21` 中，为了确保新消息发出后能够平滑置顶吸附到屏幕顶部，在 `LazyColumn` 底部加入了静态固定的 `Spacer(modifier = Modifier.fillParentMaxHeight(0.85f))`。
   - 当大模型回复完毕后，该 0.85 屏高的空白依然常驻在列表末尾。如果对话较短（如截图所示只有一条查询额度提问及简短表格），用户用手向上拖动时，会将回复内容推至屏幕顶部，下方暴露出大片黑色空白，导致提问卡片被强行折叠为单行，体验异常空旷且怪异。
2. **发送消息后软键盘未自动收起**：
   - 用户在输入框键入完毕点击发送按钮后，软键盘依然停留在屏幕上遮挡对话界面，需要用户手动点击返回键收起键盘。

---

## 2. 解决方案与实施

### 2.1 动态底部视口缓冲动画 (`bottomSpacerHeight`)
- 使用 Jetpack Compose 的 `animateDpAsState` 监听 `isStreaming` 状态：
  ```kotlin
  val configuration = LocalConfiguration.current
  val bottomSpacerHeight by animateDpAsState(
      targetValue = if (isStreaming) (configuration.screenHeightDp * 0.75f).dp else 16.dp,
      animationSpec = tween(durationMillis = 300),
      label = "bottomSpacerHeight"
  )
  ```
- **核心交互行为**：
  1. **正在流式生成中（`isStreaming == true`）**：
     - 底部视口空间平滑展开至屏幕高度的 75%，确保新发出的提问能够 100% 顺畅地置顶吸附在屏幕最顶端（`offset = 0`），AI 思考与正文在下方展开。
  2. **回复生成完毕后（`isStreaming == false`）**：
     - 底部空间通过 300ms 补间动画平滑收拢至正常的 `16.dp` 底部间距。
     - 若内容短于视口，内容自然居于顶部与正常页面内，**无法再上拉进入大面积空白**，提问卡片完整展示绝不被误折叠。
     - 若内容长于视口，对话自然紧贴底栏上方展示，不会出现尾部虚假空白。

### 2.2 发送动作联动软键盘与焦点收起
- 在 `InputBar.kt` 与 `ChatScreen.kt` 的发送点击事件中同步接入 `focusManager` 与 `keyboardController`：
  ```kotlin
  focusManager.clearFocus()
  keyboardController?.hide()
  onSend()
  ```
- 用户点击发送按钮（或排队发送按钮）后，系统立即清除输入框焦点并优雅收起系统输入法，为用户留出开阔的屏幕视野浏览大模型的思考与流式生成过程。

---

## 3. 验证与交付
- **编译打包**：运行 `./gradlew assembleDebug`，编译无任何 warning/error。
- **产物分发**：生成 `octop-v1.0.22.apk`，同步至本地工作区、`/Volumes/tools/apk` 与 Google Drive 云端硬盘。
