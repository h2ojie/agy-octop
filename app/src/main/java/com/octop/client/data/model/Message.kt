package com.octop.client.data.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

enum class AttachmentStatus {
    PENDING,
    UPLOADING,
    SUCCESS,
    ERROR
}

data class AttachmentItem(
    val id: String = UUID.randomUUID().toString(),
    val localUriString: String? = null, // 本地 URI 字符串，安全可反序列化，避免 Gson 抽象类异常
    val filename: String = "",
    val mimeType: String = "",
    val size: Long = 0,
    val isImage: Boolean = false,
    val status: AttachmentStatus = AttachmentStatus.PENDING,
    val workspacePath: String? = null,  // 服务端相对路径，例如 "inbound/20261015_xxx.jpg"
    val previewUrl: String? = null,     // 服务端返回的预览相对 URL
    val uploadedAgentId: String? = null,// 绑定的专家 ID，防止跨专家工作区污染
    val targetConversationKey: String? = null, // 绑定的会话 Key: "$agentId:$threadId"，防止跨会话串传
    val errorMessage: String? = null
)

data class HitlActionRequest(
    val name: String = "",
    val args: Map<String, Any?>? = null,
    val description: String? = null
)

data class HitlPendingInfo(
    val pendingId: String? = null,
    val actionRequests: List<HitlActionRequest> = emptyList(),
    val rawJson: String? = null
)

data class HitlDecision(
    val type: String,
    val message: String? = null
)

data class HitlResumeBody(
    val thread_id: String,
    val decisions: List<HitlDecision>
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val content: String = "",
    val thinking: String = "",
    val isThinkingExpanded: Boolean = false,
    val isStreaming: Boolean = false,
    val toolCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val hitlPending: HitlPendingInfo? = null,
    val hitlResolved: String? = null, // null, "approved", "rejected"
    val attachments: List<AttachmentItem> = emptyList(),
    val modelName: String? = null
)

data class HistoryMessageDto(
    val id: String? = null,
    val role: String? = null,
    val content: Any? = null,
    val thinking: String? = null,
    val timestamp: Long = 0,
    val created_at: Long = 0,
    @SerializedName("model_name") val modelName: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("composer_context") val composerContext: Map<String, Any?>? = null,
    @SerializedName("inbound_attachments") val inboundAttachments: List<Map<String, Any?>>? = null
)

data class HistoryResponse(
    val items: List<HistoryMessageDto>? = null,
    val messages: List<HistoryMessageDto>? = null,
    val has_more: Boolean = false,
    val limit: Int = 0,
    val offset: Int = 0,
    val turn_active: Boolean = false
)

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<AttachmentItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
