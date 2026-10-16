# Issue 014: 吸顶长提问展开垂直滑动浏览与极简 Antigravity 网页端输入栏样式

## 1. 背景与用户反馈

在体验了 v1.0.14 版本后，用户提出了两个关键的交互与视觉优化点：
1. **长提问吸顶展开后支持向下拉动浏览**：
   - 当用户发送的消息很长（几十行代码或多段文本），展开吸顶卡片后，因卡片缺乏高度约束与内部垂直滚动，卡片占满大部分屏幕并截断底部文字，用户无法滑动或向下拖拉查看未显示的全部内容。
2. **输入框对齐 Antigravity 网页端极致简洁风格**：
   - 彻底去除外层深灰/暗色底栏色块，输入卡片直接自然贴合在页面底部；
   - 发送按钮变小（28dp 小圆形按钮，内部带右向箭头），在未输入时沉静暗色，可发送时高亮；
   - 左下角紧贴呈现 `+` 号附件圆钮与当前接入模型（如 `Gemini 2.5 Flash` 或专家模型配置）的药丸胶囊，完全对齐 Antigravity Web 界面。

---

## 2. 根因与优化方案

### 2.1 长提问卡片垂直滚动支持 (`UserPromptCard`)
- **高度约束与内部滑动**：为展开状态下的内容容器引入 `Modifier.heightIn(max = (screenHeightDp * 0.45f).coerceIn(180.dp, 360.dp)).verticalScroll(scrollState)`。
- **手势冲突隔离**：原卡片在外层 `Surface` 绑定了全卡片点击折叠事件，导致用户在展开状态下试图拖动滑动时容易误触发折叠。将全卡片点击展开仅限定在 `isSticky && isCollapsed`（折叠为 1 行模式时）；展开后在卡片顶部明确提供“提问内容 点击收拢 ∧”顶栏，文字区域完全释放给纵向滑动手势，用户可以顺畅向下拉动阅读每一行文本。

### 2.2 输入框极简样式与模型药丸 (`InputBar`)
- **移除底栏外层高程色块**：将外层 `Surface` 替换为无阴影透明底衬 `Box(background(background)) + imePadding()`，卡片自然融入聊天背景。
- **发送按钮精细化**：尺寸由 34dp 紧凑至 28dp 圆形按钮，搭配 14dp `ArrowForward` 图标。深色模式下激活时为高亮灰白底黑标，未激活时为微暗沉灰色，停止生成时为红色圆形停止标。
- **左下角加号与模型胶囊**：
  - `+` 号按钮：28dp 极简圆形轻量按钮，点击弹出拍照/相册/文档菜单。
  - 模型胶囊：12dp 圆角药丸底色，展示 `currentModelName`（格式化为 `Gemini 2.5 Flash` / `Claude 3.5 Sonnet` 等标准名），右侧带下拉小箭头。

---

## 3. 变更清单

1. `app/src/main/java/com/octop/client/ui/components/ChatBubble.kt`:
   - 引入 `LocalConfiguration` 与 `verticalScroll`。
   - `UserPromptCard` 展开状态添加最大高度限制（45% 屏幕高度）与垂直滚动。
   - 区分折叠时全卡片展开与展开时的独立折叠顶栏，杜绝滑动与点击手势冲突。

2. `app/src/main/java/com/octop/client/ui/components/InputBar.kt`:
   - 移除外层有色 Surface 边框衬底，直接融合背景。
   - 发送/停止按钮缩小为 28dp 紧凑圆形。
   - 左下角排列 `+` 号圆钮与模型药丸胶囊。

3. `app/src/main/java/com/octop/client/ui/ChatScreen.kt`:
   - 新增模型名称规范化格式逻辑，向 `InputBar` 传递 `currentModelName`。

4. `app/build.gradle.kts`:
   - 版本号升级至 `versionCode 16`, `versionName "1.0.15"`。

---

## 4. 验证与发布

- `./gradlew assembleDebug` 编译通过。
- APK 复制归档至本地、工具盘及 Google Drive。
- 源码归档更新并推送至 GitHub `jieiceone/octop-android`。
