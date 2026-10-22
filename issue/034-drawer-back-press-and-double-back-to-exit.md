# Issue 034: 抽屉侧边栏返回拦截与主界面双击返回防误触退出 (v1.0.35)

## 1. 需求背景与痛点
用户反馈：
> “这个页面（专家矩阵侧边栏抽屉）。按返回键返回到聊天主窗口。然后需要按两次返回才退出，按第一次返回弹toast提示一下”

### 痛点根因分析：
1. **Compose `ModalNavigationDrawer` 缺少原生 BackHandler**：
   - Jetpack Compose Material 3 的 `ModalNavigationDrawer` 默认并未内置返回键拦截处理；
   - 当用户划开或点击汉堡按钮打开「专家矩阵」抽屉后，触发系统返回键/手势时，事件直接穿透至 `MainActivity` 默认的返回分发器，导致整个 App 瞬间退出被杀，严重打断用户查阅专家或切换会话的操作流程。
2. **主聊天界面缺少防误触退出保护**：
   - 聊天窗口是用户与多智能体长篇交互的核心工作区；
   - 没有双击防误触机制时，用户在聊天页误碰边缘返回手势就会立刻闪退退出，体验不佳。

---

## 2. 解决方案与实现详情

### 2.1 返回手势分层状态机拦截 (`ChatScreen.kt`)
通过 `androidx.activity.compose.BackHandler` 建立严谨的分层拦截机制：
1. **弹窗层优先关闭**：
   - 若当前处于大图预览（`previewImageModel != null`），返回键优先关闭大图；
   - 若处于模型与思考强度选择面板（`showModelBottomSheet`），返回键优先关闭面板；
2. **侧边栏抽屉（专家矩阵）平滑返回**：
   - 若抽屉处于展开态或展开动画态（`drawerState.isOpen || drawerState.targetValue == DrawerValue.Open`）：
     - 自动收起软键盘并清除焦点：`focusManager.clearFocus()`、`keyboardController?.hide()`；
     - 重置防误触时间戳：`lastBackPressTime = 0L`；
     - 平滑收起抽屉：`coroutineScope.launch { drawerState.close() }`；
     - 顺畅恢复至聊天主窗口。
3. **主界面双击退出防误触 (2000ms 窗口)**：
   - 处于聊天主窗口时：
     - **首次按返回**：更新 `lastBackPressTime = currentTime`，弹出轻量 Toast 提示 `「再按一次退出应用」`；
     - **2 秒内再次按返回**：立即取消当前 Toast 并调用 `activity?.finish()` 优雅退出；
     - **超时（> 2 秒）再按返回**：重新提示并重置计时窗口；
     - **状态隔离**：监听 `LaunchedEffect(drawerState.isOpen)`，只要抽屉关闭，必定重置 `lastBackPressTime = 0L`，确保回到主界面后始终需要双击，彻底杜绝误触连击闪退。

---

## 3. 验证要点
1. **侧边栏抽屉按返回**：
   - 点击顶部左上角汉堡按钮打开「Octop 专家矩阵」抽屉；
   - 按手机系统物理返回键或屏幕边缘返回滑动手势；
   - 抽屉顺滑收起，回到聊天主窗口，应用正常留存。
2. **主窗口单次返回弹 Toast**：
   - 在聊天主界面按一次返回键；
   - 弹出「再按一次退出应用」Toast 提示，界面不退出。
3. **主窗口 2 秒内双击退出**：
   - 在 Toast 提示消失前（2秒内）再次按下返回键；
   - 应用平滑退出到桌面。
