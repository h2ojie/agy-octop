package com.octop.client.data.model

import com.google.gson.annotations.SerializedName

data class ThreadItem(
    @SerializedName("thread_id") val threadId: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    @SerializedName("agent_id") val agentId: String? = null,
    @SerializedName("is_active") val isActive: Boolean = false,
    @SerializedName("model_ref") val modelRef: String? = null,
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null
)

data class CreateThreadResponse(
    @SerializedName("thread_id") val threadId: String,
    @SerializedName("session_key") val sessionKey: String? = null
)

