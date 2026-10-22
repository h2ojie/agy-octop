package com.octop.client.data.model

import com.google.gson.annotations.SerializedName

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

data class AgentListResponse(
    @SerializedName("items") val items: List<AgentItem>? = null,
    @SerializedName("agents") val agents: List<AgentItem>? = null
)
