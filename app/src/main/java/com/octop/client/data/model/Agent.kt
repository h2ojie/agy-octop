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
    @SerializedName("default_model") val defaultModel: String? = null
)

data class AgentListResponse(
    @SerializedName("items") val items: List<AgentItem>? = null,
    @SerializedName("agents") val agents: List<AgentItem>? = null
)
