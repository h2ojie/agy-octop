# Issue 041: 方案 A 落地 —— 基于非消费型垂直拖拽感知实现移动 AI 消息时自动收拢提问卡片

## 1. 业务目标与架构挑战

### 1.1 核心目标
用户希望在聊天界面中保留原汁原味的自然联动体验：
- **在提问框内部滚动**：用户上下滑动浏览长提问内容时，**绝不自动收拢**，保持内部独立浏览；
- **在 AI 消息区域划动**：当用户手指落在下方的 AI 消息卡片或底部视口区域并发生垂直上下拖动浏览会话时，**提问卡片自动平滑收拢为单行**；
- **收拢后轻触秒开**：收拢为单行后，用户随时轻触单行提问栏或其边距，**100% 瞬间再次展开**，无任何手势死锁或穿透拦截。

### 1.2 关键难点与避坑准则
1. **绝不能消费指针事件（Do NOT consume）**：
   - 如果使用传统的 `detectDragGestures` 并在内部调用 `change.consume()`，会导致外层 `LazyColumn` 丢失拖拽位移，造成列表无法滑动、卡顿；
   - 必须使用非消费型（Read-only）的指针监听，把位移完整留给外层 `LazyColumn` 的滚动引擎。
2. **严格区分轻触点击（Tap）与垂直拖拽（Drag）**：
   - 用户在 AI 消息卡片内部点击复制按钮、点击展开“深度思考”、点击代码块或链接时，手势绝对不能误判为拖拽，绝不能误收起提问框；
   - 必须以 `viewConfiguration.touchSlop` 为刚性阈值，并且判定垂直位移 `dy > touchSlop && dy > dx`。
3. **零背景穿透与无底层点击拦截**：
   - 彻底废除底层容器的全局 `clickable`，AI 消息容器只负责在发生有效垂直拖动时触发回调 `activeExpandedTurnId = null`。

---

## 2. 方案 A 核心实现

### 2.1 引入高精度非消费型垂直拖拽感知修饰符 `onVerticalDragOverThreshold` (`ChatScreen.kt`)
```kotlin
private fun Modifier.onVerticalDragOverThreshold(
    enabled: Boolean,
    onDrag: () -> Unit
): Modifier = if (enabled) {
    this.pointerInput(enabled) {
        val touchSlop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                // 在 Initial 阶段优先感知手指按下 (Down)
                val down = awaitPointerEvent(PointerEventPass.Initial)
                val downChange = down.changes.firstOrNull { it.pressed } ?: continue
                val downPointerId = downChange.id
                val downPos = downChange.position

                var triggered = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == downPointerId } ?: break
                    if (!change.pressed) {
                        break // 手指抬起 (Up)
                    }
                    if (!triggered && change.positionChanged()) {
                        val dy = kotlin.math.abs(change.position.y - downPos.y)
                        val dx = kotlin.math.abs(change.position.x - downPos.x)
                        // 判定为真正的垂直列表划动
                        if (dy > touchSlop && dy > dx) {
                            triggered = true
                            onDrag()
                        }
                    }
                }
            }
        }
    }
} else this
```

### 2.2 精准挂载至 AI 消息卡片与底部视口容器 (`ChatScreen.kt`)
1. **AI 消息卡片容器**：
   ```kotlin
   items(turn.aiMessages, key = { it.id }) { aiMsg ->
       Box(
           modifier = Modifier
               .fillMaxWidth()
               .onVerticalDragOverThreshold(enabled = activeExpandedTurnId != null) {
                   DiagnosticLogger.d(DiagnosticLogger.TAG_UI, "AI message vertically dragged, collapsing prompt card: $activeExpandedTurnId")
                   activeExpandedTurnId = null
               }
       ) {
           AiResponseCard(...)
       }
   }
   ```
2. **底部视口弹性空白区**：
   ```kotlin
   item(key = "bottom_viewport_spacer") {
       Spacer(
           modifier = Modifier
               .fillMaxWidth()
               .height(bottomSpacerAnimatable.value.dp)
               .onVerticalDragOverThreshold(enabled = activeExpandedTurnId != null) {
                   DiagnosticLogger.d(DiagnosticLogger.TAG_UI, "Bottom spacer vertically dragged, collapsing prompt card: $activeExpandedTurnId")
                   activeExpandedTurnId = null
               }
       )
   }
   ```

### 2.3 效果验证
- **动态启闭（Zero Overhead）**：当卡片折叠（`activeExpandedTurnId == null`）时，`enabled = false`，完全不启动手势协程；
- **隔离性保全**：提问卡片自身未挂载该修饰符且具备 `stopNestedScrollConnection`，内部滚动提问完全不受任何折叠影响；
- **点击交互不受损**：由于不调用 `change.consume()`，AI 消息内思考折叠、复制按钮、文档/图片点击 100% 灵敏原生响应；
- **手势冲突彻底化解**：移动 AI 消息时丝滑收拢，单行提问栏轻触 100% 灵敏重新展开。

---

## 3. 产物与版本验证

1. **版本升级**：
   - `versionCode = 43`，`versionName = "1.0.42"`；
2. **全渠道多端分发落盘**：
   - 项目根目录：`octop-v1.0.42.apk`
   - 本地家庭 NAS：`/Volumes/tools/apk/octop-v1.0.42.apk`
   - Google Drive 云端硬盘：`我的云端硬盘/octop-apk/octop-v1.0.42.apk`
