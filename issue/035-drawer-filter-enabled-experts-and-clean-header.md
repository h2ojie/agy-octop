# Issue 035: 侧边栏专家矩阵仅罗列 Enabled 专家与顶部极简标题规范 (v1.0.36)

## 1. 需求背景与痛点
用户反馈并配图：
> “我们这个页面只罗列 enable 的专家，上面保留图标和专家矩阵这几个字”

### 痛点根因分析：
1. **未对齐 Octop 云端「启用/停用」生命周期状态**：
   - Octop 专家的运行时生命周期包含 `running`（已启用）、`stopped`（已停用）等状态；
   - 移动端原先直接遍历所有专家返回列表，未对 `state` 或 `enabled` 状态进行过滤，导致在 Web 后台已被停用的专家（例如 `运维工程师 Ops`）仍然强行呈现在移动端侧边栏中；
2. **顶部副标题文案冗余且与真实状态冲突**：
   - 原先抽屉顶部硬编码写死「Octop 专家矩阵」及副标题「8位专属领域 Agent 参谋」；
   - 当用户停用某位专家时，副标题的“8位”数量与实际可用专家数量发生冲突，且视觉层级繁冗。

---

## 2. 解决方案与实现详情

### 2.1 专家模型生命周期感知扩展 (`Agent.kt`)
在 `AgentItem` 中补充反序列化字段并提供高阶状态判断计算属性：
```kotlin
data class AgentItem(
    @SerializedName("agent_id") val agentId: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("color") val colorHex: String? = null,
    @SerializedName("icon_name") val iconName: String? = null,
    @SerializedName("welcome_message") val welcomeMessage: String? = null,
    @SerializedName("persona_mbti") val personaMbti: String? = null,
    @SerializedName("default_model") val defaultModel: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("is_enabled") val isEnabled: Boolean? = null,
    @SerializedName("bridge_disconnected") val bridgeDisconnected: Boolean? = null
) {
    val isAgentEnabled: Boolean
        get() {
            if (enabled == false || isEnabled == false) return false
            if (state != null) {
                val s = state.trim().lowercase()
                if (s == "stopped" || s == "disabled") return false
                if (s == "running" || s == "starting" || s == "idle" || s == "active") return true
            }
            if (bridgeDisconnected == true) return true
            return enabled ?: isEnabled ?: (state == null || state.lowercase() != "stopped")
        }
}
```

### 2.2 抽屉侧边栏顶部视觉精简 (`DrawerContent.kt`)
1. **顶部 Header 极简化**：
   - 移除原有硬编码副标题「8位专属领域 Agent 参谋」；
   - 移除前缀「Octop」，保留精致绿底机器人图标及纯粹文字**「专家矩阵」**，单行居中垂直对齐，信息密度更高、现代感更强；
   - 升级废弃的 `Divider` 为 Material 3 `HorizontalDivider`。
2. **列表仅渲染 Enabled 专家**：
   - 通过 `val enabledAgents = remember(agents) { agents.filter { it.isAgentEnabled } }` 深度过滤；
   - `LazyColumn` 仅呈现已启用的专家，停用的专家不再出现于列表中。

### 2.3 默认选中专家自动降级对齐 (`ChatViewModel.kt`)
- 在 `loadAgents()` 中，当加载完成时，优先选中处于已启用（`isAgentEnabled`）的第一个专家，避免启动时默认选中了被停用的专家。

---

## 3. 验证要点
1. **抽屉顶部视觉**：
   - 打开侧边栏抽屉，顶部保留绿底机器人圆形图标，右侧仅显示**「专家矩阵」**大字，下方无多余副标题；
2. **已停用专家过滤**：
   - 本地 `WTNWE4`（运维工程师 Ops）处于 `stopped` 状态，侧边栏列表中已不再出现该条目；
   - 仅显示当前处于 `running` 启用的 7 位垂直领域专家；
3. **专家会话与交互**：
   - 展开、折叠、切换会话及新建会话功能完全不受影响。
