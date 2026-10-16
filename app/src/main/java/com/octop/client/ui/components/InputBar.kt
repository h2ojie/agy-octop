package com.octop.client.ui.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octop.client.data.model.AttachmentItem
import com.octop.client.data.model.AttachmentStatus
import com.octop.client.ui.theme.PrimaryGreen

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    stagedAttachments: List<AttachmentItem> = emptyList(),
    currentModelName: String = "",
    currentAgentName: String = "",
    currentAgentId: String = "",
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    onOpenModelSelector: () -> Unit = {},
    isStreaming: Boolean
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDark = isSystemInDarkTheme()
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // 使用 TextFieldValue 维持输入框精确选择区与光标，原生支持长按选中、拖动滑块、复制、剪切与粘贴
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }

    // 当外部 text 改变（例如切换会话恢复草稿、发送后清空）时，安全同步到内部 textFieldValue
    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = text,
                selection = TextRange(text.length)
            )
        }
    }

    val hasValidAttachments = stagedAttachments.any { it.status == AttachmentStatus.SUCCESS }
    val isBusy = stagedAttachments.any { it.status == AttachmentStatus.PENDING || it.status == AttachmentStatus.UPLOADING }
    val canSend = (text.isNotBlank() || hasValidAttachments) && !isBusy

    // 外层容器：直接与背景融为一体，不使用厚重的悬浮底栏色块
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .imePadding()
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1E1F20) else Color(0xFFF1F3F5),
            border = BorderStroke(
                0.75.dp,
                if (isDark) Color(0xFF333538) else Color(0xFFE2E4E8)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 1. 待发附件托盘 (仅在有选定附件时展开)
                if (stagedAttachments.isNotEmpty()) {
                    AttachmentTray(
                        attachments = stagedAttachments,
                        agentId = currentAgentId,
                        onRemove = onRemoveAttachment,
                        onRetry = onRetryAttachment
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. 文本输入核心区（随打字由 1 行平滑扩展至 8 行，原生支持长按选中、复制、剪切、粘贴）
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        if (newValue.text != text) {
                            onTextChange(newValue.text)
                        }
                    },
                    minLines = 1,
                    maxLines = 8,
                    textStyle = TextStyle(
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = if (stagedAttachments.isNotEmpty()) "描述你的问题或需求..." else "输入问题、指令或需求...",
                                    fontSize = 14.5.sp,
                                    lineHeight = 21.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )

                // 3. 底部操作栏：左侧 [+] 与模型胶囊，右侧小巧精致的圆形发送 / 停止按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧：加号附件按钮与模型标签
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Surface(
                                shape = CircleShape,
                                color = if (isDark) Color(0xFF2B2D31) else Color(0xFFE5E7EB),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { showAttachmentMenu = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "添加附件",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            AttachmentDropdown(
                                expanded = showAttachmentMenu,
                                onDismiss = { showAttachmentMenu = false },
                                onTakePhoto = {
                                    showAttachmentMenu = false
                                    onTakePhoto()
                                },
                                onPickImage = {
                                    showAttachmentMenu = false
                                    onPickImage()
                                },
                                onPickFile = {
                                    showAttachmentMenu = false
                                    onPickFile()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val displayModel = if (currentModelName.isNotBlank()) currentModelName
                        else if (currentAgentName.isNotBlank()) currentAgentName
                        else "Gemini 2.5 Flash"

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDark) Color(0xFF282A2D) else Color(0xFFE5E7EB),
                            modifier = Modifier.clickable(onClick = onOpenModelSelector)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                            ) {
                                Text(
                                    text = displayModel,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // 右侧：发送/停止按钮 (严格对齐 Antigravity：流式中若输入了文字/附件，显示蓝色排队发送箭头；无输入时显示精致停止方块)
                    val showQueueSend = isStreaming && canSend

                    if (isStreaming && !showQueueSend) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) Color(0xFF282A2D) else Color(0xFFE5E7EB),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onStop)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            color = Color(0xFFEA4335),
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    } else {
                        val sendBgColor = when {
                            showQueueSend -> Color(0xFF0084FF) // Antigravity 蓝底排队发送按钮
                            canSend -> if (isDark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
                            else -> if (isDark) Color(0xFF2A2C2E) else Color(0xFFE5E7EB)
                        }
                        val sendIconColor = when {
                            showQueueSend -> Color.White
                            canSend -> if (isDark) Color(0xFF131314) else Color.White
                            else -> if (isDark) Color(0xFF55585A) else Color(0xFF9CA3AF)
                        }

                        Surface(
                            shape = CircleShape,
                            color = sendBgColor,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(enabled = canSend) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    onSend()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.8.dp,
                                        color = sendIconColor
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = if (showQueueSend) "排队发送" else "发送",
                                        modifier = Modifier.size(14.dp),
                                        tint = sendIconColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("拍照 (拍摄化验单/图表)") },
            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            onClick = onTakePhoto
        )
        DropdownMenuItem(
            text = { Text("相册 (选择图片/多选)") },
            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
            onClick = onPickImage
        )
        DropdownMenuItem(
            text = { Text("文件 (化验单/文档/多选)") },
            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
            onClick = onPickFile
        )
    }
}
