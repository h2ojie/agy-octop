package com.octop.client.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octop.client.data.model.AttachmentStatus
import com.octop.client.data.network.NetworkConfig
import com.octop.client.ui.components.*
import com.octop.client.util.DiagnosticLogger
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // 前台恢复自愈：当用户从后台/微信/锁屏重新回到应用时，自动触发会话同步与对齐
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onForegroundResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val listState = rememberLazyListState()

    val agents by viewModel.agents.collectAsState()
    val currentAgent by viewModel.currentAgent.collectAsState()
    val currentThread by viewModel.currentThread.collectAsState()
    val agentThreadsMap by viewModel.agentThreadsMap.collectAsState()
    val expandedAgentIds by viewModel.expandedAgentIds.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val stagedAttachments by viewModel.stagedAttachments.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val showLoginDialog by viewModel.showLoginDialog.collectAsState()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val loginError by viewModel.loginError.collectAsState()

    val availableModels by viewModel.availableModels.collectAsState()
    val currentSelectedModel by viewModel.currentSelectedModel.collectAsState()
    val currentSelectedEffort by viewModel.currentSelectedEffort.collectAsState()
    var showModelBottomSheet by remember { mutableStateOf(false) }

    val isLoggingEnabled by viewModel.isLoggingEnabled.collectAsState()
    val isUploadingLogs by viewModel.isUploadingLogs.collectAsState()
    var showDiagnosticModal by remember { mutableStateOf(false) }

    // 交互轮次重组与当前唯一展开的提问卡片 ID (为空时全部自动收拢为单行，对标 Antigravity Web)
    val turns = remember(messages) { groupMessagesIntoTurns(messages) }
    var activeExpandedTurnId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentThread?.threadId) {
        activeExpandedTurnId = null
    }

    // 核心交互规范 (对标 Antigravity)：当聊天列表发生用户手指拖拽滑动时，提问卡片自动收拢为单行
    // 注意：必须使用 collectIsDraggedAsState，避免在卡片展开动画/测量调整/微触时误触发收拢
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isListDragged) {
        if (isListDragged && activeExpandedTurnId != null) {
            DiagnosticLogger.d(DiagnosticLogger.TAG_UI, "LazyColumn touch dragged, auto-collapsing prompt card")
            activeExpandedTurnId = null
        }
    }

    // 预计算每个交互轮次在 LazyColumn 中的 Header 索引 (用于精确定位哪个提问卡片当前处于置顶吸顶状态)
    val turnHeaderIndices = remember(turns) {
        var currentIndex = 0
        val map = mutableMapOf<Int, Int>() // turnIndex -> header item index in LazyColumn
        turns.forEachIndexed { turnIndex, turn ->
            if (turn.userMessage != null) {
                map[turnIndex] = currentIndex
                currentIndex += 1
            }
            currentIndex += turn.aiMessages.size
        }
        map
    }

    // 切换会话或初次加载完毕时，自动定位至最新消息处 (列表底部)
    var lastScrolledThreadId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentThread?.threadId, turns.isNotEmpty()) {
        val tid = currentThread?.threadId
        if (!tid.isNullOrBlank() && turns.isNotEmpty() && lastScrolledThreadId != tid) {
            lastScrolledThreadId = tid
            kotlinx.coroutines.delay(80L)
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.scrollToItem((total - 1).coerceAtLeast(0))
            } else if (turns.isNotEmpty()) {
                val lastHeader = turnHeaderIndices[turns.size - 1] ?: 0
                listState.scrollToItem(lastHeader)
            }
        }
    }

    val configuration = LocalConfiguration.current
    // 动态底部视口缓冲空间：
    // 1. 发送消息（isStreaming == true）时：通过 snapTo 立即瞬间达到 85% 视口高度（0 延迟），确保 LazyColumn 能够绝对置顶吸顶；
    // 2. 回复完成（isStreaming == false）时：通过 animateTo 平滑收拢至 16dp，彻底避免回复后仍可往上拉出大片黑边空白。
    val bottomSpacerAnimatable = remember { Animatable(16f) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            bottomSpacerAnimatable.snapTo(configuration.screenHeightDp * 0.85f)
        } else {
            bottomSpacerAnimatable.animateTo(16f, animationSpec = tween(durationMillis = 350))
        }
    }

    var showTopMenu by remember { mutableStateOf(false) }

    // 大图预览弹窗状态
    var previewImageModel by remember { mutableStateOf<String?>(null) }
    var previewImageTitle by remember { mutableStateOf("") }

    // 拍照临时 URI 状态
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // 返回键统一拦截：
    // 1. 若处于大图预览，则关闭大图；
    // 2. 若处于模型选择 BottomSheet，则关闭 BottomSheet；
    // 3. 若处于抽屉栏（专家矩阵），则按返回键关闭抽屉回到聊天主窗口；
    // 4. 处于聊天主窗口时，按第一次返回弹 Toast 提示「再按一次退出应用」，2 秒内连续按第二次才退出程序。
    val activity = remember(context) { context.findActivity() }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var backToast by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) {
            lastBackPressTime = 0L
        }
    }

    BackHandler(enabled = true) {
        if (previewImageModel != null) {
            previewImageModel = null
        } else if (showModelBottomSheet) {
            showModelBottomSheet = false
        } else if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
            lastBackPressTime = 0L
            focusManager.clearFocus()
            keyboardController?.hide()
            coroutineScope.launch { drawerState.close() }
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000L) {
                backToast?.cancel()
                activity?.finish()
            } else {
                lastBackPressTime = currentTime
                backToast?.cancel()
                backToast = Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).apply {
                    show()
                }
            }
        }
    }

    // 相册图片选择器 (支持多选，最大 9 张，Android 14 免存储权限)
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.pickAttachments(context, uris, isImage = true)
        }
    }

    // 系统文档选择器 (支持多选，Android 14 免存储权限)
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.pickAttachments(context, uris, isImage = false)
        }
    }

    // 相机拍照 (基于 FileProvider 临时缓存)
    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.pickAttachment(context, tempCameraUri!!, isImage = true)
        }
    }

    fun launchCamera() {
        try {
            val cameraDir = File(context.cacheDir, "camera").apply { if (!exists()) mkdirs() }
            val photoFile = File(cameraDir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempCameraUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "启动相机失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    // 监听 Toast 提示
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // 监听新增用户提问：消息发送之后立马把最新提问消息置顶吸附到页面最上方 (对齐 Antigravity 网页版交互)
    val latestUserMsg = remember(messages) { messages.lastOrNull { it.isUser } }
    var lastHandledUserMsgId by remember(currentThread?.threadId) { mutableStateOf(latestUserMsg?.id) }

    LaunchedEffect(latestUserMsg?.id) {
        val userMsg = latestUserMsg
        if (userMsg != null && userMsg.id != lastHandledUserMsgId) {
            lastHandledUserMsgId = userMsg.id
            val turnIndex = turns.indexOfFirst { it.userMessage?.id == userMsg.id }
                .takeIf { it >= 0 } ?: (turns.size - 1).coerceAtLeast(0)
            if (turnIndex < turns.size) {
                activeExpandedTurnId = turns[turnIndex].id
            }
            // 等待键盘收拢与 LazyColumn 布局测量稳定，确保新轮次的 stickyHeader 已进入列表
            kotlinx.coroutines.delay(60L)
            val headerIndex = turnHeaderIndices[turnIndex] ?: 0
            listState.animateScrollToItem(headerIndex, 0)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                agents = agents,
                currentAgent = currentAgent,
                currentThread = currentThread,
                agentThreadsMap = agentThreadsMap,
                expandedAgentIds = expandedAgentIds,
                onToggleExpandAgent = { viewModel.toggleExpandAgent(it) },
                onSelectAgent = { agent ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.selectAgent(agent)
                },
                onSelectThread = { agent, thread ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.selectThread(agent, thread)
                    coroutineScope.launch { drawerState.close() }
                },
                onCreateThread = { agent ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.createNewThread(agent)
                    coroutineScope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(44.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 保留左侧抽屉按钮，尺寸小巧精致 (36dp)
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                coroutineScope.launch { drawerState.open() }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "展开专家与会话列表",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 2. 中间单行：管家名字 + · + 会话标题 (超长跑马灯滚动，轻触标题亦可展开抽屉)
                        val displayAgent = currentAgent?.name?.takeIf { it.isNotBlank() } ?: "Octop"
                        val displayTitle = currentThread?.title?.takeIf { it.isNotBlank() } ?: "新对话"

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    coroutineScope.launch { drawerState.open() }
                                }
                        ) {
                            Text(
                                text = displayAgent,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = "·",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = displayTitle,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .weight(1f)
                                    .basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        velocity = 30.dp
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 3. 右侧操作区：刷新按钮 + 更多菜单按钮 (尺寸小巧精致 36dp)
                        IconButton(
                            onClick = {
                                viewModel.refreshCurrentThread(showToast = true)
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(120L)
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) {
                                        listState.animateScrollToItem((total - 1).coerceAtLeast(0))
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新当前会话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showTopMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "更多选项",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("刷新当前会话") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        viewModel.refreshCurrentThread(showToast = true)
                                        coroutineScope.launch {
                                            kotlinx.coroutines.delay(120L)
                                            val total = listState.layoutInfo.totalItemsCount
                                            if (total > 0) {
                                                listState.animateScrollToItem((total - 1).coerceAtLeast(0))
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("新建会话") },
                                    leadingIcon = { Icon(Icons.Default.AddComment, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        currentAgent?.let { viewModel.createNewThread(it) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("查看上下文统计") },
                                    leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        Toast.makeText(context, "当前会话消息数: ${messages.size} 条", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("模型管理") },
                                    leadingIcon = { Icon(Icons.Default.SettingsSuggest, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        val model = currentAgent?.defaultModel ?: "默认高阶模型"
                                        Toast.makeText(context, "当前接入模型: $model", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("账号与连接设置") },
                                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        viewModel.openLoginDialog()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("诊断与系统日志") },
                                    leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        showDiagnosticModal = true
                                    }
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                val currentModelName = remember(currentSelectedModel, currentAgent, availableModels) {
                    val raw = if (currentSelectedModel.isNotBlank()) currentSelectedModel else currentAgent?.defaultModel?.trim()
                    if (raw.isNullOrBlank()) {
                        "Gemini 3.8 Flash"
                    } else {
                        val prov = com.octop.client.data.model.ResolvedModel.extractProvider(raw).ifBlank {
                            availableModels.firstOrNull { it.modelRef == raw || it.model == raw }?.providerName
                        }
                        com.octop.client.data.model.ResolvedModel.formatModelWithProvider(raw, prov)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (queuedMessages.isNotEmpty()) {
                        QueuedMessagesCard(
                            queuedMessages = queuedMessages,
                            onSendNow = { viewModel.sendQueuedNow(it) },
                            onEdit = { viewModel.editQueuedMessage(it) },
                            onDelete = { viewModel.removeQueuedMessage(it) }
                        )
                    }

                    InputBar(
                        text = inputText,
                        onTextChange = { viewModel.setInputText(it) },
                        onSend = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            viewModel.sendMessage()
                        },
                        onStop = { viewModel.stopStreaming() },
                        onTakePhoto = { launchCamera() },
                        onPickImage = {
                            pickImagesLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickFile = {
                            pickFilesLauncher.launch(arrayOf("*/*"))
                        },
                        stagedAttachments = stagedAttachments,
                        currentModelName = currentModelName,
                        currentAgentName = currentAgent?.name ?: "Octop",
                        currentAgentId = currentAgent?.agentId ?: "",
                        onRemoveAttachment = { viewModel.removeStagedAttachment(it) },
                        onRetryAttachment = { viewModel.retryUpload(context, it) },
                        onOpenModelSelector = { showModelBottomSheet = true },
                        isStreaming = isStreaming
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (messages.isEmpty()) {
                    // 空会话状态：显示专家开场白与功能介绍
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentAgent?.name ?: "Octop 专家",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = currentAgent?.welcomeMessage
                                ?: currentAgent?.description
                                ?: "随时发我你的代码灵感、排障日志或体检报告。我们一起开启高效思考。",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        turns.forEachIndexed { turnIndex, turn ->
                            if (turn.userMessage != null) {
                                stickyHeader(key = "turn_${turn.id}_user") {
                                    val headerIndex = turnHeaderIndices[turnIndex]
                                    val nextHeaderIndex = turnHeaderIndices.entries
                                        .firstOrNull { it.key > turnIndex }
                                        ?.value

                                    val isSticky by remember(headerIndex, nextHeaderIndex) {
                                        derivedStateOf {
                                            val isPastCurrentHeader = headerIndex != null &&
                                                    listState.firstVisibleItemIndex > headerIndex
                                            val isBeforeNextHeader = nextHeaderIndex == null ||
                                                    listState.firstVisibleItemIndex < nextHeaderIndex
                                            isPastCurrentHeader && isBeforeNextHeader
                                        }
                                    }
                                    val isCollapsed = activeExpandedTurnId != turn.id

                                    // 置顶吸顶卡片容器：铺满屏幕底色，彻底防止下方 AI 长文在滚动时在背后重叠透出
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.background)
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        UserPromptCard(
                                            message = turn.userMessage,
                                            isSticky = isSticky,
                                            isCollapsed = isCollapsed,
                                            onToggleExpand = {
                                                val nextId = if (activeExpandedTurnId == turn.id) null else turn.id
                                                DiagnosticLogger.d(DiagnosticLogger.TAG_UI, "Prompt card toggled for turn: ${turn.id}, expanding=${nextId != null}")
                                                activeExpandedTurnId = nextId
                                            },
                                            currentAgentId = currentAgent?.agentId ?: "",
                                            onOpenFile = { att ->
                                                currentAgent?.agentId?.let { aid ->
                                                    viewModel.downloadAndOpenFile(context, aid, att)
                                                }
                                            },
                                            onPreviewImage = { model, title ->
                                                previewImageModel = model
                                                previewImageTitle = title
                                            }
                                        )
                                    }
                                }
                            }

                            items(turn.aiMessages, key = { it.id }) { aiMsg ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (activeExpandedTurnId != null) {
                                                activeExpandedTurnId = null
                                            }
                                        }
                                ) {
                                    AiResponseCard(
                                        message = aiMsg,
                                        currentAgentId = currentAgent?.agentId ?: "",
                                        onToggleThinking = { viewModel.toggleThinking(aiMsg.id) },
                                        onHitlDecision = { approve ->
                                            val aid = currentAgent?.agentId
                                            val tid = currentThread?.threadId
                                            if (aid != null && tid != null) {
                                                viewModel.submitHitlDecision(aid, tid, approve)
                                            }
                                        },
                                        onOpenFile = { att ->
                                            currentAgent?.agentId?.let { aid ->
                                                viewModel.downloadAndOpenFile(context, aid, att)
                                            }
                                        },
                                        onPreviewImage = { model, title ->
                                            previewImageModel = model
                                            previewImageTitle = title
                                        }
                                    )
                                }
                            }
                        }

                        // 预留底部视口缓冲空间：仅在流式生成中提供充足缓冲确保置顶；回复完成后平滑收拢至正常底距 (16dp)
                        item(key = "bottom_viewport_spacer") {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(bottomSpacerAnimatable.value.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (activeExpandedTurnId != null) {
                                            activeExpandedTurnId = null
                                        }
                                    }
                            )
                        }
                    }

                    // Antigravity 风格：快速滚动至最新消息浮动按钮
                    val showScrollToBottom by remember {
                        derivedStateOf {
                            val totalItems = listState.layoutInfo.totalItemsCount
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            totalItems > 0 && lastVisible < totalItems - 2
                        }
                    }

                    if (showScrollToBottom) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) {
                                        val target = (total - 2).coerceAtLeast(0)
                                        listState.animateScrollToItem(target)
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "滚动到底部",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 大图预览弹窗
    ImageViewerModal(
        visible = previewImageModel != null,
        model = previewImageModel,
        title = previewImageTitle,
        onDismiss = { previewImageModel = null }
    )

    // 登录与连接设置弹窗
    LoginDialog(
        visible = showLoginDialog,
        initialServerUrl = NetworkConfig.baseUrl,
        initialAppKey = NetworkConfig.appKey,
        isLoading = isLoggingIn,
        errorMessage = loginError,
        onDismiss = { viewModel.dismissLoginDialog() },
        onLogin = { serverUrl, appKey, uname, pwd ->
            viewModel.login(serverUrl, appKey, uname, pwd)
        }
    )

    // 模型与思考强度选择底部弹窗 (对标 Antigravity Web 手机端)
    ModelSelectionBottomSheet(
        visible = showModelBottomSheet,
        onDismiss = { showModelBottomSheet = false },
        models = availableModels,
        selectedModelRef = currentSelectedModel,
        selectedEffort = currentSelectedEffort,
        onSelectModel = { modelRef, effort ->
            viewModel.selectModel(modelRef, effort)
        }
    )

    // 系统诊断与运行日志弹窗
    DiagnosticModal(
        visible = showDiagnosticModal,
        isLoggingEnabled = isLoggingEnabled,
        isUploading = isUploadingLogs,
        onDismiss = { showDiagnosticModal = false },
        onToggleLogging = { viewModel.setLoggingEnabled(it) },
        onUploadLogs = { viewModel.uploadDiagnosticLogs(context) },
        onShareLogs = { viewModel.shareDiagnosticLogs(context) },
        onClearLogs = { viewModel.clearDiagnosticLogs(context) }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
