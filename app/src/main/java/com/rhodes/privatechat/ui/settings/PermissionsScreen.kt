package com.rhodes.privatechat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodes.privatechat.ui.common.OperatorAvatarImage
import com.rhodes.privatechat.data.db.entity.ChatSessionEntity
import com.rhodes.privatechat.ui.theme.*
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.viewmodel.MainViewModel
import com.rhodes.privatechat.automation.DailyContentScheduler
import org.koin.compose.koinInject

@Composable
fun PermissionsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val operators by viewModel.operators.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()
    val groups = allSessions.filter { it.operatorId.startsWith("group_") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabs = listOf("干员", "群聊")
    var tabIndex by remember { mutableIntStateOf(0) }
    var pendingDeletionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var finishSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var cancelSave by remember { mutableStateOf<(() -> Unit)?>(null) }

    SaveableSettingsScaffold(
        title = "权限管理",
        onBack = onBack,
        modifier = modifier.fillMaxSize().background(BG).systemBarsPadding(),
        icon = { Icon(Icons.Default.Build, null, tint = Primary) },
        onSaveRequest = { completeSave, cancel ->
            val finish = {
                completeSave()
                viewModel.refreshAutoGroupChats()
            }
            if (deleting) return@SaveableSettingsScaffold
            if (pendingDeletionIds.isEmpty()) finish()
            else {
                finishSave = finish
                cancelSave = cancel
                showDeleteConfirm = true
            }
        }
    ) {

        TabRow(selectedTabIndex = tabIndex, containerColor = Surface, contentColor = Blue400) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title, fontWeight = if (tabIndex == i) FontWeight.SemiBold else FontWeight.Normal) })
            }
        }

        when (tabIndex) {
            0 -> OperatorPermTab(operators = operators, pendingDeletionIds = pendingDeletionIds, onDeletionChanged = { pendingDeletionIds = it })
            1 -> GroupPermTab(groups = groups, viewModel = viewModel)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) { showDeleteConfirm = false; cancelSave?.invoke(); cancelSave = null } },
            title = { Text("确认删除干员", color = TextPrimary) },
            text = { Text("已勾选删除 ${pendingDeletionIds.size} 个干员。将同时删除这些干员的私聊、记忆、关系、动态和相关数据，此操作无法恢复。是否确认？", color = TextSecondary) },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    val deletingIds = pendingDeletionIds
                    deleting = true
                    viewModel.deleteOperators(deletingIds) { error ->
                        deleting = false
                        if (error == null) {
                            DailyContentScheduler.cancelTodayPlanForOperators(context, deletingIds)
                            pendingDeletionIds = emptySet()
                            showDeleteConfirm = false
                            finishSave?.invoke()
                            finishSave = null
                            cancelSave = null
                        } else {
                            showDeleteConfirm = false
                            cancelSave?.invoke()
                            cancelSave = null
                            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(if (deleting) "正在删除..." else "确认删除", color = ErrorRed) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { showDeleteConfirm = false; cancelSave?.invoke(); cancelSave = null }) { Text("取消", color = TextSecondary) } }
        )
    }
}

@Composable
private fun OperatorPermTab(
    operators: List<com.rhodes.privatechat.data.db.entity.OperatorEntity>,
    pendingDeletionIds: Set<String>,
    onDeletionChanged: (Set<String>) -> Unit
) {
    val settings: SettingsRepository = koinInject()
    // 批量开关只改变全局默认；把它作为行内缓存的 key，切换后每行会按新默认重算。
    var permissionAll by remember { mutableStateOf(settings.proactivePermissionAll) }

    Column {
        Text("批量设置角色主动私聊和动态参与权限。删除勾选会在点击保存并确认后执行，且会同时删除该干员的相关聊天、记忆、关系和动态数据。", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("主动私聊默认开启", fontSize = 13.sp, color = TextPrimary)
                    Text(
                        if (permissionAll) "所有角色默认可以主动联系你，下面单独关掉的角色除外；动态权限不受影响。"
                        else "只有下面单独打开的角色会主动联系你；动态权限不受影响。",
                        fontSize = 11.sp, color = TextSecondary
                    )
                }
                Switch(checked = permissionAll, onCheckedChange = { b ->
                    permissionAll = b
                    settings.proactivePermissionAll = b
                }, colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = PrimaryContainer, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Divider))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("干员", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("主动私聊", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(56.dp))
            Text("动态权限", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(56.dp))
            Text("删除", fontSize = 11.sp, color = ErrorRed, modifier = Modifier.width(56.dp))
        }

        LazyColumn {
            items(operators) { op ->
                var allowMsg by remember(op.id, permissionAll) { mutableStateOf(settings.getOperatorMsgPermission(op.id)) }
                var allowDyn by remember(op.id, permissionAll) { mutableStateOf(settings.getOperatorDynPermission(op.id)) }
                val markedForDeletion = op.id in pendingDeletionIds
                Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OperatorAvatarImage(avatarUri = op.avatarUri, name = op.name, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(op.name, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = allowMsg, onCheckedChange = { b -> allowMsg = b; settings.putOperatorMsgPermission(op.id, b) }, modifier = Modifier.width(56.dp), colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = PrimaryContainer, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Divider))
                    Switch(checked = allowDyn, onCheckedChange = { b -> allowDyn = b; settings.putOperatorDynPermission(op.id, b) }, modifier = Modifier.width(56.dp), colors = SwitchDefaults.colors(checkedThumbColor = AccentOrange, checkedTrackColor = AccentOrange.copy(alpha = 0.2f), uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Divider))
                    Switch(checked = markedForDeletion, onCheckedChange = { checked ->
                        onDeletionChanged(if (checked) pendingDeletionIds + op.id else pendingDeletionIds - op.id)
                    }, modifier = Modifier.width(56.dp), colors = SwitchDefaults.colors(checkedThumbColor = ErrorRed, checkedTrackColor = ErrorRed.copy(alpha = 0.2f), uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Divider))
                }
                HorizontalDivider(color = Divider)
            }
        }
    }
}

@Composable
private fun GroupPermTab(groups: List<com.rhodes.privatechat.data.db.entity.ChatSessionEntity>, viewModel: MainViewModel) {
    val settings: SettingsRepository = koinInject()

    Column {
        Text("只有你为群聊开启“空闲自动”后，群聊才会在到达设定时间时自动聊天。", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("暂无群聊", fontSize = 14.sp, color = TextTertiary)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群聊", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
                Text("空闲自动", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(64.dp))
            }
            LazyColumn {
                items(groups, key = { it.id }) { g ->
                    var idleAuto by remember(g.id) { mutableStateOf(settings.getGroupAuto(g.id)) }
                    Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OperatorAvatarImage(avatarUri = g.avatarUri, name = g.operatorName, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.operatorName, fontSize = 14.sp, color = TextPrimary)
                            Text("到达设定时间后自动聊天", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(checked = idleAuto, onCheckedChange = { b ->
                            idleAuto = b
                            viewModel.setAutoGroupChatEnabled(g.id, b)
                        }, colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = PrimaryContainer, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Divider))
                    }
                    HorizontalDivider(color = Divider)
                }
            }
        }
    }
}
