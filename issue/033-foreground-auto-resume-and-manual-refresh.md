# Issue 033: 切后台返回前台自动对齐刷新与手动刷新支持 (v1.0.34)

## 1. 需求背景与痛点
用户反馈：
> “重新返回应用，然后默认在这个在上一个对话。
> 页面没有刷新，我需要切换别的对话再切回来才刷新。这个是不是可以优化一下？”

### 痛点根因分析：
1. **前台恢复无感知**：
   - 用户在等待长推理或工具调用时切到微信或锁屏；
   - 切回应用时，Activity 恢复到前台（`ON_RESUME`），但应用没有任何生命周期监听器去主动检查当前会话的最新云端状态。
2. **后台断网漏收 `done` 帧导致流式假死**：
   - 移动系统在切后台后通常会限制后台网络或挂起 Socket；
   - 云端生成结束后发送了 `done` 帧，但客户端未收到，导致本地内存中的 `_streamingThreads` 一直残留；
   - 客户端误以为还在生成，保持 `isStreaming = true`，闪烁光标与红色停止按钮一直显示。
3. **缺少快捷刷新通道**：
   - 用户无法手动刷新当前会话，被迫只能通过抽屉“切换到其他对话再切回来”利用 `selectThread` 来触发 `loadHistory`。

---

## 2. 解决方案与实现详情

### 2.1 云端完成态自愈与流式锁解除 (`ChatViewModel.kt`)
- 在 `loadHistory(agentId, threadId)` 中：
  ```kotlin
  // 核心自愈：若服务端明确指示本轮已结束 (turnActiveOnServer == false)，
  // 立即解除本地可能因切后台/断线漏收 done 帧而残留的流式锁与看门狗
  if (!turnActiveOnServer) {
      _streamingThreads.remove(threadId)
      stopStreamingWatchdog(threadId)
  }
  ```
  云端只要已完成，本地立即释放 `isStreaming` 锁，将云端完整的最终回复呈现到屏幕上，光标自然消失。

### 2.2 前台恢复自动同步 (`onForegroundResume`)
- 在 `ChatViewModel` 中新增 `onForegroundResume()`：
  - 检查当前会话的长连接健康度，断开时自动重连并 `subscribe`；
  - 自动调用 `loadHistory` 与 `loadThreadsForAgent` 静默拉取云端对齐。
- 在 `ChatScreen.kt` 中挂载 Compose 生命周期监听器：
  ```kotlin
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
              viewModel.onForegroundResume()
          }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  ```

### 2.3 手动刷新支持
1. **顶部栏快捷刷新按钮**：
   - 在顶部栏右上角三点菜单左侧增加一个精致的 36dp 刷新按钮（`Icons.Default.Refresh`），轻触即可触发当前会话实时同步并提示“已同步最新会话记录”。
2. **三点菜单刷新选项**：
   - 在顶部栏下拉菜单中首项增加“刷新当前会话”，提供多重入口。

---

## 3. 验证要点
1. **切后台返回前台自动刷新**：在 AI 生成或工具调用期间切换到其他应用，待生成完毕返回 Octop，无需任何手动操作，页面在切回瞬间自动对齐完整回复，光标消失，输入框复原。
2. **快捷手动刷新**：轻触右上角刷新按钮，即时触发历史拉取与连接保活检查，即刻获得最新对话。
