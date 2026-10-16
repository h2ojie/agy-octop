# Issue 016: 修复发送与暂停按钮过大问题，严格对齐 Antigravity 极致紧凑质感

## 1. 背景与用户反馈

用户上传了最新运行截图并指出：
- **发送按钮和暂停按钮过大，不美观**：
  在实际手机运行中，发送按钮（特别是流式生成时的暂停/停止按钮）渲染为一个巨大且突兀的粉红大圆圈，内部带一小方块，与整体界面严重失调；未生成时的发送按钮也比左侧的加号明显大出一圈。
- **用户要求**：发送与暂停按钮尺寸要缩小，视觉上要“抄 Antigravity 的样式”。

---

## 2. 根因剖析与改进架构

### 2.1 为什么之前的按钮尺寸会失控变大？
- 在 Material 3 体系中，`androidx.compose.material3.IconButton` 内部默认应用了 `minimumInteractiveComponentSize()` 限制（强制至少 48dp $\times$ 48dp 触摸交互靶心），并且其 token `StateLayerSize` 为 40dp。
- 当在外部传入 `.size(28.dp).background(color, CircleShape)` 时，Compose Modifiers 链与内部的交互区域强制扩充发生冲突，加之设备 DPI 与无障碍缩放影响，导致圆形背景被撑大至 60~80dp 的巨大圆盘。
- 此外，暂停按钮原先使用了 `MaterialTheme.colorScheme.error`，在 Material 3 深色模式下被解析为浅粉红色（`#FFB4AB`），造成强烈的廉价违和感。

### 2.2 解决方案：采用独立精确定制 `Surface` 结构
1. **彻底废除 `IconButton`，改用纯粹可控的 `Surface` 容器**：
   - 与左侧加号圆钮保持 100% 结构一致，直接使用：
     ```kotlin
     Surface(
         shape = CircleShape,
         color = ...,
         modifier = Modifier.size(28.dp).clip(CircleShape).clickable(...)
     )
     ```
   - 彻底摆脱 `minimumInteractiveComponentSize()` 的不可控膨胀，宽高被严格锁定在与左侧加号完全一致的 **28dp**。
2. **停止/暂停生成按钮风格全面对齐 Antigravity Web**：
   - **底色**：告别大粉红，采用深色模式下的高级灰白底色 `Color(0xFFE3E3E3)`（浅色模式下为纯黑底 `Color(0xFF1F1F1F)`）。
   - **中心图案**：中心居中放置尺寸为 **10dp**、带 2dp 微圆角的精致纯黑小方块（深色下为 `#131314`，浅色下为 `#FFFFFF`）。
3. **发送按钮状态配色对齐 Antigravity Web**：
   - **未激活/不可发送时**：底色为沉静低调暗灰 `Color(0xFF2A2C2E)`，搭配暗灰色微小箭头（14dp）。
   - **激活/可发送时**：底色高亮为灰白 `Color(0xFFE3E3E3)`，搭配黑色锐利小箭头。

---

## 3. 变更清单

1. `app/src/main/java/com/octop/client/ui/components/InputBar.kt`:
   - 移除 `IconButton`，全面改用精确定制的 `Surface` 按钮。
   - 暂停按钮采用 28dp 灰白圆圈 + 10dp 黑色圆角微方块。
   - 发送按钮采用 28dp 精准圆形，与加号圆钮高度绝对对称统一。

2. `app/build.gradle.kts`:
   - 版本号升级至 `versionCode 18`, `versionName "1.0.17"`。

---

## 4. 验证与发布

- `./gradlew assembleDebug` 编译通过。
- APK 归档并同步至本地、内网共享盘及 Google Drive。
- 源码打包并推送到 GitHub `jieiceone/octop-android`。
