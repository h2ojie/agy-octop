# Issue 032: 修复 AI 响应卡片底部模型元数据重复服务商标称问题 (v1.0.33)

## 1. 痛点现象
在 AI 回复卡片底部元数据行中，模型标识显示为：
`ice / Ice / GPT 6.1 Sol · 10月16日 02:05`
出现了两次重复的服务商代码（`ice / Ice / ...`），且破损了原本 `GPT-6.1 Sol` 的破折号连字符样式。

---

## 2. 根因分析
1. **多层格式化叠加**：
   - 在历史记录同步或状态聚合时，消息模型字段可能已经被存入了包含服务商前缀的格式化字符串（如 `"ice / GPT-6.1 Sol"`）；
   - 在 `ChatBubble.kt` 中渲染时，再次调用了 `ResolvedModel.formatModelWithProvider(it)` 对其进行二次格式化。
2. **前缀剥离不彻底与 Fallback 误伤**：
   - 原先的 `formatModelName(raw)` 仅处理了 `removePrefix("ice/")`，未考虑带空格的 `"ice / "`；
   - 包含空格的前缀未被剔除，导致字符串无法命中 `gpt-6.1-sol` 预置映射，跌入通用 fallback 逻辑；
   - 通用 fallback 逻辑将整段字符串按空格切词并将首字母大写，把 `"ice"` 处理成了 `"Ice"`，再拼上外层提取的 `prov`（`"ice"`），最终组装成 `"ice / Ice / GPT 6.1 Sol"`。

---

## 3. 修复方案
1. **彻底剥离服务商前缀**：
   - 在 `formatModelName(raw)` 中增加斜杠判定：若输入包含斜杠，直接取 `raw.substringAfterLast("/").trim()` 获取最内层纯模型名；
   - 彻底清理 `"ice / "`, `"cli / "`, `"ice/"`, `"cli/"` 等变体前缀及 `"-cli"` 后缀。
2. **幂等格式化保障**：
   - 无论输入是 `gpt-6.1-sol`、`ice/gpt-6.1-sol`、`ice / GPT-6.1 Sol` 还是带历史脏数据的 `ice / Ice / GPT 6.1 Sol`，均稳定收敛并格式化为 `ice / GPT-6.1 Sol`。
3. **版本迭代**：
   - 升级至 `v1.0.33`（versionCode: 34）。
