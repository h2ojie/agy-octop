# Issue 005: 顶部状态栏显示为纯白横条，与暗色主题/顶部栏颜色脱节

- **问题编号**：ISSUE-005
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：
  - `Theme.kt`: 状态栏与导航栏颜色与图标反色动态联动 (`SideEffect` + `WindowInsetsControllerCompat`)
  - `themes.xml`: Activity 基础样式配置暗黑底色与 `#1E2229` 状态栏，彻底消除冷启动与默认背景白条
  - `colors.xml`: 补齐 `surface_dark` 与 `window_background_dark` 颜色资源
  - `ChatScreen.kt`: `TopAppBar` 显式绑定 `MaterialTheme.colorScheme.surface` 容器色
  - `app/build.gradle.kts`: 版本号递增至 `v1.0.4` (versionCode = 5)
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

用户在 Android 手机上打开应用后（暗色主题下）：
1. **状态栏亮白刺眼**：屏幕最顶部的系统状态栏呈现为一条纯白底色（`#FBFBFD`）的横条，与下方暗黑色（`#10131C` / `#1E2229`）的标题栏和整个对话界面形成强烈的割裂感；
2. **状态栏图标不可读**：由于手机系统处于暗黑环境，系统图标（时间、电量、Wi-Fi、微信等通知图标）为浅白字体，显示在纯白状态栏上几乎完全隐形看不清。

---

## 二、 原因分析 (Root Cause Analysis)

1. **XML 主题继承自 Light，且状态栏为 transparent 却未开启 edge-to-edge**：
   - 原 `themes.xml` 中配置：
     ```xml
     <style name="Theme.Octop" parent="android:Theme.Material.Light.NoActionBar">
         <item name="android:statusBarColor">@android:color/transparent</item>
     </style>
     ```
   - 父级主题为 `Light`，窗口装饰背景（`windowBackground`）为默认白色；
   - 当 `statusBarColor` 设为透明而 Activity 未使用无界全面屏（edge-to-edge）绘制时，底层透出的正是 Activity 的默认白色 DecorView 背景。
2. **Compose 主题 `Theme.kt` 中未对 Window 的状态栏进行同步更新**：
   - 原 `OctopTheme` 仅配置了 Compose 内层的 `MaterialTheme(colorScheme)`；
   - 未通过 `SideEffect` 与 `WindowInsetsControllerCompat` 将系统的 `window.statusBarColor` 和 `isAppearanceLightStatusBars` 与当前的 `colorScheme.surface` 建立双向绑定；
   - 导致界面进入 Compose 渲染后，顶层的系统状态栏仍然停留在 Window 的冷启动浅色配置上。

---

## 三、 解决方案 (Fix & Implementation)

1. **重构 XML 原生层主题 (`themes.xml`, `colors.xml`)**：
   - 将 `Theme.Octop` 的父主题切换为 `android:Theme.Material.NoActionBar`；
   - 显式声明 `statusBarColor` 与 `navigationBarColor` 为 `#1E2229`（`surface_dark`），`windowBackground` 为 `#121418`，`windowLightStatusBar` 为 `false`；
   - 从冷启动阶段便保证无白色闪屏或白色状态栏。
2. **在 Compose `Theme.kt` 中注入状态栏动态联动**：
   - 提取当前窗口的 `WindowInsetsControllerCompat`；
   - 动态将 `window.statusBarColor` 赋为 `colorScheme.surface.toArgb()`；
   - 根据 `darkTheme` 动态配置 `isAppearanceLightStatusBars = !darkTheme`（暗色模式下图标为纯白，浅色模式下为深灰）；
   - 锁定 `dynamicColor = false`，保证品牌暗色调一致性。
3. **`TopAppBar` 显式使用 `colorScheme.surface` 容器色 (`ChatScreen.kt`)**：
   - 保证状态栏与标题栏颜色严格一致，浑然一体。

---

## 四、 验证与效果 (Verification)

- 顶部状态栏与 `TopAppBar` 完美融为一体（颜色均为 `#1E2229`），彻底消除了顶部白色横条；
- 状态栏的时间、电池、Wi-Fi 与应用通知图标清晰呈现为白色高亮文本；
- 整体沉浸感达到主流旗舰移动应用的视觉水准。
