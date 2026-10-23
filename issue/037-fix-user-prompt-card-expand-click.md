# Issue 037: 修复置顶提问卡片点击无法展开问题 (v1.0.38)

## 1. 需求背景与痛点
用户反馈并配图（聊天窗口顶部置顶提问卡片）：
> “上面的对话点击展不开。”

### 痛点根因分析：
1. **外层父容器 `detectTapGestures` 冲突并即时反向置空状态**（最核心元凶）：
   - 在 `ChatScreen.kt` 中，包裹 `LazyColumn` 的外层 `Box` 上曾挂载了如下手势监听：
     ```kotlin
     Box(
         modifier = Modifier
             .fillMaxSize()
             .padding(paddingValues)
             .pointerInput(Unit) {
                 detectTapGestures {
                     if (activeExpandedTurnId != null) {
                         activeExpandedTurnId = null
                     }
                 }
             }
     )
     ```
   - 当用户点击顶部吸顶单行卡片希望展开时，子组件 `UserPromptCard` 的 `combinedClickable.onClick` 刚将 `activeExpandedTurnId` 置为当前 `turn.id`；
   - 紧接着由于外层父级容器捕获同一击键事件并触发 `detectTapGestures`，判断到 `activeExpandedTurnId != null`，瞬间在同一帧又将其置回了 `null`！导致卡片完全表现为“点击无效、展不开”。
2. **`isScrollInProgress` 滑动监听误判导致尺寸动画被立即截断收拢**：
   - 在 `ChatScreen.kt` 原有交互规范中使用了：
     ```kotlin
     LaunchedEffect(listState.isScrollInProgress) {
         if (listState.isScrollInProgress && activeExpandedTurnId != null) {
             activeExpandedTurnId = null
         }
     }
     ```
   - 当提问卡片通过 `.animateContentSize()` 展开时，由于其作为 `stickyHeader` 高度从单行 (~36dp) 动态拉伸至多行 (~200dp)，`LazyColumn` 会伴随高度变化调整测量布局与视口偏移，或在用户手指微触时产生微小偏移，导致 `isScrollInProgress` 瞬间变为 `true`，从而立即撤销了刚展开的状态。
3. **卡片本身手势拦截冗余与 Modifier 顺序问题**：
   - `UserPromptCard` 中 `animateContentSize()` 放在了条件 Modifier 之后，且展开状态下的标题栏被挂载了不必要的 `.scrollable`，阻断了正常的点击收起逻辑。

---

## 2. 解决方案与实现详情

### 2.1 彻底移除外层父容器全局拦截手势 (`ChatScreen.kt`)
- 移除了包裹 `LazyColumn` 的外层 `Box` 上的 `pointerInput(Unit) { detectTapGestures { ... } }`；
- 空白处/外部点击收拢逻辑已由各 AI 回复卡片上的透明点击区域、底部视口留白区（Spacer）以及滚动监听完美承载，不再全局劫持提问卡片区域的点击事件。

### 2.2 精准识别用户手指滑动：`collectIsDraggedAsState` 替换 `isScrollInProgress` (`ChatScreen.kt`)
- 将 `listState.isScrollInProgress` 替换为 `listState.interactionSource.collectIsDraggedAsState()`：
  ```kotlin
  // 核心交互规范 (对标 Antigravity)：当聊天列表发生用户手指拖拽滑动时，提问卡片自动收拢为单行
  // 注意：必须使用 collectIsDraggedAsState，避免在卡片展开动画/测量调整/微触时误触发收拢
  val isListDragged by listState.interactionSource.collectIsDraggedAsState()
  LaunchedEffect(isListDragged) {
      if (isListDragged && activeExpandedTurnId != null) {
          activeExpandedTurnId = null
      }
  }
  ```
- 这样只有在用户真正用手指拖拽滚动列表内容时才会自动将置顶卡片收回单行，在展开动画、测量重新布局或点按时绝不误触发。

### 2.3 规范化卡片点击与展开/收拢响应链路 (`ChatBubble.kt`)
- 调整 `UserPromptCard` 的 `animateContentSize()` 声明于基底 Modifier 链，确保展开动画顺畅无跳变；
- 在单行折叠态，卡片整体（文本、间距及展开下箭头）均能精确响应点击展开 `onToggleExpand`，长按复制全文；
- 移除展开态标题栏中冗余的 `.scrollable` 拦截，点击“提问内容”或“点击收拢”均可秒级收拢。

---

## 3. 验证与分发产物

- 编译调试：`./gradlew assembleDebug`，编译通过且零报错；
- 版本号演进：`v1.0.38` (VersionCode: 39)；
- 多渠道分发：
  - 本地：`octop-v1.0.38.apk`
  - 局域网共享盘：`/Volumes/tools/apk/octop-v1.0.38.apk`
  - Google Drive：`我的云端硬盘/octop-apk/octop-v1.0.38.apk`
  - VPS 公网下载：`http://47.130.249.193/octop.apk`
