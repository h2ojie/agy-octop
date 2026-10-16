# Issue 015: 自然流长提问卡片防撑屏与滑动模型回复时自动收缩为单行

## 1. 背景与用户反馈

用户在 v1.0.15 版本体验中指出两个核心痛点：
1. **下拉模型回复到自然用户内容展开时，还是撑满全屏**：
   - 当用户从模型回复向下滑动回到对话顶部（自然流用户消息位置），如果用户发送的 Prompt 内容极长（例如几十行提示词或脚本），卡片由于在非吸顶（自然流）状态下缺少高度上限约束与收起选项，直接撑满整屏手机屏幕，遮挡了后续所有回复。
2. **上拉模型回复时，未能自动收回变成一行**：
   - 用户在展开长提问卡片后，或者从顶部向下滑动进入模型回复时，原吸顶判定条件过于严苛（仅在 `firstVisibleItemIndex > headerIndex` 即整张长卡片彻底滑出屏幕顶部后才触发），导致在上拉模型回复的整个过程中卡片未能及时收缩为单行；且手动展开的卡片在滑动聊天列表时没有自动收缩。

---

## 2. 根因剖析与改进架构

### 2.1 全态长提问保护上限与双向折叠 (`UserPromptCard`)
- **全状态高度保护**：不仅在置顶吸顶时约束高度，自然流展开状态下同样施加高度保护：
  $$maxContentHeight = (screenHeightDp \times 0.38f).coerceIn(160dp, 300dp)$$
  并挂载 `Modifier.heightIn(max = maxContentHeight).verticalScroll(scrollState)`。即使是几百行代码的超长提示词，展开时最多也仅占据屏幕 38% 高度，内部支持顺畅垂直滑动，绝不霸屏。
- **自然流收拢折叠能力**：检测若消息属于长文本（字数 > 80 或含换行符或含附件），在自然流状态同样渲染“提问内容 点击收拢 ∧”顶栏，用户随时可将其收拢为单行卡片。

### 2.2 瞬时吸顶判定与滑动自动收回 (`ChatScreen`)
- **吸顶触发点提前**：
  原判定 `firstVisibleItemIndex > headerIndex` 导致长卡片必须滑完整个高度后才判定为吸顶。现改进为：
  $$isCurrentHeaderAtTop = firstVisibleItemIndex == headerIndex \land firstVisibleItemScrollOffset > 0$$
  $$isSticky = (firstVisibleItemIndex > headerIndex \lor isCurrentHeaderAtTop) \land isBeforeNextHeader$$
  只要用户从顶部开始上拉模型回复（偏移量 > 0），卡片立刻被 Compose 锚定在顶部并**瞬间收回变成单行**！
- **手势滑动监听自动收起**：
  引入 `LaunchedEffect(listState.isScrollInProgress)`，无论用户是上拉还是下拉模型回复，只要产生列表滚动，之前任何临时展开的提问卡片立即自动收回变成 1 行，交互极其自然跟手。

---

## 3. 变更清单

1. `app/src/main/java/com/octop/client/ui/components/ChatBubble.kt`:
   - `UserPromptCard` 无论在自然流还是吸顶流，均统一施加 38% 屏幕高度保护上限与内部滚动。
   - 长提问卡片在自然流中同样提供“点击收拢 ∧”操作栏，支持随时折叠为 1 行。

2. `app/src/main/java/com/octop/client/ui/ChatScreen.kt`:
   - 增加 `isScrollInProgress` 监听，滑动聊天即刻自动折叠展开的吸顶卡片。
   - 重构 `isSticky` 判定逻辑，上拉时只要产生滑动位移即刻切入单行置顶。
   - 支持自然流下手动折叠状态管理 `manualCollapsedInNaturalFlow`。

3. `app/build.gradle.kts`:
   - 版本升级至 `versionCode 17`, `versionName "1.0.16"`。

---

## 4. 验证与发布

- `./gradlew assembleDebug` 构建成功。
- APK 归档并同步至本地、内网共享盘及 Google Drive。
- 源码打包并推送到 GitHub `jieiceone/octop-android`。
