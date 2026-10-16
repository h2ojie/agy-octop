# Issue 023: 点击列表/滑动AI回复自动收拢提问卡片与小图标缩略图展示 (v1.0.24)

## 1. 背景与用户反馈
用户反馈：
> “这个再优化一下。难点在于：当不是用户输入内容的时候，也就是点击到后面的列表，或者 AI 回复的消息滑动的时候，用户消息栏就自动收成一行。
> 还有上传的图片也不要显示这么大，用小图标代替就可以，差不多就是 anti gravity web端的这个效果。”

同时提供了 Antigravity Web 手机端的真实截图与 Octop 当前效果截图进行对标：
1. **Antigravity Web 效果**：
   - 顶部/历史提问卡片自动收缩为简洁单行或紧凑卡片，左侧配有小尺寸方形缩略图（约 56dp x 56dp），绝不铺满屏幕。
   - 当用户滑动浏览 AI 回复或者点击聊天列表时，提问卡片自动折叠收缩为单行。
2. **Octop 原先问题**：
   - 用户发送带图片的消息时，卡片内调用了 `MessageAttachmentList`，导致渲染出 240dp 极高大图及“已上传附件:”长标题，独占半屏。
   - 提问卡片采用分别管理的 `manualExpandedTurnId` 和 `manualCollapsedInNaturalFlow`，导致发送后的新卡片在自然流中即使滑动或点击背景也无法收拢。

---

## 2. 核心架构与交互设计

### 2.1 统一唯一的活动展开状态 (`activeExpandedTurnId`)
- 将分散的折叠/展开状态合并为全局唯一的活动展开轮次 ID：
  ```kotlin
  var activeExpandedTurnId by remember { mutableStateOf<String?>(null) }
  ```
- **默认全折叠**：
  任何一轮提问卡片的折叠状态统一定义为：
  ```kotlin
  val isCollapsed = activeExpandedTurnId != turn.id
  ```
  在未显式激活时，所有提问卡片天然处于 1 行单行极简条状态（单行省略文本 + 展开箭头），绝不喧宾夺主。
- **发送时初始展开**：
  用户发送新消息后，将 `activeExpandedTurnId` 赋予该轮次 ID，新消息滚到顶部并以展开状态展示。

### 2.2 触碰列表/滑动 AI 回复时瞬时收拢
对标 Antigravity Web 手机端的三维感知收拢：
1. **滑动感知 (`listState.isScrollInProgress`)**：
   ```kotlin
   LaunchedEffect(listState.isScrollInProgress) {
       if (listState.isScrollInProgress && activeExpandedTurnId != null) {
           activeExpandedTurnId = null
       }
   }
   ```
   无论是用户手指拖拽滑动、惯性飞掠 AI 长回复，还是上拉下拉列表，一旦发生位移，展开的提问卡片立即自动平滑收拢为一行单行！
2. **点击 AI 回复内容**：
   AI 回复卡片外层包裹透明无波纹点击监听器：
   ```kotlin
   Box(
       modifier = Modifier
           .fillMaxWidth()
           .clickable(
               interactionSource = remember { MutableInteractionSource() },
               indication = null
           ) {
               if (activeExpandedTurnId != null) {
                   activeExpandedTurnId = null
               }
           }
   ) { AiResponseCard(...) }
   ```
   用户在阅读思考或点击 AI 文本时，若提问栏处于展开状态，即刻自动收拢。
3. **点击空白列表背景 / 视口缓冲区域**：
   在整个聊天内容区外层 `Box` 的 `pointerInput(Unit) { detectTapGestures { ... } }` 以及底部的 `bottom_viewport_spacer` 上均挂载收拢逻辑，确保点击屏幕任意空余区域均可让提问栏收回单行。
4. **单行点击展开与收拢切换**：
   点击单行条时展开卡片，在展开状态下点击顶部“点击收拢”按钮即可收回，提供完整、对称的操作闭环。

### 2.3 Antigravity Web 风格小图标缩略图 (`PromptAttachmentThumbnails`)
- 废弃原先的 240dp 全屏横排大图与“已上传附件:”繁复标题。
- 引入专属的紧凑缩略图组件：
  - **尺寸与圆角**：采用 `56.dp x 56.dp`、`10.dp` 圆角方形小缩略图，搭配精致的暗调边框与微底色。
  - **裁剪模式**：`ContentScale.Crop`，保证图片内容居中充满且规整。
  - **横向滑动与预览**：支持多张图片横向平滑滚动，点击任一小图标直接调起全屏查看器。
  - **文档附件处理**：文档类型同样精简为 `44.dp` 高度的小胶囊卡片。
- **单行模式富文本提示**：
  收拢为单行条时，若带附件，自动在单行文本前加上 `📎 [N个附件]` 提示，直观又节省空间。

---

## 3. 验证要点
1. **图片提问精致小巧**：发送带图片的提问后，卡片内图片呈现为精致的 56dp 方形缩略图，高度从 300+dp 骤降至 80dp 左右，不再霸占屏幕。
2. **滑动即收拢**：只要手指上下滑动 AI 的回复或列表，原本展开的提问栏立即收拢为一行单行。
3. **点击即收拢**：点击 AI 回复、下方空白处，提问栏瞬间收拢为一行单行。
4. **按需再展开**：点击折叠后的单行提问条，随时展开查看完整提问与附件小图标；点击“点击收拢”或再次点击即可收回。
