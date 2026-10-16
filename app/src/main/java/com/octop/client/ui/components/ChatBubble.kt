package com.octop.client.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.octop.client.data.model.AttachmentItem
import com.octop.client.data.model.ChatMessage
import com.octop.client.data.model.HitlPendingInfo
import com.octop.client.data.model.ResolvedModel
import com.octop.client.data.network.NetworkConfig
import com.octop.client.ui.theme.*

/**
 * 代表 Antigravity 风格的一个交互轮次 (Turn):
 * 包含一条置顶/吸顶的用户提问卡片，以及跟随该提问的一组 AI 响应（流式正文、思考卡片、工具审批、附件等）。
 */
data class ChatTurn(
    val id: String,
    val userMessage: ChatMessage?,
    val aiMessages: List<ChatMessage>
)

/**
 * 将平铺的消息列表重组为 Antigravity 风格的轮次结构
 */
fun groupMessagesIntoTurns(messages: List<ChatMessage>): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    var currentUserMsg: ChatMessage? = null
    val currentAiMsgs = mutableListOf<ChatMessage>()

    for (msg in messages) {
        if (msg.isUser) {
            if (currentUserMsg != null || currentAiMsgs.isNotEmpty()) {
                val turnId = currentUserMsg?.id ?: currentAiMsgs.firstOrNull()?.id ?: "turn_${turns.size}"
                turns.add(ChatTurn(turnId, currentUserMsg, currentAiMsgs.toList()))
                currentAiMsgs.clear()
            }
            currentUserMsg = msg
        } else {
            currentAiMsgs.add(msg)
        }
    }
    if (currentUserMsg != null || currentAiMsgs.isNotEmpty()) {
        val turnId = currentUserMsg?.id ?: currentAiMsgs.firstOrNull()?.id ?: "turn_${turns.size}"
        turns.add(ChatTurn(turnId, currentUserMsg, currentAiMsgs.toList()))
    }
    return turns
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserPromptCard(
    message: ChatMessage,
    isSticky: Boolean = false,
    isCollapsed: Boolean = false,
    onToggleExpand: () -> Unit = {},
    currentAgentId: String = "",
    onOpenFile: ((AttachmentItem) -> Unit)? = null,
    onPreviewImage: ((String, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val (cleanContent, _) = remember(message.content, message.thinking) {
        val raw = message.content
        val needsParse = raw.startsWith("[") || raw.startsWith("{") || raw.contains("\"type\"")
        if (needsParse) {
            val parsed = com.octop.client.data.model.MessageParser.parseString(raw)
            Pair(if (parsed.first.isNotBlank() || parsed.second.isNotBlank()) parsed.first else raw, parsed.second)
        } else {
            Pair(raw, message.thinking)
        }
    }

    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()

    val showHeader = true
    val maxContentHeight = (configuration.screenHeightDp * 0.38f).coerceIn(160f, 300f).dp

    // 拦截内部滚动溢出，阻断嵌套滚动冒泡给外层 LazyColumn，
    // 防止在提问框内上下滑动时拉扯到后面的 AI 回复或上一轮对话
    val stopNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 彻底消费全部未被内部滚动的垂直与水平位移，阻断其传递给外层 LazyColumn
                return available
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                // 彻底消费剩余惯性速度，防止滑到顶/底时外层列表跟随弹跳
                return available
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF1E1F20) else Color(0xFFF1F3F5),
        border = BorderStroke(
            0.75.dp,
            if (isDark) Color(0xFF333538) else Color(0xFFE2E4E8)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isCollapsed) {
                    Modifier.nestedScroll(stopNestedScrollConnection)
                } else Modifier
            )
            .then(
                // 折叠为单行时，点击展开；长按直接复制全部提问内容并弹出 Toast
                if (isCollapsed) {
                    Modifier.combinedClickable(
                        onClick = { onToggleExpand() },
                        onLongClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("提问内容", cleanContent))
                            Toast.makeText(context, "已复制提问内容", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else Modifier
            )
            .animateContentSize()
    ) {
        if (isCollapsed) {
            // 缩成一行单行模式 (带省略号 + 展开指示图标)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayText = when {
                    cleanContent.isNotBlank() && cleanContent != "请查看并分析附件内容" && message.attachments.isNotEmpty() ->
                        "📎 [${message.attachments.size}个附件] $cleanContent"
                    cleanContent.isNotBlank() && cleanContent != "请查看并分析附件内容" ->
                        cleanContent
                    message.attachments.isNotEmpty() ->
                        "📎 [${message.attachments.size} 个附件] ${message.attachments.first().filename}"
                    else -> cleanContent
                }

                Text(
                    text = displayText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "展开提问",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            // 展开/正常流完整卡片：在卡片整体内捕获手势，确保即使点在内边距或头部也绝不会带动外层答案列表滑动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollable(
                        orientation = Orientation.Vertical,
                        state = rememberScrollableState { 0f }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (showHeader) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scrollable(
                                orientation = Orientation.Vertical,
                                state = rememberScrollableState { 0f }
                            )
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "提问内容",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("提问内容", cleanContent))
                                        Toast.makeText(context, "已复制提问内容", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制提问",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "复制",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Row(
                                modifier = Modifier
                                    .clickable { onToggleExpand() }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "点击收拢",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "收拢提问",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 核心内容区：对长提问施加高度保护上限，支持内部纵向滑动与长按选中复制
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(scrollState)
                ) {
                    // 1. 小图标/紧凑附件展示 (对标 Antigravity Web 手机端)
                    if (message.attachments.isNotEmpty()) {
                        PromptAttachmentThumbnails(
                            attachments = message.attachments,
                            agentId = currentAgentId,
                            onPreviewImage = onPreviewImage,
                            onOpenFile = onOpenFile
                        )
                        if (cleanContent.isNotBlank() && cleanContent != "请查看并分析附件内容") {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    // 2. 提问正文（若有纯文本输入）
                    if (cleanContent.isNotBlank() && !(cleanContent == "请查看并分析附件内容" && message.attachments.isNotEmpty())) {
                        SelectionContainer {
                            Text(
                                text = cleanContent,
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp,
                                color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiResponseCard(
    message: ChatMessage,
    currentAgentId: String = "",
    onToggleThinking: () -> Unit,
    onHitlDecision: ((Boolean) -> Unit)? = null,
    onOpenFile: ((AttachmentItem) -> Unit)? = null,
    onPreviewImage: ((String, String) -> Unit)? = null
) {
    val context = LocalContext.current

    // 确保气泡展现前文本已被彻底解包与清洗，避免显示 [{type=text, ...}] 原始结构（非 JSON 结构的流式增量跳过正则解析，避免长文掉帧）
    val (cleanContent, cleanThinking) = remember(message.content, message.thinking) {
        val raw = message.content
        val needsParse = raw.startsWith("[") || raw.startsWith("{") || raw.contains("\"type\"")
        if (needsParse) {
            val parsed = com.octop.client.data.model.MessageParser.parseString(raw)
            val finalThinking = when {
                message.thinking.isNotBlank() && parsed.second.isNotBlank() && !message.thinking.contains(parsed.second) ->
                    "${message.thinking}\n\n---\n\n${parsed.second}"
                message.thinking.isNotBlank() -> message.thinking
                else -> parsed.second
            }
            val finalContent = if (parsed.first.isNotBlank() || parsed.second.isNotBlank()) parsed.first else raw
            Pair(finalContent, finalThinking)
        } else {
            Pair(raw, message.thinking)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .animateContentSize()
    ) {
        // 1. 深度思考与工具调用折叠卡片 (与 Web 端进程摘要严格对齐)
        if (cleanThinking.isNotBlank() || message.toolCount > 0) {
            ThinkingBlock(
                thinking = cleanThinking,
                toolCount = message.toolCount,
                isExpanded = message.isThinkingExpanded,
                isStreaming = message.isStreaming && cleanContent.isEmpty(),
                onToggle = onToggleThinking
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 2. HITL 工具执行审批卡片 (运维工程师/高危操作安全护栏)
        if (message.hitlPending != null) {
            HitlApprovalBlock(
                hitlPending = message.hitlPending,
                hitlResolved = message.hitlResolved,
                onDecision = onHitlDecision
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 3. AI 附件展示 (例如生成或读取的文件/图像)
        if (message.attachments.isNotEmpty()) {
            MessageAttachmentList(
                attachments = message.attachments,
                agentId = currentAgentId,
                onPreviewImage = onPreviewImage,
                onOpenFile = onOpenFile
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 4. 正文 Markdown 渲染
        if (cleanContent.isNotBlank()) {
            MarkdownView(
                content = cleanContent,
                isStreaming = message.isStreaming
            )
        } else if (message.isStreaming && cleanThinking.isEmpty() && message.hitlPending == null) {
            Text(
                text = "正在思考中...",
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 5. Antigravity 风格底部快捷操作栏 (模型与时间标签 + 复制回复)
        if (!message.isStreaming && cleanContent.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeStr = remember(message.timestamp) { formatMessageTime(message.timestamp) }
                val rawModel = message.modelName?.trim()
                val modelStr = remember(rawModel) {
                    rawModel?.takeIf { it.isNotBlank() }?.let { ResolvedModel.formatModelWithProvider(it) }
                }
                val metaText = when {
                    !modelStr.isNullOrBlank() && timeStr.isNotBlank() -> "$modelStr · $timeStr"
                    !modelStr.isNullOrBlank() -> modelStr
                    else -> timeStr
                }

                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = 8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AI 回复", cleanContent))
                        Toast.makeText(context, "已复制回复内容", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制回复",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    currentAgentId: String = "",
    onToggleThinking: () -> Unit,
    onHitlDecision: ((Boolean) -> Unit)? = null,
    onOpenFile: ((AttachmentItem) -> Unit)? = null,
    onPreviewImage: ((String, String) -> Unit)? = null
) {
    if (message.isUser) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            UserPromptCard(
                message = message,
                isSticky = false,
                isCollapsed = false,
                currentAgentId = currentAgentId,
                onOpenFile = onOpenFile,
                onPreviewImage = onPreviewImage
            )
        }
    } else {
        AiResponseCard(
            message = message,
            currentAgentId = currentAgentId,
            onToggleThinking = onToggleThinking,
            onHitlDecision = onHitlDecision,
            onOpenFile = onOpenFile,
            onPreviewImage = onPreviewImage
        )
    }
}

@Composable
fun ThinkingBlock(
    thinking: String,
    toolCount: Int = 0,
    isExpanded: Boolean,
    isStreaming: Boolean,
    onToggle: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val wordCount = thinking.length

    val title = when {
        isStreaming && toolCount > 0 -> "🛠️ 已调用 $toolCount 次工具 · 💭 正在深度思考..."
        isStreaming -> "💭 正在深度思考..."
        toolCount > 0 && wordCount > 0 -> "🛠️ 已调用 $toolCount 次工具 · 💭 已深度思考 ($wordCount 字)"
        toolCount > 0 -> "🛠️ 已调用 $toolCount 次工具"
        else -> "💭 已深度思考 ($wordCount 字)"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isDark) ThinkingBgDark else ThinkingBgLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (thinking.isNotBlank()) onToggle() }
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (thinking.isNotBlank()) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "切换思考折叠",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SelectionContainer {
                        Text(
                            text = thinking,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(
    language: String,
    code: String,
    onCopy: () -> Unit
) {
    val isDark = isSystemInDarkTheme()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) CodeBgDark else CodeBgLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0xFF1E2127) else Color(0xFFDDE1E6))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.clickable { onCopy() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "复制",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (isDark) Color(0xFFD1D5DB) else Color(0xFF1F2937),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun HitlApprovalBlock(
    hitlPending: HitlPendingInfo,
    hitlResolved: String?,
    onDecision: ((Boolean) -> Unit)?
) {
    val isDark = isSystemInDarkTheme()
    val borderColor = when (hitlResolved) {
        "approved" -> Color(0xFF10B981)
        "rejected" -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isDark) Color(0xFF262115) else Color(0xFFFEF3C7),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "安全审批",
                    tint = borderColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "待人工审批操作 (Security Guardrail)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFFDE68A) else Color(0xFF92400E)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 列出请求调用的工具与参数
            hitlPending.actionRequests.forEach { action ->
                Text(
                    text = "工具: ${action.name}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFFF3F4F6) else Color(0xFF1F2937)
                )
                if (!action.description.isNullOrBlank()) {
                    Text(
                        text = action.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!action.args.isNullOrEmpty()) {
                    val argsText = action.args.entries.joinToString("\n") { (k, v) -> "$k: $v" }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDark) Color(0xFF111827) else Color(0xFFF3F4F6),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = argsText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (isDark) Color(0xFFE5E7EB) else Color(0xFF374151),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 审批操作区
            when (hitlResolved) {
                "approved" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "已批准",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已批准执行",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF10B981)
                        )
                    }
                }
                "rejected" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "已拒绝",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已拒绝执行",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
                else -> {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { onDecision?.invoke(false) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("拒绝", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onDecision?.invoke(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("批准执行", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Antigravity Web 风格的小图标附件展示组件：
 * 采用小尺寸圆角方形缩略图 (56dp x 56dp) 代替原来的大图满铺，支持横向滑动多图与点击全屏放大预览。
 */
@Composable
fun PromptAttachmentThumbnails(
    attachments: List<AttachmentItem>,
    agentId: String,
    onPreviewImage: ((String, String) -> Unit)?,
    onOpenFile: ((AttachmentItem) -> Unit)?
) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (item in attachments) {
            if (item.isImage) {
                val targetAid = if (agentId.isNotBlank()) agentId else (item.uploadedAgentId ?: "")
                val model = if (!item.localUriString.isNullOrBlank()) {
                    item.localUriString
                } else if (!item.workspacePath.isNullOrBlank() && targetAid.isNotBlank()) {
                    NetworkConfig.getMediaPreviewUrl(targetAid, item.workspacePath, item.mimeType)
                } else null

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDark) Color(0xFF282A2D) else Color(0xFFE5E7EB),
                    border = BorderStroke(0.75.dp, if (isDark) Color(0xFF3E4146) else Color(0xFFD1D5DB)),
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (model != null) {
                                onPreviewImage?.invoke(model, item.filename)
                            }
                        }
                ) {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = item.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDark) Color(0xFF282A2D) else Color(0xFFE5E7EB),
                    border = BorderStroke(0.75.dp, if (isDark) Color(0xFF3E4146) else Color(0xFFD1D5DB)),
                    modifier = Modifier
                        .height(44.dp)
                        .clickable { onOpenFile?.invoke(item) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = item.filename,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.size > 0) {
                                Text(
                                    text = formatBubbleFileSize(item.size),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageAttachmentList(
    attachments: List<AttachmentItem>,
    agentId: String,
    onPreviewImage: ((String, String) -> Unit)?,
    onOpenFile: ((AttachmentItem) -> Unit)?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (item in attachments) {
            if (item.isImage) {
                // 本地优先防闪烁 (Detail #2)
                val model = if (!item.localUriString.isNullOrBlank()) {
                    item.localUriString
                } else if (!item.workspacePath.isNullOrBlank()) {
                    NetworkConfig.getMediaPreviewUrl(agentId, item.workspacePath, item.mimeType)
                } else null

                if (model != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.05f))
                            .clickable { onPreviewImage?.invoke(model, item.filename) }
                    ) {
                        AsyncImage(
                            model = model,
                            contentDescription = item.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile?.invoke(item) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.filename,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.size > 0) {
                                Text(
                                    text = formatBubbleFileSize(item.size),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBubbleFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.1f MB", mb)
    } else {
        String.format("%.0f KB", kb)
    }
}

fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
    return try {
        val sdf = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINESE)
        sdf.format(java.util.Date(millis))
    } catch (_: Exception) {
        ""
    }
}



