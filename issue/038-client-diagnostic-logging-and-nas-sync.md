# Issue 038: 移动端诊断与运行日志系统落地及云端/NAS一键上传分析 (v1.0.39)

## 1. 需求背景与痛点
在移动端涉及 **WebSocket 长连接、切后台重连、网络弱网恢复、手势吸顶折叠与分页时间戳校验** 的复杂场景下，排查偶发异常过去往往依靠用户截图或口头描述，缺少精确的操作事件上下文与堆栈定位。

用户期望：
1. **本地关键日志持久化**：将长连接状态、重连、交互手势、未捕获异常精准记录在手机沙盒中；
2. **开关控制**：默认先开启，方便排查；后续稳定后可在设置中一键关闭，关闭时 0 I/O 零开销；
3. **一键上传至 NAS / Google Drive**：手机端提供按钮一键打包上传，可在 Mac 端的 Google Drive / NAS 直接查阅日志分析；
4. **系统级辅助分享与手机端实时预览**：支持在手机端直接查看最新 150 行日志（深色终端样式，一键复制），并支持系统原生分享菜单转存至其他 App。

---

## 2. 方案实现与架构细节

### 2.1 异步非阻塞日志核心 (`DiagnosticLogger.kt`)
- **零阻塞 IO 架构**：采用单线程后台协程与 `Channel<String>` 异步缓冲区，日志格式化落盘完全独立于主线程，绝不影响 UI 渲染与 WebSocket 通信吞吐；
- **分级标签规范**：
  - `[WS]`：连接建立、重连退避、Ping/Pong 心跳、关闭与异常；
  - `[NET]`：HTTP 上传、接口调用状态码及错误；
  - `[STATE]`：应用前后台生命周期、专家切换、会话切换、历史消息分页拉取与时间戳倒流自愈检测；
  - `[UI]`：提问吸顶卡片展开/收拢手势、手指拖拽列表自动收拢、返回键拦截状态；
  - `[CRASH]`：全局未捕获异常处理器捕获的闪退堆栈。
- **敏感信息自动脱敏**：正则匹配 Token、AppKey、Password、Authorization 自动替换为 `***`；
- **滚动与存储保护**：
  - 存储目录：`context.filesDir/diagnostic_logs/`；
  - 单文件超 4MB 自动轮转（`octop_yyyyMMdd_*.log`）；
  - 最多保留最近 5 天日志，超期后台静默自动清理，绝不膨胀占用存储。
- **全局 Crash 守护者**：
  - 注册 `Thread.setDefaultUncaughtExceptionHandler`；
  - 闪退瞬间同步写入完整堆栈、设备型号（Brand/Model）、系统版本（Android OS / SDK）与应用版本。

### 2.2 用户界面与交互设计 (`DiagnosticModal.kt` & `ChatScreen.kt`)
- **入口**：顶部右侧更多选项 (`MoreVert`) -> 新增「诊断与系统日志」；
- **开关控制**：
  - `启用本地运行日志` Switch 开关（默认开），状态持久化在 `SharedPreferences`；
  - 关闭时，所有 `d()` / `i()` / `w()` 内部执行 `if (!isEnabled) return`，保持 0 CPU / 0 I/O 损耗。
- **快捷操作**：
  - **「上传至 NAS/云端」**：一键将最近日志打包为 `.zip`，通过现有专线通道回传并落盘；
  - **「分享导出」**：调起 Android 系统原生 `Intent.ACTION_SEND`，用户可选择手机上的 Google 云端硬盘 App、微信或文件管理器；
  - **「清空」**：一键彻底清理本地所有 log 与临时 zip，释放存储。
- **终端风格日志查看器**：
  - 深色黑底代码框 (`#141619`)，Monospace 等宽字体，支持水平和垂直双向滚动；
  - 实时显示最新 150 行日志，提供「刷新」与「一键复制」功能。

### 2.3 Mac 端与 Google Drive / NAS 自动协同 (`scripts/sync_diagnostic_logs.sh`)
- 手机端上传的日志包落盘至 Mac 端 Octop 工作区后，本地协同同步脚本自动检索并分发至：
  1. Google Drive：`$HOME/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-logs/`；
  2. 家庭 NAS 共享盘：`/Volumes/tools/octop-logs/`；
  3. 本地归档目录：`$HOME/octop-logs/`。
- Antigravity 可直接在 Mac 终端秒级读取日志并生成深度排障报告。

---

## 3. 验证与分发产物

- 编译调试：`./gradlew assembleDebug`，编译通过且零报错；
- 版本号演进：`v1.0.39` (VersionCode: 40)；
- 多渠道分发：
  - 本地：`octop-v1.0.39.apk`
  - 局域网共享盘：`/Volumes/tools/apk/octop-v1.0.39.apk`
  - Google Drive：`我的云端硬盘/octop-apk/octop-v1.0.39.apk`
  - VPS 公网下载：`http://47.130.249.193/octop.apk`
