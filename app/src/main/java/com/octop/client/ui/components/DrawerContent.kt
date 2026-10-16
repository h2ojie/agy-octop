package com.octop.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octop.client.data.model.AgentItem
import com.octop.client.data.model.ThreadItem
import com.octop.client.ui.theme.PrimaryGreen

@Composable
fun DrawerContent(
    agents: List<AgentItem>,
    currentAgent: AgentItem?,
    currentThread: ThreadItem?,
    agentThreadsMap: Map<String, List<ThreadItem>>,
    expandedAgentIds: Set<String>,
    onToggleExpandAgent: (String) -> Unit,
    onSelectAgent: (AgentItem) -> Unit,
    onSelectThread: (AgentItem, ThreadItem) -> Unit,
    onCreateThread: (AgentItem) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. 抽屉顶部头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = PrimaryGreen.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = PrimaryGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Octop 专家矩阵",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "8位专属领域 Agent 参谋",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // 2. 专家与树状会话列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(agents, key = { it.agentId }) { agent ->
                    val isExpanded = expandedAgentIds.contains(agent.agentId)
                    val isCurrentAgent = currentAgent?.agentId == agent.agentId
                    val threads = agentThreadsMap[agent.agentId] ?: emptyList()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                    ) {
                        // 专家条目
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isCurrentAgent) PrimaryGreen.copy(alpha = 0.08f) else Color.Transparent
                                )
                                .clickable {
                                    onSelectAgent(agent)
                                    onToggleExpandAgent(agent.agentId)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowRight,
                                contentDescription = null,
                                tint = if (isCurrentAgent) PrimaryGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = agent.name,
                                fontSize = 15.sp,
                                fontWeight = if (isCurrentAgent) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrentAgent) PrimaryGreen else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // 快捷新建会话 [+] 按钮
                            IconButton(
                                onClick = { onCreateThread(agent) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "新建会话",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // 原地展开该专家下的历史会话列表
                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 36.dp, end = 12.dp, bottom = 4.dp)
                            ) {
                                if (threads.isEmpty()) {
                                    Text(
                                        text = "暂无历史会话",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                } else {
                                    threads.forEach { thread ->
                                        val isCurrentThread = currentThread?.threadId == thread.threadId
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isCurrentThread) PrimaryGreen.copy(alpha = 0.15f) else Color.Transparent
                                                )
                                                .clickable { onSelectThread(agent, thread) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ChatBubbleOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isCurrentThread) PrimaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = thread.title ?: "未命名会话",
                                                fontSize = 13.sp,
                                                fontWeight = if (isCurrentThread) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isCurrentThread) PrimaryGreen else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isCurrentThread) {
                                                Text(
                                                    text = "当前",
                                                    fontSize = 10.sp,
                                                    color = PrimaryGreen,
                                                    fontWeight = FontWeight.Bold
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
        }
    }
}
