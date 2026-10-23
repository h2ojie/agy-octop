# Issue 039: 修复用户提问卡片展开后上下滑动时意外自动收拢问题 (v1.0.40)

## 1. 需求背景与痛点
用户反馈并配图与诊断日志：
> “在用户消息框展开之后，然后上下滑动几次，总会触发，忽然用户消息框收起来，日志已上传”

### 诊断日志精确定位分析：
从用户设备实时上传的诊断日志包（`octop_20261023.log`）中检索到如下高频反复出现的关键日志序列：
```
[2026-10-23 18:11:44.929] [DEBUG] [UI] LazyColumn touch dragged, auto-collapsing prompt card
[2026-10-23 18:11:45.683] [DEBUG] [UI] Prompt card toggled for turn: 1ea6f32b-bb70-40f0-90b3-07b97140290a, expanding=true
[2026-10-23 18:11:46.985] [DEBUG] [UI] LazyColumn touch dragged, auto-collapsing prompt card
[2026-10-23 18:11:47.856] [DEBUG] [UI] Prompt card toggled for turn: 1ea6f32b-bb70-40f0-90b3-07b97140290a, expanding=true
[2026-10-23 18:11:49.153] [DEBUG] [UI] LazyColumn touch dragged, auto-collapsing prompt card
...
[2026-10-23 18:12:15.963] [DEBUG] [UI] LazyColumn touch dragged, auto-collapsing prompt card
[2026-10-23 18:12:17.332] [DEBUG] [UI] Prompt card toggled for turn: 1ea6f32b-bb70-40f0-90b3-07b97140290a, expanding=true
```
在短短 33 秒内，用户连续 12 次点击展开提问卡片（`expanding=true`），随后每次试图上下滑动阅读长提问文本时，均在 1~2 秒内被 `LazyColumn touch dragged, auto-collapsing prompt card` 强制收拢。

### 痛点根因分析：
1. **外层 `LazyColumn` 全局拖拽监听器缺乏区域隔离感知**：
   - 在 `ChatScreen.kt` 中设计了对标 Antigravity 的联动收拢逻辑：
     ```kotlin
     val isListDragged by listState.interactionSource.collectIsDraggedAsState()
     LaunchedEffect(isListDragged) {
         if (isListDragged && activeExpandedTurnId != null) {
             activeExpandedTurnId = null
         }
     }
     ```
   - 其初衷是在用户**阅读或上下滑动浏览下方的 AI 回复**时，自动收拢顶部展开的提问卡片；
   - 但因为置顶提问卡片（`UserPromptCard`）本身是 `LazyColumn` 内部的一个 `stickyHeader` 列表项，任何落在提问卡片上的触摸位移如果未被底层完全消费，就会直接被 `LazyColumn` 捕获并判定为列表被拖拽（`isListDragged = true`），从而粗暴地收拢卡片本身。
2. **长提问卡片内部滚动触底/触顶时未消费位移穿透冒泡**：
   - `UserPromptCard` 内部通过 `Column(modifier = Modifier.heightIn(max = maxContentHeight).verticalScroll(scrollState))` 支持长文本内部纵向滑动；
   - 原先仅使用了 `stopNestedScrollConnection` 拦截 Compose 嵌套滚动协议中的 `onPostScroll` 与 `onPostFling`；
   - 然而在 Compose 的底座事件传递机制中，当内部 `scrollState` 滚动到最顶部（`value == 0`）或最底部（`value == maxValue`）时，内部 `verticalScroll` 无法继续消费位移，原始指针位移事件（`PointerInputChange`）不会被标记为 `consumed`；
   - 处于祖先节点的 `LazyColumn` 的 `Modifier.scrollable` 便直接捕获了该指针位移并跨越 `touchSlop`，立即向 `listState.interactionSource` 发射 `DragInteraction.Start`，导致在提问卡片内“上下滑动几次”（触碰边界或触顶触底）瞬间触发卡片意外自收拢。
3. **卡片标题栏与内边距区域缺乏手势保护隔离**：
   - 提问卡片顶部的标题栏（“提问内容”、“复制”、“点击收拢”）及外层容器周围没有挂载滚动修饰符，用户手指在滑动浏览长提问时若不慎触碰到标题栏或卡片边缘空白处，位移直接穿透至 `LazyColumn`，同样会瞬间造成卡片崩溃式折叠。

---

## 2. 解决方案与架构实现

### 2.1 引入高精度防穿透手势修饰符 `consumeDragGestures` (`ChatBubble.kt`)
通过指针级自定义手势修饰符，在 `PointerEventPass.Main` 阶段对展开状态下的卡片及其置顶容器施加防穿透隔离层：
```kotlin
fun Modifier.consumeDragGestures(
    enabled: Boolean = true
): Modifier = if (enabled) {
    this.pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                // 等待手指按下 (Down)
                val downEvent = awaitPointerEvent(PointerEventPass.Main)
                val downChange = downEvent.changes.firstOrNull { it.pressed } ?: continue
                val downPointerId = downChange.id
                val downPosition = downChange.position
                var isDrag = false

                // 持续跟踪当前手势指针移动与抬起
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == downPointerId } ?: break
                    if (!change.pressed) {
                        break // 手指抬起 (Up)
                    }
                    if (change.positionChanged()) {
                        val currentPosition = change.position
                        val dragDistance = (currentPosition - downPosition).getDistance()
                        if (dragDistance > touchSlop) {
                            isDrag = true
                        }
                        if (isDrag) {
                            event.changes.forEach { c ->
                                if (c.positionChanged()) {
                                    c.consume()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} else this
```
- **点击与滑动的绝对安全隔离**：
  - 手指位移在 `touchSlop`（约 8~16px）以内时判定为点击（Tap），绝不提前调用 `change.consume()`，确保卡片内「点击收拢」、「复制」、图片缩略图点击预览、文档点击下载以及长按文本选择功能 **100% 灵敏响应**；
  - 一旦位移超过 `touchSlop`（进入拖拽/滑动态），在 `PointerEventPass.Main`（子组件优先处理后）彻底消费所有剩余运动位移，**绝不让任何 1 像素的拖拽事件泄漏给外层的 LazyColumn**。

### 2.2 双重立体防护 (`ChatBubble.kt` & `ChatScreen.kt`)
1. **卡片内部 (`UserPromptCard` 的 `Surface`)**：
   - 展开状态下同时应用 `stopNestedScrollConnection`（嵌套滚动与惯性 Fling 拦截）与 `consumeDragGestures`（指针级拖拽拦截）；
   - 增加展开状态复位监听：`LaunchedEffect(isCollapsed) { if (isCollapsed) scrollState.scrollTo(0) }`，重新展开时始终从提问开头开始阅读；
   - 视口最大高度优化提升至 `(configuration.screenHeightDp * 0.42f).coerceIn(160f, 360f).dp`，在大屏/长屏手机上提供更加充裕的阅读空间。
2. **列表置顶吸顶容器 (`ChatScreen.kt` 的 `Box`)**：
   - 置顶吸顶的外部包裹层同样挂载 `.consumeDragGestures(enabled = !isCollapsed)`；
   - 彻底封死从卡片边缘 12dp 水平外边距或 4dp 垂直间距处滑动手势穿透的可能。

### 2.3 联动收拢规范的真正闭环
- **提问卡片内部**：无论用户如何反复、快速、大幅度上下滑动浏览长提问、触碰卡片边缘或触碰标题栏，**绝不收拢**；
- **提问卡片外部**：用户手指触摸滑动下方的 AI 回复列表、点击 AI 气泡或点击底部空白区时，提问卡片立即平滑折叠为一行单行，保持视野开阔。

---

## 3. 验证与产物分发

1. **编译验证**：
   - `./gradlew compileDebugKotlin`：编译零报错；
   - `./gradlew assembleDebug`：成功构建调试版 APK；
2. **版本号升级**：
   - `versionCode = 41`，`versionName = "1.0.40"`；
3. **多端分发落盘**：
   - 本地根目录：`octop-v1.0.40.apk`
   - 家庭 NAS 共享盘：`/Volumes/tools/apk/octop-v1.0.40.apk`
   - Google Drive 云端硬盘：`我的云端硬盘/octop-apk/octop-v1.0.40.apk`
