# Issue 024: 隔离提问卡片内部纵向滚动与外层回答列表，彻底杜绝上下连带滑动 (v1.0.25)

## 1. 背景与用户反馈
用户反馈：
> “再优化一下
> 提问内容的对话框里面的滑动不要影响到后面的答案，因为我有时候在提问对话框里面向下拉，然后会拉到下一会拉到上一次回答内容，向上滑有时候会把后面的回答内容往上滑了。”

附带长文本提问对话框真实截图：当提问内容包含多段聊天记录或长文本时，用户在卡片内上下滑动浏览提问，经常因滑动到达卡片顶部或底部，触发外层 `LazyColumn` 发生连带滚动（向下拖拽拉出上一轮 AI 响应，向上拖拽把当前轮的 AI 回复顶上来），严重干扰阅读体验。

---

## 2. 根因剖析
在 Jetpack Compose 中：
1. **嵌套滚动传递机制 (Nested Scroll Bubble)**：
   - `UserPromptCard` 内部使用了 `Column(modifier = Modifier.verticalScroll(scrollState))`。
   - Compose 的 `scrollable` 机制在子组件滚动到达边界（`scrollState.value == 0` 或 `scrollState.value == maxValue`）时，未消费的滑动位移量（`available` delta）与剩余惯性速度（fling velocity）会通过 `onPostScroll` 与 `onPostFling` 自动冒泡分发给父级容器。
   - 外层的 `LazyColumn` 监听到冒泡上来的剩余位移与速度后，会直接执行列表滚动，导致：
     - 向下拉到顶部后继续下拉，外层列表向下滚，露出上一轮的 AI 回答；
     - 向上拉到底部后继续上拉，外层列表向上滚，将当前轮的 AI 答案连带顶上来。
2. **头部栏区域手势未捕获**：
   - 展开状态下的“提问内容/复制/点击收拢”顶栏之前没有挂载垂直手势监听器，若用户手指触碰在顶栏进行滑动，手势会穿透直接带动外层 `LazyColumn` 滚动。

---

## 3. 解决方案与架构设计

### 3.1 专属嵌套滚动拦截连接器 (`NestedScrollConnection`)
在 `UserPromptCard` 中，针对展开状态 (`!isCollapsed`) 构建专门的 `stopNestedScrollConnection`：
```kotlin
val stopNestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // 彻底拦截并消费全部未被内部滚动的垂直与水平位移，阻断其向外层 LazyColumn 冒泡
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            return Velocity.Zero
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity
        ): Velocity {
            // 彻底消费全部剩余惯性速度，防止滑到顶/底时外层列表跟随弹跳
            return available
        }
    }
}
```
并将该拦截器挂载到 `Surface`：
```kotlin
modifier = Modifier
    .fillMaxWidth()
    .then(
        if (!isCollapsed) {
            Modifier.nestedScroll(stopNestedScrollConnection)
        } else Modifier
    )
```

### 3.2 顶栏与外层卡片手势闭环
在展开态的顶栏 `Row` 以及外层 `Column` 上挂载垂直滚动捕获状态：
```kotlin
.scrollable(
    orientation = Orientation.Vertical,
    state = rememberScrollableState { 0f }
)
```
- 确保用户手指无论触碰在提问文本区、附件缩略图区、顶部栏还是内边距上，所有的垂直拖拽与甩动手势均在提问卡片内部消化。
- 剩余所有位移 100% 被 `stopNestedScrollConnection` 吸收，外层 `LazyColumn` 接收到的可用位移恒为 `Offset.Zero`。

### 3.3 折叠态自然无阻
当卡片处于 `isCollapsed == true`（单行极简模式）时，该拦截器自动停用，保证用户在浏览历史列表时划过单行提问栏能自然流畅地滚动整个对话历史。

---

## 4. 验证要点
1. **长提问卡片内下拉到底/顶**：在长提问对话框内快速上下滑动，滑动到最顶或最底时，卡片内部自然停住，背后的 AI 回答与上一轮对话纹丝不动。
2. **顶栏触摸滑动**：手指在“提问内容”顶栏区域滑动，不会带动外层答案列表上下晃动。
3. **按钮与复制功能无损**：卡片内的“复制”与“点击收拢”按钮点击响应灵敏，长按选中正文正常运作。
4. **外部滑动正常收拢**：在卡片外部（AI 答案或列表空白处）滑动时，提问卡片依然会依照 Antigravity 交互规范优雅自动收拢为单行。
