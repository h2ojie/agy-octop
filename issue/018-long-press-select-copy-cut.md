# Issue 018: 长按文本选中、复制与剪切能力全面支持

## 1. 背景与用户诉求
用户反馈：
> “增加一下长按 可以选中 然后复制、剪切”

在之前的版本中：
1. **输入框 (`InputBar`)**：
   - 使用 `BasicTextField(value: String, onValueChange: (String) -> Unit)`，因仅持有纯字符串状态且在 recomposition 周期中重置，导致用户长按时无法稳定呼出 Android 文本选择浮层（剪切、复制、粘贴、全选），或滑动选择句柄时发生选区丢失。
2. **提问与回复气泡 (`UserPromptCard` / `AiResponseCard` / `MarkdownView`)**：
   - 气泡文本使用普通 `Text` 渲染，未包裹 `SelectionContainer`，导致用户长按提问或 AI 的 Markdown 回复（段落、列表、代码块、表格、思考过程）时完全无响应，无法长按选中指定文字进行复制。

## 2. 解决方案与实施细节

### 2.1 输入框：升级为 `TextFieldValue` 保持完整选择区与剪贴板操作
在 `InputBar.kt` 中：
```kotlin
// 使用 TextFieldValue 维持输入框精确选择区与光标，原生支持长按选中、拖动滑块、复制、剪切与粘贴
var textFieldValue by remember {
    mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
}

LaunchedEffect(text) {
    if (text != textFieldValue.text) {
        textFieldValue = textFieldValue.copy(
            text = text,
            selection = TextRange(text.length)
        )
    }
}

BasicTextField(
    value = textFieldValue,
    onValueChange = { newValue ->
        textFieldValue = newValue
        if (newValue.text != text) {
            onTextChange(newValue.text)
        }
    },
    ...
)
```
- **长按文本**：弹出系统悬浮工具栏，显示 **剪切 (Cut) | 复制 (Copy) | 粘贴 (Paste) | 全选 (Select All)**。
- **剪切**：选中的内容被剪切至剪贴板，输入框相应删除，并同步通知 ViewModel。
- **复制**：选中的文字复制到系统剪贴板。
- **拖动选择手柄**：`TextFieldValue.selection` 稳定跟踪手柄拖动，不触发多余重组。

### 2.2 用户提问卡片 (`UserPromptCard`)：长按选词与快捷复制
在 `ChatBubble.kt` 中：
1. **单行折叠模式**：
   - 使用 `Modifier.combinedClickable`：单点展开，长按自动复制整段提问内容至剪贴板，并提示 Toast “已复制提问内容”。
2. **完整卡片模式**：
   - 顶部收拢栏新增“复制”按钮，可一键复制全部提问。
   - 核心提问内容文字使用 `SelectionContainer` 包裹，用户长按任意文字均可唤起双水滴选择滑块，自由选取单词、句子并进行“复制”或“全选”。

### 2.3 AI 回复与思考区 (`MarkdownView` / `ThinkingBlock` / `CodeBlock`)：
1. **Markdown 正文**：
   - 在 `MarkdownRenderer.kt` 中使用 `SelectionContainer` 包裹整个 `Column`，Markdown 的标题、段落、引用、列表、表格内容均原生支持长按选中与复制。
   - 流式光标使用 `DisableSelection` 隔离，避免选中。
2. **思考过程 (`ThinkingBlock`)**：
   - 展开状态下的思考文本包裹于 `SelectionContainer`，长按可选取深度思考内容复制。
3. **代码块 (`CodeBlock`)**：
   - 顶部语言标签与一键“复制”按钮使用 `DisableSelection` 隔离；代码主体支持长按拖动选中部分代码或点击一键全量复制。

## 3. 验证与分发
1. 执行 `./gradlew assembleDebug`，编译通过。
2. 版本号升级至 `1.0.19`（versionCode: 20）。
3. 同步 APK 至本地、局域网共享盘、Google Drive，并推送代码至 GitHub `origin main`。
