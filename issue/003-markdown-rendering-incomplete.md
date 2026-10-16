# Issue 003: Markdown 多级标题、列表序号与行内代码块未正确渲染

- **问题编号**：ISSUE-003
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：Android 客户端 (`build.gradle.kts`, `MarkdownRenderer.kt`, `ChatBubble.kt`)
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

在 Android 客户端与专家对话时，AI 回复的结构化 Markdown 文本未被渲染为富文本视觉层级（如图）：
- **标题未渲染**：展示为字面量的 `### 一、为什么此前所有任务对话都显示为 \`new chat\`？`，包含了原始的 `###` 和反引号，且字体字号与普通正文完全无异；
- **行内代码未高亮**：形如 `` `fresh_thread: true` ``、`` `feishu` ``、`` `NULL` `` 等技术标记原样显示带反引号的文字，未显示为具有背景药丸的高亮等宽样式；
- **列表未分层对齐**：有序列表项 `1. `、`2. `、`3. ` 仅作为换行普通文本，缺少原生缩进与强调序号。

---

## 二、 原因分析 (Root Cause Analysis)

### 1. 原代码缺少 Markdown 语法树 (AST) 解析器

在先前版本的 `ChatBubble.kt` 中，`FormattedText` 的实现过于初级：
```kotlin
@Composable
fun FormattedText(text: String) {
    val annotatedString = buildAnnotatedString {
        val parts = text.split("**")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(part)
                }
            } else {
                append(part)
            }
        }
    }
    Text(text = annotatedString, fontSize = 15.sp, ...)
}
```
该函数仅仅利用字符串 `split("**")` 粗暴加粗了双星号之间的文本，对于：
1. **多级标题**（`#`、`##`、`###`）：未按行识别，未赋予对应字号与行间距；
2. **行内代码**（`` `code` ``）：未识别单反引号，未配置背景底色与等宽字体；
3. **列表排版**（`1. `、`- `、`* `）：未建立左右栅格对齐容器；
4. **引用块**（`> `）与**分割线**（`---`）：未渲染边框引线与分割组件。

---

## 三、 解决方案 (Fix & Implementation)

### 1. 引入标准 CommonMark AST 引擎 (`build.gradle.kts`)

引入轻量级、无 UI 绑定的权威 Markdown 语法树解析库：
```kotlin
// app/build.gradle.kts
implementation("org.commonmark:commonmark:0.21.0")
implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
```

### 2. 编写原生的 Compose 树形渲染器 (`MarkdownRenderer.kt`)

利用 CommonMark 构建访问者树，深度递归解析每一个 Block 与 Inline 节点，映射为纯正的 Material 3 Compose 组件：

1. **多级标题 (`Heading`)**：
   - `level 1`: `20.sp`, `FontWeight.Bold`, 品牌色, `lineHeight = 28.sp`；
   - `level 2`: `18.sp`, `FontWeight.Bold`, `lineHeight = 26.sp`；
   - `level 3`: `16.sp`, `FontWeight.Bold`, `lineHeight = 24.sp`；
   - `level 4+`: `15.sp`, `FontWeight.SemiBold`；
   - 自动赋予呼吸感的上下边距，与正文自然区隔。
2. **行内代码块 (`Code`)**：
   - 样式提升为 **代码药丸**：等宽字体 `FontFamily.Monospace`、字号 `13.5.sp`；
   - 浅色模式赋予 `0xFFE2E8F0` 浅灰底色 + `0xFFC05621` 暖橘高亮；
   - 深色模式赋予 `0xFF2D3748` 深灰底色 + `0xFFF6AD55` 橙金高亮；
   - 彻底清除生硬的前后反引号 `` ` ``。
3. **有序与无序列表 (`OrderedList` / `BulletList`)**：
   - 采用 `Row` 栅格，左侧常驻等宽序号徽标（`1.`、`2.`、`•`），右侧承载内容列并保持左对齐缩进。
4. **引用块 (`BlockQuote`)**：
   - 左侧渲染 3.5dp 宽度的高亮垂直竖线（带圆角），右侧内容以半透明色调呈现。
5. **代码围栏 (`FencedCodeBlock`)**：
   - 保留深色卡片、语言 Badge（如 `KOTLIN`、`SQL`）以及一键复制至剪贴板功能。

### 3. 在 `ChatBubble.kt` 中全面替换渲染调用

在气泡正文处彻底弃用旧的粗暴分词逻辑，直接对接 `MarkdownView`：
```kotlin
// 2. 正文 Markdown 渲染
if (cleanContent.isNotBlank()) {
    MarkdownView(
        content = cleanContent,
        isStreaming = message.isStreaming
    )
}
```

---

## 四、 验证与结果 (Verification & Results)

1. **编译与版本自增**：
   - 版本自增为 **`v1.0.2`**（`versionCode = 3`, `versionName = "1.0.2"`）；
   - `./gradlew assembleDebug` 耗时 26 秒构建成功（产物 15MB）。
2. **发布与双向部署**：
   - 归档至共享存储：`/Volumes/tools/apk/octop-v1.0.2.apk`（并更新 `octop-v1.0.0.apk`）；
   - 部署至公网中转服务器：`/var/www/html/octop.apk`；
   - MD5 校验一致：`576d2f33c5caf72476fd8130dafa5fae`。
3. **视觉效果**：
   - `### 一、为什么...` 渲染为大号加粗主标题；
   - `` `new chat` ``、`` `fresh_thread: true` `` 渲染为精致的行内代码高亮胶囊；
   - `1. 独立线程重置`、`2. 外部渠道未回填标题` 渲染为对齐排版的数字列表。
