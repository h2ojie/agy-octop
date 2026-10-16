# Issue 031: 深度 Code Review 采纳落地与数据层/附件管线重构 (v1.0.32)

## 1. 背景与评审意见
针对 Octop Android 客户端进行深度 Code Review，提出以下改进点：
1. **网络与数据层**：
   - `ApiClient.api` 每次调用重复执行 `retrofit.create()` 带来的动态代理与反射方法扫描开销；
   - 弱类型 `JsonElement` 响应导致解析逻辑散落并泄漏到 ViewModel 中；
   - URL 构建使用 `URLEncoder` 会将空格转为 `+`，不满足 RFC 3986 路径规范；
   - 数据模型命名规范统一（驼峰配合 `@SerializedName`）。
2. **UI 与 Compose 渲染层**：
   - 吸顶头部在列表滑动时的 `isSticky` 计算需要通过 `derivedStateOf` 优化，避免每一帧无谓重组；
   - Markdown 表格列宽计算需适配系统字号缩放（`fontScale`）。
3. **工程卫生与安全性**：
   - 清理历史归档包 `app/src.zip` 与废弃组件 `FullscreenInputModal.kt`；
   - `ChatViewModel` 上千行代码的职责拆分（数据持久化、附件上传压缩）；
   - 网络层 401 Unauthorized 自动清理与重新登录弹窗打通。

---

## 2. 重构与实现详情

### 2.1 网络层单例缓存与 401 拦截 (`OctopApiService.kt`)
- **动态代理实例缓存**：增加 `@Volatile private var apiService: OctopApi? = null`，仅当 `currentBaseUrl != url` 时重建 Retrofit 并创建新的 `apiService`。
- **401 Unauthorized 统一拦截**：在 `ApiClient.okHttpClient` 中注入拦截器，对非登录接口的 401 响应触发 `NetworkConfig.notifyAuthExpired()`。
- **DTO 规范统一**：`LoginRequest` 的 `captchaToken`、`UploadAttachmentResponse` 的 `workspacePath`, `accessUrl`, `mediaType` 采用驼峰命名并显式标注 `@SerializedName`，同时保留兼容属性别名。

### 2.2 安全 URL 构建 (`NetworkConfig.kt`)
- 移除 `java.net.URLEncoder` 手动字符串拼接；
- 全面使用 OkHttp `HttpUrl.toHttpUrlOrNull()?.newBuilder()` 自动处理路径与 Query 参数的 RFC 3986 标准编码。
- 增加 `onAuthExpired` 会话失效回调，支持跨模块通知。

### 2.3 抽取统一数据仓库层 (`OctopRepository.kt`)
- 新建 `com.octop.client.data.repository.OctopRepository`；
- 将 `getAgents()`、`getThreads()`、`createThread()`、`getThreadHistory()`、`login()`、`getResolvedModels()`、`updateThreadComposer()` 统一收敛至 Repository；
- 封装内部的 Gson 多态解析器（`parseAgentItems`、`parseThreadItems`），ViewModel 完全与弱类型 `JsonElement` 解耦，直接接收强类型 Result。

### 2.4 抽取独立附件上传管线 (`AttachmentUploadPipeline.kt`)
- 新建 `com.octop.client.util.AttachmentUploadPipeline`；
- 封装图片智能压缩（`ImageCompressor`）、缓存文件流拷贝、Multipart 请求构建、网络上传及临时文件 `tempToClean` 安全清理；
- 简化 `ChatViewModel` 中的附件上传方法至 20 余行。

### 2.5 Compose 渲染与系统字号自适应
- **`ChatScreen.kt`**：吸顶状态判定包裹至 `remember(headerIndex, nextHeaderIndex) { derivedStateOf { ... } }`，只有在吸顶布尔值翻转时才触发重组。
- **`MarkdownRenderer.kt`**：引入 `LocalDensity.current.fontScale`，表格中英文字符宽度基数、内边距和最小/最大宽度跟随系统无障碍/大字号设置等比缩放。

### 2.6 工程卫生清理
- 彻底移除 `app/src.zip` 二进制包；
- 彻底删除已废弃且 0 处引用的 `FullscreenInputModal.kt`；
- `.gitignore` 强化忽略 `*.zip` 与 `*.apk`。

---

## 3. 验证结果
- 执行 `./gradlew assembleDebug`，34 个 task 100% 成功编译，耗时 1 分 12 秒，顺利生成 `app-debug.apk`。
- 零编译错误，代码架构大幅清晰整洁。
