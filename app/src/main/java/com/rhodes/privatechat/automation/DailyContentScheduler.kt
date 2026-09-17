package com.rhodes.privatechat.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Creates one deterministic delivery plan per Beijing natural day. */
object DailyContentScheduler {
    const val TYPE_PLAN = "plan"
    const val TYPE_MOMENT = "moment"
    const val TYPE_PRIVATE = "private"
    const val TYPE_PRIVATE_FOLLOW_UP = "private_follow_up"
    /** 同一角色当天的第几条主动消息。 */
    const val DELIVERY_FIRST = "0"
    const val DELIVERY_SECOND = "1"
    const val DELIVERY_FOLLOW_UP = "f"
    private const val SECOND_MESSAGE_MIN_GAP_MINUTES = 45L
    private const val PLAN_WORK = "daily-content-plan"
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    fun schedulePlanner(context: Context) {
        val now = System.currentTimeMillis()
        val next = nextCycleStart(now)
        val request = PeriodicWorkRequestBuilder<DailyContentWorker>(24, TimeUnit.HOURS)
            .setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("type" to TYPE_PLAN))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PLAN_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun ensureTodayPlan(context: Context, repository: ChatRepository, settings: SettingsRepository) =
        runBlocking { ensureTodayPlanSuspending(context, repository, settings) }

    /**
     * Suspend form of the planner. The worker used to call a runBlocking wrapper, which parked a
     * Dispatchers.Default thread inside joinBlocking. Private-chat prompt assembly reads the database
     * through that same shared pool, so a running planner starved the very first read and the whole reply
     * died on the 50s prompt_build timeout - proven by the thread dump taken at that timeout.
     */
    suspend fun ensureTodayPlanSuspending(context: Context, repository: ChatRepository, settings: SettingsRepository) {
        if (!settings.autoAiEnabled) return
        val cycle = cycleId()
        if (settings.getBoolean("daily_content_planned_$cycle", false)) return
        val now = System.currentTimeMillis()
        val operators = repository.getAllOperatorsSync()
        val cycleStart = cycleStart(now)
        val cycleEnd = cycleStart + TimeUnit.DAYS.toMillis(1)
        operators.filter { settings.getOperatorDynPermission(it.id) }.forEach { op ->
            repeat(settings.dailyMomentTarget) { index ->
                schedule(context, TYPE_MOMENT, op.id, index.toString(), scheduledTime(cycleStart, cycleEnd, "moment:${op.id}:$index", now))
            }
        }
        val dispatchedOperatorIds = repository.getActiveDispatches()
            .flatMap { it.operatorIds.split(",") }
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val candidates = operators.filter { op ->
            settings.idleProactiveChatEnabled &&
            settings.getOperatorMsgPermission(op.id) && (0..99).random() < settings.dailyProactiveChance &&
                op.id !in dispatchedOperatorIds &&
                hasConversationContext(repository, op.id)
        }.shuffled()
        // 额度分配：约四分之一留给"同一角色第二句"，其余给不同角色。
        // 这样既有广度（每天不同的人来），又有一两次连发（更像真人发消息）。
        val budget = proactiveBudgetFor(cycleEnd - now, settings.dailyProactiveMax)
        val perOperatorMax = settings.proactivePerOperatorDailyMax.coerceAtLeast(1)
        val secondSlots = if (perOperatorMax >= 2 && budget >= 4) (budget * 25 / 100).coerceAtLeast(1) else 0
        val speakers = candidates.take((budget - secondSlots).coerceAtLeast(1))
        val firstDeliveryAt = mutableListOf<Pair<String, Long>>()
        val planEntries = mutableListOf<String>()
        fun timeLabel(at: Long): String =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
        speakers.forEachIndexed { index, op ->
            val at = avoidQuietHours(scheduledTime(cycleStart, cycleEnd, "private:${op.id}:$index", now), cycleEnd, settings, now)
            firstDeliveryAt += op.id to at
            planEntries += "${timeLabel(at)} ${op.name} 第1条"
            schedule(context, TYPE_PRIVATE, op.id, DELIVERY_FIRST, at)
        }
        // 第二句：与第一句至少间隔 45 分钟，且仍然避开免打扰时段。
        firstDeliveryAt.shuffled().take(secondSlots).forEachIndexed { index, (opId, firstAt) ->
            val spreadMs = TimeUnit.MINUTES.toMillis(SECOND_MESSAGE_MIN_GAP_MINUTES)
            val extra = spreadMs + (opId.hashCode().toLong() and Long.MAX_VALUE) % (TimeUnit.HOURS.toMillis(3))
            val at = (firstAt + extra).coerceAtMost(cycleEnd - TimeUnit.MINUTES.toMillis(10))
            val adjusted = avoidQuietHours(at, cycleEnd, settings, now)
            if (adjusted >= firstAt + TimeUnit.MINUTES.toMillis(SECOND_MESSAGE_MIN_GAP_MINUTES / 2)) {
                planEntries += "${timeLabel(adjusted)} ${operators.firstOrNull { it.id == opId }?.name ?: opId} 第2条"
                schedule(context, TYPE_PRIVATE, opId, DELIVERY_SECOND, adjusted)
            }
        }
        // 补一句：紧跟在某条主动消息之后 3~10 分钟，概率由设置决定。用户回复了就会在投递时取消。
        val followUpChance = settings.proactiveFollowUpChance
        if (followUpChance > 0) {
            firstDeliveryAt.forEachIndexed { index, (opId, firstAt) ->
                if ((0..99).random() >= followUpChance) return@forEachIndexed
                val delay = TimeUnit.MINUTES.toMillis(3L + (opId.hashCode().toLong() and Long.MAX_VALUE) % 8L)
                val at = avoidQuietHours(firstAt + delay, cycleEnd, settings, now)
                if (at <= cycleEnd - TimeUnit.MINUTES.toMillis(5)) {
                    // 每个角色每天最多一条补句，所以投递编号可以用固定值，取消计划时才枚举得出来。
                    planEntries += "${timeLabel(at)} ${operators.firstOrNull { it.id == opId }?.name ?: opId} 补一句"
                    schedule(context, TYPE_PRIVATE_FOLLOW_UP, opId, DELIVERY_FOLLOW_UP, at)
                }
            }
        }
        DailyPlanDiagnostics.recordPlan(
            context,
            cycle,
            if (planEntries.isEmpty()) listOf("今天没有安排任何主动消息（检查自动内容、主动私聊开关与角色权限）")
            else planEntries.sorted(),
        )
        settings.putBoolean("daily_content_planned_$cycle", true)
    }

    /** Replaces only today's pending deliveries after the user saves new automatic-content settings. */
    fun rebuildTodayPlan(context: Context, repository: ChatRepository, settings: SettingsRepository) =
        runBlocking { rebuildTodayPlanSuspending(context, repository, settings) }

    /** Suspend form; also avoids the old nested runBlocking (this wrapping ensureTodayPlan). */
    suspend fun rebuildTodayPlanSuspending(context: Context, repository: ChatRepository, settings: SettingsRepository) {
        val cycle = cycleId()
        val operators = repository.getAllOperatorsSync()
        val workManager = WorkManager.getInstance(context)
        operators.forEach { op ->
            // dailyMomentTarget is capped at three, so these cover every possible old plan.
            repeat(3) { index -> workManager.cancelUniqueWork(workName(cycle, TYPE_MOMENT, op.id, index.toString())) }
            cancelPrivateDeliveries(workManager, cycle, op.id)
        }
        settings.remove("daily_content_planned_$cycle")
        ensureTodayPlanSuspending(context, repository, settings)
    }

    /** 取消当天某个角色的全部主动消息投递（第一句、第二句、补句）。 */
    private fun cancelPrivateDeliveries(workManager: WorkManager, cycle: String, operatorId: String) {
        listOf(DELIVERY_FIRST, DELIVERY_SECOND, DELIVERY_FOLLOW_UP).forEach { deliveryId ->
            workManager.cancelUniqueWork(workName(cycle, TYPE_PRIVATE, operatorId, deliveryId))
            workManager.cancelUniqueWork(workName(cycle, TYPE_PRIVATE_FOLLOW_UP, operatorId, deliveryId))
        }
    }

    /** Settings must not fail just because a best-effort background plan rebuild fails. */
    fun rebuildTodayPlanAsync(context: Context, repository: ChatRepository, settings: SettingsRepository, onComplete: (String?) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val error = runCatching { rebuildTodayPlan(context.applicationContext, repository, settings) }
                .exceptionOrNull()
                ?.let { "自动计划重建失败：${it.message?.take(80) ?: it.javaClass.simpleName}" }
            if (error != null) DebugLogger.diagnostic("DailyContent/RebuildFailed", error)
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { onComplete(error) }
        }
    }

    fun cancelTodayPlanForOperators(context: Context, operatorIds: Collection<String>, onComplete: () -> Unit = {}) {
        if (operatorIds.isEmpty()) { onComplete(); return }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val cycle = cycleId()
                val workManager = WorkManager.getInstance(context.applicationContext)
                operatorIds.forEach { operatorId ->
                    repeat(3) { index -> workManager.cancelUniqueWork(workName(cycle, TYPE_MOMENT, operatorId, index.toString())) }
                    cancelPrivateDeliveries(workManager, cycle, operatorId)
                }
            }.onFailure { DebugLogger.diagnostic("DailyContent/CancelDeletedOperatorPlanFailed", it.message ?: it.javaClass.simpleName) }
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    /**
     * 当天剩余时间不足以把消息自然铺开时收缩配额。
     *
     * 计划常常是在玩家打开 App 时才补建的（后台任务被系统杀掉、跨天首次启动等）。
     * 如果晚上 23 点才建计划却仍发满 8 条，玩家会在几十分钟内连续收到一堆消息，体验很差。
     */
    private fun proactiveBudgetFor(remainingMs: Long, configuredMax: Int): Int = when {
        remainingMs <= TimeUnit.HOURS.toMillis(2) -> minOf(2, configuredMax)
        remainingMs <= TimeUnit.HOURS.toMillis(6) -> minOf(4, configuredMax)
        else -> configuredMax
    }

    private suspend fun hasConversationContext(repository: ChatRepository, operatorId: String): Boolean {
        val session = repository.getSessionByOperator(operatorId)
        return session != null && (repository.getMessagesSync(session.id).isNotEmpty() ||
            repository.getShortTermMemory(session.id) != null || repository.getLongTermImpression(operatorId) != null)
    }

    private fun schedule(context: Context, type: String, operatorId: String, deliveryId: String, scheduledAt: Long) {
        val cycle = cycleId()
        val name = workName(cycle, type, operatorId, deliveryId)
        val request = OneTimeWorkRequestBuilder<DailyContentWorker>()
            .setInitialDelay((scheduledAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("type" to type, "operatorId" to operatorId, "deliveryId" to deliveryId, "cycle" to cycle))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    private fun workName(cycle: String, type: String, operatorId: String, deliveryId: String) =
        "daily-content-$cycle-$type-$operatorId-$deliveryId"

    fun cycleId(now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        return "%04d%02d%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    private fun cycleStart(now: Long): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return cal.timeInMillis
    }

    private fun nextCycleStart(now: Long): Long = cycleStart(now).let { if (it > now) it else it + TimeUnit.DAYS.toMillis(1) }

    private fun scheduledTime(start: Long, end: Long, seed: String, now: Long): Long {
        val span = end - start - TimeUnit.HOURS.toMillis(1)
        val offset = (seed.hashCode().toLong() and Long.MAX_VALUE) % span
        val planned = start + TimeUnit.MINUTES.toMillis(30L) + offset
        if (planned >= now) return planned

        // Planning can happen after the normal slot (first launch, restored worker, etc.).
        // Spread overdue deliveries across the remaining day instead of firing them together.
        val recoveryStart = now + TimeUnit.MINUTES.toMillis(5L)
        val recoveryEnd = end - TimeUnit.MINUTES.toMillis(10L)
        if (recoveryEnd <= recoveryStart) return recoveryStart
        return recoveryStart + ((seed.hashCode().toLong() and Long.MAX_VALUE) % (recoveryEnd - recoveryStart))
    }

    private fun avoidQuietHours(at: Long, cycleEnd: Long, settings: SettingsRepository, now: Long): Long {
        if (!settings.quietHoursEnabled) return at
        val cal = Calendar.getInstance(zone).apply { timeInMillis = at }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val quiet = when {
            settings.quietHoursStart == settings.quietHoursEnd -> false
            settings.quietHoursStart < settings.quietHoursEnd -> hour in settings.quietHoursStart until settings.quietHoursEnd
            else -> hour >= settings.quietHoursStart || hour < settings.quietHoursEnd
        }
        if (!quiet) return at
        cal.set(Calendar.HOUR_OF_DAY, settings.quietHoursEnd); cal.set(Calendar.MINUTE, 10 + (at % 40).toInt()); cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis.coerceAtMost(cycleEnd - TimeUnit.MINUTES.toMillis(10)).coerceAtLeast(now + TimeUnit.MINUTES.toMillis(2))
    }
}
