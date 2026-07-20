package com.xiaoling.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xiaoling.service.ReminderReceiver
import java.util.Calendar

/**
 * 语音提醒调度:从口语解析时间(如"每天早上八点""明天下午三点")→ AlarmManager 定时。
 * 到点由 ReminderReceiver 触发,语音播报 + 通知。纯语音闭环,老人不用手动设。
 */
object Reminders {

    /** 解析并排定一条提醒。返回给用户的确认话术。 */
    fun schedule(ctx: Context, raw: String): String {
        val cal = parseTime(raw)
        val content = extractContent(raw)
        // 用提醒内容的 hash 做 request code:同一件事(如"每天八点吃药")重复说只会更新同一个闹钟,不会叠加
        val id = content.hashCode()

        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("content", content)
            putExtra("raw", raw)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val daily = raw.contains("每天") || raw.contains("每日")
        try {
            if (daily) {
                am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis,
                    AlarmManager.INTERVAL_DAY, pi)
            } else {
                // 精确闹钟需 SCHEDULE_EXACT_ALARM(Android 12+),没权限就退普通闹钟
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
            }
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
        return "好的,我会${describeTime(raw)}提醒您${content},放心。"
    }

    /** 从口语解析出时间;解析不出默认下一整点。 */
    private fun parseTime(raw: String): Calendar {
        val cal = Calendar.getInstance()
        var hour = -1
        var minute = 0
        // 阿拉伯数字点
        Regex("([0-9]{1,2})\\s*点\\s*(半|[0-9]{1,2})?").find(raw)?.let { m ->
            hour = m.groupValues[1].toIntOrNull() ?: -1
            val mm = m.groupValues[2]
            minute = if (mm == "半") 30 else mm.toIntOrNull() ?: 0
        }
        // 中文数字点
        if (hour < 0) {
            Regex("([一二三四五六七八九十]+)\\s*点\\s*(半)?").find(raw)?.let { m ->
                hour = cnNum(m.groupValues[1])
                minute = if (m.groupValues[2] == "半") 30 else 0
            }
        }
        // 上午/下午/晚上
        val pm = raw.contains("下午") || raw.contains("晚上") || raw.contains("傍晚")
        if (hour in 1..11 && pm) hour += 12
        if (hour < 0) { hour = cal.get(Calendar.HOUR_OF_DAY) + 1; minute = 0 }

        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        if (raw.contains("明天")) cal.add(Calendar.DAY_OF_MONTH, 1)
        // 已过点且非每天 → 顺延到明天
        if (cal.timeInMillis <= System.currentTimeMillis() && !raw.contains("每天")) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal
    }

    private fun extractContent(raw: String): String {
        var s = raw.replace(Regex("提醒我?|记得|要"), "")
        s = s.replace(Regex("(每天|每日|明天|今天|上午|下午|晚上|傍晚)?\\s*[0-9一二三四五六七八九十]+\\s*点\\s*(半|[0-9]+分?)?"), "")
        s = s.trim().trim('，', ',', '。', '.', '的')
        return if (s.isBlank()) "该做的事" else s
    }

    private fun describeTime(raw: String): String {
        val m = Regex("(每天|明天|今天)?\\s*(上午|下午|晚上)?\\s*([0-9一二三四五六七八九十]+点(?:半)?)").find(raw)
        return m?.value?.trim() ?: "到点"
    }

    private fun cnNum(s: String): Int {
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s == "十") return 10
        if (s.startsWith("十")) return 10 + (map[s.getOrNull(1)] ?: 0)
        if (s.endsWith("十")) return (map[s[0]] ?: 0) * 10
        if (s.contains("十")) {
            val parts = s.split("十")
            return (map[parts[0].getOrNull(0)] ?: 1) * 10 + (map[parts.getOrNull(1)?.getOrNull(0)] ?: 0)
        }
        return map[s.getOrNull(0)] ?: -1
    }
}
