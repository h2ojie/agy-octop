# Issue 020: 解决流式生成中列表手势回弹与确保提问卡片完整置顶展开 (v1.0.21)

## 1. 背景与用户反馈
用户反馈：
> “大模型在思考过程中，这个页面好像不能往上拉，越往上拉它就会放回弹回来，你查一下。
> 还有说的，把消息发出去之后，当前的消息就置顶，好像没有完成。”

同时参考了 Antigravity Web 手机端基准截图（`media_1792080705108_790b25c5.jpg` 与 `media_1792080654986_233ebbad.jpg`）。

## 2. 根因深度剖析

### 2.1 思考/流式过程中上拉回弹（"越往上拉越回弹"）
- **现象**：大模型思考或正文流式输出时，用户尝试用手指向上或向下滑动查看上下文，列表会剧烈回弹、打断手势并强制跳到底部。
- **根因**：
  在 `ChatScreen.kt` 中存在针对流式增量监听的副作用：
  ```kotlin
  val lastAiMsgContentLength = remember(messages) { messages.lastOrNull { !it.isUser }?.content?.length ?: 0 }
  LaunchedEffect(lastAiMsgContentLength) {
      if (isStreaming && lastAiMsgContentLength > 0) {
          ...
          listState.animateScrollToItem(total - 1)
      }
  }
  ```
  在流式生成或思考阶段，服务端每推送一个 token/thinking 片段（频率可达每秒数十次），该 `LaunchedEffect` 都会被触发，直接执行 `animateScrollToItem(total - 1)`。Jetpack Compose 的程序化动画会强制取消用户当前的触摸手势与惯性滚动，把列表强行拉回底部，造成严重的手势对抗与回弹感。
  在 Antigravity Web 端，用户在任何时刻都享有 100% 自由的手势滑动控制权，绝不会在流式时强行拉回。

### 2.2 消息发出后未正确置顶吸附且卡片被过早折叠
- **现象 1：LazyColumn 滚动高度受限**：
  在一条新交互轮次开始时，新提问刚发出，下方 AI 回复仅有简短的占位状态（如“正在思考中...”），整个轮次的总高度通常仅 150dp~200dp，远小于手机屏幕视口高度（约 700dp~800dp）。
  由于 LazyColumn 默认底部没有足够的缓冲空白，Compose 会强制将滚动位置钳制在“列表末尾触及视口底边缘”，导致新提问卡片被困在屏幕底部或中间，无法滚动到屏幕最顶部（`offset = 0`）。
- **现象 2：isSticky 误判导致卡片瞬间缩成一行**：
  原代码判定置顶折叠的逻辑为：
  `val isCurrentHeaderAtTop = headerIndex != null && listState.firstVisibleItemIndex == headerIndex && listState.firstVisibleItemScrollOffset > 0`
  由于像素对齐或物理微小偏移，一旦 `firstVisibleItemScrollOffset > 0`（哪怕仅 1 像素），系统立即判定为 sticky 状态，导致新提问卡片在置顶时瞬间缩成一行（如 `media_1792080654986_233ebbad.jpg`），而不是像 Antigravity Web（`media_1792080705108_790b25c5.jpg`）那样保持**完整自然的卡片展开状态**。

## 3. 解决方案与修改

### 3.1 彻底移除流式增量自动滚动，保障 100% 自由手势
- 完全删除 `ChatScreen.kt` 中的 `LaunchedEffect(lastAiMsgContentLength)`。
- 用户可任意上滑、下滑查看历史记录或思考卡片，无任何程序化回弹与手势对抗。
- 如需快速跳到底部，使用屏幕底部的 Antigravity 样式快速直达悬浮按钮（FAB）。

### 3.2 增加底部视口弹性缓冲空间 (`bottom_viewport_spacer`)
- 在 `LazyColumn` 底部追加 `item(key = "bottom_viewport_spacer") { Spacer(modifier = Modifier.fillParentMaxHeight(0.85f)) }`。
- 确保即使在只有一行提问和一行 AI 占位的初始状态下，LazyColumn 下方也具备充足的视口空间，让 `animateScrollToItem(headerIndex, 0)` 能够 100% 顺畅地将新提问卡片精确置顶到屏幕最上方边缘。

### 3.3 精确修正 isSticky 与折叠时机
- 简化吸顶判定：
  ```kotlin
  val isPastCurrentHeader = headerIndex != null && listState.firstVisibleItemIndex > headerIndex
  val isBeforeNextHeader = nextHeaderIndex == null || listState.firstVisibleItemIndex < nextHeaderIndex
  val isSticky = isPastCurrentHeader && isBeforeNextHeader
  ```
- **核心交互表现**：
  1. 消息发送后置顶时（`firstVisibleItemIndex == headerIndex`），卡片保持**完整展开的自然大卡片形态**，提问文字、附件预览完整展现，下方紧跟 AI 思考过程，对标 Antigravity Web。
  2. 只有当用户主动向上滑动、提问卡片滑出视口顶部（`firstVisibleItemIndex > headerIndex`）时，stickyHeader 才会吸附在顶端并自动缩成一行。
  3. 点击缩成一行的卡片可临时展开查看与复制，再次滑动聊天列表时自动收拢。
  4. 下拉聊天记录返回到当前轮次顶部时，卡片自然恢复全尺寸展开。

### 3.4 发送触发逻辑健壮性加固
- `lastHandledUserMsgId` 绑定 `currentThread?.threadId`，避免会话切换时误触置顶动画。
- 在 `LaunchedEffect(latestUserMsg?.id)` 中加入 `kotlinx.coroutines.yield()`，确保新轮次的 stickyHeader 在布局测量完成后精准执行滚动，并在索引查找异常时安全回退至当前轮次 header，绝不回退至 `total - 1`。

## 4. 验证要点
1. **流式自由滚动**：提问后在大模型思考和流式正文输出期间，上下滑动屏幕，手势极为流畅自然，零回弹。
2. **提问完整置顶**：发送消息后，新提问卡片立即平滑滚动并贴齐屏幕最顶部，且卡片处于完整展开状态（而非折叠成单行），紧随其下的是 AI 的思考卡片或“正在思考中...”。
3. **自然折叠与展开**：手指上拉查看长篇 AI 回复时，提问卡片平滑粘在顶部并缩成一行；下拉回退时自然恢复为大卡片。
