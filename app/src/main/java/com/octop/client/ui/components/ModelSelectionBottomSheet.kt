package com.octop.client.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octop.client.data.model.ResolvedModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    models: List<ResolvedModel>,
    selectedModelRef: String,
    selectedEffort: String?,
    onSelectModel: (modelRef: String, effort: String?) -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF18181B) else Color(0xFFF9FAFB)
    val textColor = if (isDark) Color(0xFFF4F4F5) else Color(0xFF18181B)
    val secondaryColor = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)

    // 二级导航状态：null 表示主列表，非 null 表示当前正在配置该模型的推理强度
    var drillDownModel by remember { mutableStateOf<ResolvedModel?>(null) }

    ModalBottomSheet(
        onDismissRequest = {
            drillDownModel = null
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = bgColor,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            color = if (isDark) Color(0xFF3F3F46) else Color(0xFFD1D5DB),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
        ) {
            AnimatedContent(
                targetState = drillDownModel,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width }
                    } else {
                        slideInHorizontally { width -> -width } togetherWith slideOutHorizontally { width -> width }
                    }
                },
                label = "ModelSheetContent"
            ) { targetModel ->
                if (targetModel == null) {
                    // 主页面：模型列表 (对标 Antigravity Web)
                    ModelListView(
                        models = models,
                        selectedModelRef = selectedModelRef,
                        selectedEffort = selectedEffort,
                        textColor = textColor,
                        secondaryColor = secondaryColor,
                        onSelectModel = { model, effort ->
                            onSelectModel(model.modelRef, effort)
                            onDismiss()
                        },
                        onOpenEffort = { model ->
                            drillDownModel = model
                        }
                    )
                } else {
                    // 二级页面：思考强度选择 (Reasoning Effort)
                    EffortSelectionView(
                        model = targetModel,
                        currentEffort = if (targetModel.modelRef == selectedModelRef) selectedEffort ?: targetModel.defaultEffort else targetModel.defaultEffort,
                        textColor = textColor,
                        secondaryColor = secondaryColor,
                        onBack = { drillDownModel = null },
                        onSelectEffort = { effort ->
                            onSelectModel(targetModel.modelRef, effort)
                            drillDownModel = null
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelListView(
    models: List<ResolvedModel>,
    selectedModelRef: String,
    selectedEffort: String?,
    textColor: Color,
    secondaryColor: Color,
    onSelectModel: (ResolvedModel, String?) -> Unit,
    onOpenEffort: (ResolvedModel) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分组标题
        Text(
            text = "Model",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = secondaryColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            items(models, key = { it.modelRef }) { model ->
                val isSelected = if (selectedModelRef.contains("/")) {
                    model.modelRef.equals(selectedModelRef, ignoreCase = true)
                } else {
                    model.model.equals(selectedModelRef, ignoreCase = true)
                }
                val hasEfforts = model.efforts.size > 1
                val activeEffort = if (isSelected) selectedEffort ?: model.defaultEffort else model.defaultEffort

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectModel(model, activeEffort)
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧：模型名称 + 服务商标称 (cli / ice) + 思考强度小标签
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = model.displayName,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor
                        )

                        if (model.providerName.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color(0xFF2C2F33),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(
                                    0.5.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFF42444A)
                                )
                            ) {
                                Text(
                                    text = model.providerName,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF9EA3AC),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (model.reasoning) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ResolvedModel.formatEffortName(activeEffort),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                color = secondaryColor
                            )
                        }
                    }

                    // 右侧：勾选标记与二级配置箭头 (对标 Antigravity: [✓] [>])
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "已选择",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        if (hasEfforts) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onOpenEffort(model) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "调整思考强度",
                                    tint = secondaryColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(19.dp)
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
private fun EffortSelectionView(
    model: ResolvedModel,
    currentEffort: String,
    textColor: Color,
    secondaryColor: Color,
    onBack: () -> Unit,
    onSelectEffort: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 顶部导航栏：返回按钮与标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回模型列表",
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "Reasoning Effort",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        fontSize = 12.sp,
                        color = secondaryColor
                    )
                    if (model.providerName.isNotBlank()) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            color = Color(0xFF2C2F33),
                            shape = RoundedCornerShape(3.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF42444A))
                        ) {
                            Text(
                                text = model.providerName,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF9EA3AC),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.5.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 思考强度选项列表 (Low, Medium, High, Extra High)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            items(model.efforts) { effort ->
                val isSelected = effort.equals(currentEffort, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectEffort(effort) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = ResolvedModel.formatEffortName(effort),
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor
                    )

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "当前选择",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}
