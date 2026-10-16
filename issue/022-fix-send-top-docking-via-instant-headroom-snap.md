# Issue 022: 修复消息发送未置顶问题，采用瞬时头部缓冲与平滑收拢 (v1.0.23)

## 1. 背景与用户反馈
用户反馈：
> “改了一个，发送消息又没有置顶了”

## 2. 根因深度剖析
在 `v1.0.22` 中，为了解决“回复完成后底部仍能上拉大片黑边空白”的问题，引入了 `animateDpAsState`：
```kotlin
val bottomSpacerHeight by animateDpAsState(
    targetValue = if (isStreaming) (configuration.screenHeightDp * 0.75f).dp else 16.dp,
    animationSpec = tween(durationMillis = 300)
)
```
### 为什么导致发送消息无法置顶？
1. **时序冲突与高度钳制**：
   - 发送前处于非流式状态（`isStreaming == false`），底部的 `bottomSpacerHeight` 值为 `16.dp`。
   - 用户点击发送，`_isStreaming.value` 切换为 `true`，`animateDpAsState` 开始从 `16.dp` 向 `75%` 屏高过渡，耗时 300ms。
   - 与此同时，新增的 `userMsg` 立即触发 `LaunchedEffect(latestUserMsg?.id)` 并执行 `animateScrollToItem(headerIndex, 0)`。
   - **在执行滚动的关键时刻（前数十毫秒），`bottomSpacerHeight` 实际上仅有 16~20dp**。
   - 对于后续轮次的消息（上方已有对话历史），下方仅有新提问卡片与 AI 占位（合计约 150dp）加 16dp 缓冲，远不足以填满屏幕（约 700dp）。
   - Compose 的 `LazyListState.animateScrollToItem` 检测到底部内容高度不足，**强行将滚动位置钳制在屏幕底部**，导致新提问卡片无法滚到屏幕最上方，置顶吸顶彻底失效。

2. **输入法收起过程中的视口重绘**：
   - 发送时软键盘收起（耗时约 150~200ms），视口从受压迫的 350dp 扩展为全屏 700dp。若在键盘完全收拢前立即触发短距离滚动，容易受到输入法窗口变化的影响。

---

## 3. 最终解决架构

### 3.1 基于 `Animatable` 的瞬时展开与平滑收缩
改用低层级、无时序死角的 `Animatable`：
```kotlin
val bottomSpacerAnimatable = remember { Animatable(16f) }
LaunchedEffect(isStreaming) {
    if (isStreaming) {
        // 瞬间提供 85% 视口缓冲（0 延迟 snapTo），确保滚动发起时视口底部具有充足空间
        bottomSpacerAnimatable.snapTo(configuration.screenHeightDp * 0.85f)
    } else {
        // 回复完成后，以 350ms 补间平滑收拢至 16dp 正常间距
        bottomSpacerAnimatable.animateTo(16f, animationSpec = tween(durationMillis = 350))
    }
}
```
- **发送提问瞬间（`isStreaming == true`）**：
  使用 `snapTo` 在第 0 帧瞬间提供 85% 屏高缓冲，**绝不引入任何递增动画延迟**。`animateScrollToItem(headerIndex, 0)` 发起时，视口已有充足下潜空间，100% 精确置顶到屏幕最上方边缘！
- **回复完成瞬间（`isStreaming == false`）**：
  使用 `animateTo` 顺滑收拢至 `16dp`，彻底杜绝回复结束后可向上拉扯出大片黑边空白的异常现象。

### 3.2 布局稳定延迟窗口 (`delay(60L)`)
在 `LaunchedEffect(latestUserMsg?.id)` 中，将 `yield()` 升级为微小的 `delay(60L)`：
- 为软键盘收拢与 LazyColumn 新 item 的测量摆放提供稳定的 60ms 缓冲，随后发起弹性滑动，顺畅直达顶端。

---

## 4. 验证要点
1. **连续多轮发消息置顶**：无论第 1 轮、第 2 轮还是第 N 轮，点击发送后新提问卡片瞬间平滑滚至屏幕最顶端，卡片保持完整展开自然状态。
2. **回复后紧凑贴合**：AI 回复结束后，底部缓冲自动收缩至 16dp，无法再往上拉出黑边大空白。
3. **软键盘自动收拢**：点击发送后软键盘顺畅隐藏，视野开阔。
