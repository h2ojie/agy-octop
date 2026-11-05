# Issue 045: 专家矩阵抽屉与前台自愈自动同步 (v1.0.46)

## 1. 需求背景与痛点
用户反馈：
> “安卓apk 里面，右滑 左边的抽屉， 专家矩阵 人数是固定的吗，我现在加到9个了，apk 里面还是显示8个。”

### 痛点根因分析：
1. **专家列表缺少生命周期动态同步**：
   - 移动端原先仅在 `ChatViewModel.init`（App 进程冷启动）与 `login()`（重新登录）时调用一次 `loadAgents()`；
   - 当用户在 Octop Web 端或控制台新增、修改或删除专家（如新增第 9 位专家 `jiaoyuan` 教员）时，由于移动端长时间驻留后台保活，`onForegroundResume()` 仅恢复了会话 WebSocket 与历史，未触发专家列表重新拉取；
   - 导致内存中持续保留冷启动时的旧列表，用户即便多次右滑展开左侧抽屉，看到的依然是旧的 8 个专家。
2. **列表覆盖时当前选中专家防抖与元数据更新**：
   - 原先在 `loadAgents()` 成功拉取新列表后，若当前选中的专家仍然有效，仅保持原引用，未能同步云端更新后的最新模型、名称或 MBTI 等元数据；
3. **离线默认专家列表 (defaultExperts) 滞后**：
   - 本地兜底列表中仅有 8 位早期专家，缺少新近接入的孔夫子（`3MM121`）与教员（`jiaoyuan`）。

---

## 2. 解决方案与实现详情

### 2.1 右滑抽屉与切前台双触发自动同步 (`ChatScreen.kt` & `ChatViewModel.kt`)
1. **右滑展开抽屉自动拉取**：
   在 `ChatScreen.kt` 中监听 `drawerState.isOpen`：每次用户右滑打开或点击汉堡按钮展开抽屉时，异步触发 `viewModel.loadAgents()`，实现专家矩阵打开即最新：
   ```kotlin
   LaunchedEffect(drawerState.isOpen) {
       if (drawerState.isOpen) {
           viewModel.loadAgents()
       } else {
           lastBackPressTime = 0L
       }
   }
   ```
2. **切回前台自动拉取**：
   在 `ChatViewModel.kt` 的 `onForegroundResume()` 头部增加 `loadAgents()`，用户从系统其他应用或锁屏切回时无感对齐云端专家矩阵。

### 2.2 专家重载安全防抖与元数据更新 (`ChatViewModel.kt`)
优化 `loadAgents()` 内部更新策略：
- 当云端返回最新专家列表时，优先保持当前选中的专家不动（避免用户输入或阅读中断）；
- 同时从新列表中找到同名专家条目，无缝对齐最新元数据；
- 若当前专家在云端已被删除或禁用，则平滑降级切换至启用的首个专家。

### 2.3 离线默认专家完整扩充 (`ChatViewModel.kt`)
在 `defaultExperts` 中补全第 8 位（`3MM121` 孔夫子 · 思想导师）与第 9 位（`jiaoyuan` 教员 · 战略实践导师），保证离线或首启冷启动兜底数据完整。

---

## 3. 验证结果
1. **自动化单元测试**：
   - 新增 `AgentItemTest`，覆盖 `isAgentEnabled` 生命周期判定及 `defaultExperts` 包含全部已启用的专家（验证 9 位 running 专家全部就绪），测试通过。
2. **构建与分发**：
   - APK 成功构建，版本号递增至 `v1.0.46 (47)`；
   - 自动同步分发至家庭 NAS（`/Volumes/tools/apk/octop-v1.0.46.apk`）与 Google Drive。
