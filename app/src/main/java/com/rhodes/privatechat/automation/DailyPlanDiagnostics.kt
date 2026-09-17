package com.rhodes.privatechat.automation

import android.content.Context
import com.rhodes.privatechat.util.DebugLogger

/**
 * 当天主动消息计划的诊断记录，用于在设置页回答"为什么今天没人来找我"。
 *
 * 刻意使用独立的明文 SharedPreferences（rhodes_daily_plan），而不是加密设置库：
 * 加密库每次提交都会重新加密并重写整个文件，条目变多后已经出现过明显变慢；
 * 这里每天都会新增记录，写进加密库会把那个问题持续放大。
 *
 * 内容只有角色名、投递编号、时间和跳过原因，不含任何聊天内容，也不参与备份与恢复。
 * 每次写入新计划时会清理其它日期的键，所以文件大小有界。
 */
object DailyPlanDiagnostics {
    private const val PREFS = "rhodes_daily_plan"
    private const val PLAN_KEY_PREFIX = "plan_"
    private const val LOG_KEY_PREFIX = "log_"
    private const val MAX_LOG_LINES = 40
    private const val MAX_PLAN_LINES = 30

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 写入当天计划（覆盖），并清理其它日期留下的键。 */
    fun recordPlan(context: Context, cycle: String, entries: List<String>) {
        runCatching {
            val store = prefs(context)
            val editor = store.edit()
            store.all.keys
                .filter { it.startsWith(PLAN_KEY_PREFIX) || it.startsWith(LOG_KEY_PREFIX) }
                .filterNot { it.endsWith(cycle) }
                .forEach { editor.remove(it) }
            editor.putString(PLAN_KEY_PREFIX + cycle, entries.take(MAX_PLAN_LINES).joinToString("\n"))
            editor.apply()
        }.onFailure { DebugLogger.diagnostic("DailyPlan/WriteFailed", it.javaClass.simpleName) }
    }

    /** 追加一条投递结果（已发 / 跳过及原因 / 失败）。 */
    fun recordResult(context: Context, cycle: String, line: String) {
        runCatching {
            val store = prefs(context)
            val key = LOG_KEY_PREFIX + cycle
            val lines = store.getString(key, "").orEmpty()
                .lines().filter { it.isNotBlank() }.toMutableList()
            lines += "${nowLabel()} $line"
            while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
            store.edit().putString(key, lines.joinToString("\n")).apply()
        }.onFailure { DebugLogger.diagnostic("DailyPlan/WriteFailed", it.javaClass.simpleName) }
    }

    fun plan(context: Context, cycle: String): List<String> =
        runCatching { prefs(context).getString(PLAN_KEY_PREFIX + cycle, "").orEmpty() }
            .getOrDefault("")
            .lines().filter { it.isNotBlank() }

    fun log(context: Context, cycle: String): List<String> =
        runCatching { prefs(context).getString(LOG_KEY_PREFIX + cycle, "").orEmpty() }
            .getOrDefault("")
            .lines().filter { it.isNotBlank() }

    private fun nowLabel(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
}
