package com.rhodes.privatechat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodes.privatechat.automation.DailyContentScheduler
import com.rhodes.privatechat.automation.DailyPlanDiagnostics
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.ui.theme.BG
import com.rhodes.privatechat.ui.theme.Card
import com.rhodes.privatechat.ui.theme.Primary
import com.rhodes.privatechat.ui.theme.TextPrimary
import com.rhodes.privatechat.ui.theme.TextSecondary
import com.rhodes.privatechat.viewmodel.MainViewModel
import org.koin.compose.koinInject
import android.widget.Toast

@Composable
fun DailyContentSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings: SettingsRepository = koinInject()
    val viewModel: MainViewModel = koinInject()
    val context = LocalContext.current
    var autoEnabled by remember { mutableStateOf(settings.autoAiEnabled) }
    var dailyMomentsEnabled by remember { mutableStateOf(settings.dailyAutoMomentEnabled) }
    var proactiveEnabled by remember { mutableStateOf(settings.idleProactiveChatEnabled) }
    var permissionAll by remember { mutableStateOf(settings.proactivePermissionAll) }
    var quietHoursEnabled by remember { mutableStateOf(settings.quietHoursEnabled) }
    // 当天主动消息诊断：计划了谁、几点、以及每条为什么没发出去。
    var planLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cycle = DailyContentScheduler.cycleId()
            val plan = DailyPlanDiagnostics.plan(context, cycle)
            val log = DailyPlanDiagnostics.log(context, cycle)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                planLines = plan
                logLines = log
            }
        }
    }
    SaveableSettingsScaffold(
        title = "每日自动内容",
        onBack = onBack,
        modifier = modifier.fillMaxSize().background(BG).systemBarsPadding(),
        icon = { Icon(Icons.Default.AutoAwesome, null, tint = Primary) },
        onSaveRequest = { completeSave, _ ->
            completeSave()
            DailyContentScheduler.rebuildTodayPlanAsync(
                context = context,
                repository = viewModel.repository,
                settings = settings
            ) { error ->
                if (error != null) Toast.makeText(context, "设置已保存，$error，将在下次启动时重试。", Toast.LENGTH_LONG).show()
                else viewModel.refreshAutoGroupChats()
            }
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).imePadding().navigationBarsPadding()) {
            DailyContentInfoCard("固定日计划", "每天按北京时间 00:00 开始新的内容周期。动态和私聊会预先分散安排，并在应用关闭时由系统后台任务投递；系统省电策略可能让实际到达时间略晚。")
            SettingsSectionTitle("总开关")
            SettingsSwitchCard("自动内容", "关闭后不再生成计划动态或主动私聊；手动聊天、手动催发动态不受影响。", autoEnabled) {
                autoEnabled = it
                settings.autoAiEnabled = it
            }
            SettingsSectionTitle("每日动态")
            SettingsSwitchCard("每日固定动态", "开启动态权限的角色每天会生成设定数量的公开动态。", dailyMomentsEnabled, enabled = autoEnabled) {
                dailyMomentsEnabled = it
                settings.dailyAutoMomentEnabled = it
            }
            SettingsParamSlider(settings, "daily_moment_target", "每角色每日动态数", 1, 0f..3f, "每个角色每天自动发几条动态。建议1。太高（超过3）信息流刷屏太快看不过来。", step = 1f, enabled = autoEnabled)
            SettingsSectionTitle("主动私聊")
            DailyContentInfoCard("自然分散发送", "每天从有私聊权限且聊过的角色里随机轮换，时间打散在一天里。刚聊完一段时间内不会又来；同一角色一天最多说两句话。")
            SettingsSwitchCard("主动私聊", "开启后，有主动消息权限且有聊天上下文的干员会主动联系你。默认开启。", proactiveEnabled, enabled = autoEnabled) {
                proactiveEnabled = it
                settings.idleProactiveChatEnabled = it
            }
            SettingsSwitchCard("主动消息权限跟随全部角色", "开启时，除你单独关掉的角色外，所有角色都可以主动联系。关闭时只按权限管理页里单独开启的角色执行。", permissionAll, enabled = autoEnabled && proactiveEnabled) {
                permissionAll = it
                settings.proactivePermissionAll = it
            }
            SettingsParamSlider(settings, "daily_proactive_chance", "角色参与概率", 100, 0f..100f, "每个角色每天进入抽选的概率。建议100：所有聊过的角色都有机会，具体每天来几个由下面的条数上限决定。", step = 5f, enabled = autoEnabled && proactiveEnabled)
            SettingsParamSlider(settings, "daily_proactive_max", "每日主动消息上限", 8, 0f..30f, "每天最多收到几条主动消息（不管来自几个角色）。建议8。太低（低于3）一天只有一两条，太高（超过12）看不过来。", step = 1f, enabled = autoEnabled && proactiveEnabled)
            SettingsParamSlider(settings, "proactive_per_operator_daily_max", "同一角色每日上限", 2, 1f..5f, "同一个角色一天最多主动发几次。建议2，这样有的人会连发两句，更像真人发消息。", step = 1f, enabled = autoEnabled && proactiveEnabled)
            SettingsParamSlider(settings, "proactive_followup_chance", "补一句概率", 15, 0f..100f, "主动消息发出后 3~10 分钟再补一句的概率（补充一个细节或一句关心）。建议15。设为0则不补。若你在这期间回复了，补发会自动取消。", step = 5f, enabled = autoEnabled && proactiveEnabled)
            SettingsParamSlider(settings, "proactive_min_gap_minutes", "距上次互动静默分钟", 45, 0f..240f, "距离你们双方最后一条消息至少间隔多久，角色才会主动联系。建议45，避免刚回复完又来一条。", step = 5f, enabled = autoEnabled && proactiveEnabled)
            SettingsParamSlider(settings, "proactive_quiet_after_user_minutes", "用户发言后静默分钟", 15, 0f..240f, "你刚发过消息后，角色至少等待多久才会再次主动联系。设为0表示不额外等待。", step = 5f, enabled = autoEnabled && proactiveEnabled)
            SettingsSwitchCard("免打扰时段", "开启后主动私聊会避开这个时段，过后再发。动态照常。", quietHoursEnabled, enabled = autoEnabled && proactiveEnabled) {
                quietHoursEnabled = it
                settings.quietHoursEnabled = it
            }
            SettingsParamSlider(settings, "quiet_hours_start", "免打扰开始时间", 1, 0f..23f, "几点开始不打扰（24小时制）。建议凌晨1点。", step = 1f, enabled = autoEnabled && proactiveEnabled && quietHoursEnabled)
            SettingsParamSlider(settings, "quiet_hours_end", "免打扰结束时间", 9, 0f..23f, "几点恢复（24小时制）。建议早上9点；与开始时间相同时不启用免打扰。", step = 1f, enabled = autoEnabled && proactiveEnabled && quietHoursEnabled)
            if (planLines.isNotEmpty() || logLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DailyContentInfoCard(
                    "今天的主动消息",
                    buildString {
                        append("计划：\n")
                        append(planLines.joinToString("\n").ifBlank { "（今天还没有生成计划）" })
                        if (logLines.isNotEmpty()) {
                            append("\n\n实际结果：\n")
                            append(logLines.joinToString("\n"))
                        }
                        append("\n\n说明：计划在每天 00:00 后的首次启动或后台任务运行时生成；系统省电策略可能让实际到达时间略晚。")
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DailyContentInfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
    }
    Spacer(Modifier.height(10.dp))
}
