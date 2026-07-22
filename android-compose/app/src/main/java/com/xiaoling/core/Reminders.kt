package com.xiaoling.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.xiaoling.service.ReminderReceiver
import org.json.JSONObject
import java.util.Calendar

/**
 * 语音提醒调度:从口语解析时间(如"每天早上八点""明天下午三点")→ AlarmManager 定时。
 * 到点由 ReminderReceiver 触发,语音播报 + 通知。纯语音闭环,老人不用手动设。
 */
object Reminders {

    enum class MissingPart { TIME, CONTENT }

    fun missingPart(raw: String): MissingPart? = when {
        !hasTime(raw) -> MissingPart.TIME
        !hasContent(raw) -> MissingPart.CONTENT
        else -> null
    }

    private fun hasTime(raw: String): Boolean =
        Regex("([0-9一二三四五六七八九十两]+)\\s*点").containsMatchIn(raw) ||
            Regex("([0-9一二三四五六七八九十两]+|半)\\s*(分钟|小时)后").containsMatchIn(raw) ||
            Regex("一会儿|等会儿|待会儿|今晚|明早|明晚|早晚|早中晚").containsMatchIn(raw)

    private fun hasContent(raw: String): Boolean {
        if (Regex("闹钟|叫醒").containsMatchIn(raw) && hasTime(raw)) return true
        val content = raw
            .replace(Regex("提醒我?|帮我|麻烦|记得|别忘了|设置(一个)?提醒|闹钟|叫醒|定个?|设个?"), "")
            .replace(Regex("(每天|每日|明天|今天|今晚|明早|明晚|上午|下午|晚上|傍晚)?\\s*[0-9一二三四五六七八九十]+\\s*点\\s*(半|[0-9]+分?)?"), "")
            .replace(Regex("([0-9一二三四五六七八九十]+|半)\\s*(分钟|小时)后|一会儿|等会儿|待会儿"), "")
            .replace(Regex("[，,。.!！?？的呀啊吧呢\\s]"), "")
        return content.isNotBlank()
    }

    /** 解析并排定一条提醒。返回给用户的确认话术。 */
    fun schedule(ctx: Context, raw: String): String {
        val cal = parseTime(raw)
        val content = extractContent(raw)
        if (Regex("闹钟|叫醒").containsMatchIn(raw) && setSystemAlarm(ctx, cal, content, raw)) {
            return "好的,系统闹钟已经设好,会${describeTime(raw)}叫醒您。"
        }
        if (raw.contains("早中晚") || raw.contains("早晚")) {
            val hours = if (raw.contains("早中晚")) listOf(8, 12, 20) else listOf(8, 20)
            hours.forEachIndexed { index, hour ->
                val at = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
                }
                scheduleAlarmManager(ctx, at, content, content.hashCode() + index, daily = true, raw = raw)
            }
            return "好的,我会每天${if (hours.size == 3) "早中晚" else "早晚"}提醒您$content。"
        }
        // 用提醒内容的 hash 做 request code:同一件事(如"每天八点吃药")重复说只会更新同一个闹钟,不会叠加
        val id = content.hashCode()

        scheduleAlarmManager(ctx, cal, content, id, raw.contains("每天") || raw.contains("每日"), raw)
        return "好的,我会${describeTime(raw)}提醒您${content},放心。"
    }

    private fun scheduleAlarmManager(ctx: Context, cal: Calendar, content: String, id: Int, daily: Boolean, raw: String) {
        persist(ctx, cal, content, id, daily, raw)

        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("content", content)
            putExtra("raw", raw)
            putExtra("daily", daily)
            putExtra("request_id", id)
            putExtra("hour", cal.get(Calendar.HOUR_OF_DAY))
            putExtra("minute", cal.get(Calendar.MINUTE))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            // 每日提醒也按单次精确闹钟调度,触发后由 Receiver 排下一天,避免 setRepeating 漂移。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun rescheduleDaily(ctx: Context, raw: String, content: String, id: Int, hour: Int, minute: Int) {
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            do { add(Calendar.DAY_OF_MONTH, 1) } while (timeInMillis <= System.currentTimeMillis())
        }
        scheduleAlarmManager(ctx.applicationContext, next, content, id, daily = true, raw = raw)
    }

    fun markTriggered(ctx: Context, id: Int, daily: Boolean) {
        if (!daily) reminderPrefs(ctx).edit().remove(id.toString()).apply()
    }

    /** 手机重启或应用更新后恢复尚未触发的本地提醒。 */
    fun restore(ctx: Context) {
        val now = System.currentTimeMillis()
        reminderPrefs(ctx).all.values.mapNotNull { it as? String }.forEach { encoded ->
            try {
                val item = JSONObject(encoded)
                val daily = item.optBoolean("daily")
                val id = item.getInt("id")
                val content = item.optString("content", "该做的事")
                val raw = item.optString("raw")
                val cal = Calendar.getInstance().apply {
                    timeInMillis = item.optLong("at", now + 60_000L)
                    if (daily && timeInMillis <= now) {
                        set(Calendar.HOUR_OF_DAY, item.optInt("hour", 8))
                        set(Calendar.MINUTE, item.optInt("minute", 0))
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        while (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                if (daily || cal.timeInMillis > now) {
                    scheduleAlarmManager(ctx.applicationContext, cal, content, id, daily, raw)
                } else {
                    reminderPrefs(ctx).edit().remove(id.toString()).apply()
                }
            } catch (_: Exception) {}
        }
    }

    private fun persist(ctx: Context, cal: Calendar, content: String, id: Int, daily: Boolean, raw: String) {
        val item = JSONObject()
            .put("id", id)
            .put("at", cal.timeInMillis)
            .put("hour", cal.get(Calendar.HOUR_OF_DAY))
            .put("minute", cal.get(Calendar.MINUTE))
            .put("content", content)
            .put("raw", raw)
            .put("daily", daily)
        reminderPrefs(ctx).edit().putString(id.toString(), item.toString()).apply()
    }

    private fun reminderPrefs(ctx: Context) =
        ctx.getSharedPreferences("xiaoling_reminders", Context.MODE_PRIVATE)

    private fun setSystemAlarm(ctx: Context, cal: Calendar, content: String, raw: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_MESSAGE, content.ifBlank { "小灵叫您起床" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (raw.contains("每天") || raw.contains("每日")) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2, 3, 4, 5, 6, 7))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try { ctx.startActivity(intent); true } catch (_: Exception) { false }
    }

    /** 从口语解析出时间;解析不出默认下一整点。 */
    private fun parseTime(raw: String): Calendar {
        val cal = Calendar.getInstance()
        Regex("([0-9一二三四五六七八九十两]+|半)\\s*(分钟|小时)后").find(raw)?.let { m ->
            val amount = if (m.groupValues[1] == "半") 30 else
                (m.groupValues[1].toIntOrNull() ?: cnNum(m.groupValues[1])).coerceAtLeast(1)
            if (m.groupValues[2] == "小时") cal.add(Calendar.MINUTE, amount * 60)
            else cal.add(Calendar.MINUTE, amount)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal
        }
        if (Regex("一会儿|等会儿|待会儿").containsMatchIn(raw)) {
            cal.add(Calendar.MINUTE, 10)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal
        }
        when {
            raw.contains("明早") && !raw.contains("点") -> {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 8)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal
            }
            raw.contains("明晚") && !raw.contains("点") -> {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 20)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal
            }
            raw.contains("今晚") && !raw.contains("点") -> {
                cal.set(Calendar.HOUR_OF_DAY, 20)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                return cal
            }
        }
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
            Regex("([一二三四五六七八九十两]+)\\s*点\\s*(半|一刻|三刻|[一二三四五六七八九十两]+分?)?").find(raw)?.let { m ->
                hour = cnNum(m.groupValues[1])
                minute = when (val mm = m.groupValues[2].trimEnd('分')) {
                    "半" -> 30
                    "一刻" -> 15
                    "三刻" -> 45
                    "" -> 0
                    else -> cnNum(mm).coerceIn(0, 59)
                }
            }
        }
        // 上午/下午/晚上
        val pm = raw.contains("下午") || raw.contains("晚上") || raw.contains("傍晚")
        if (hour in 1..11 && pm) hour += 12
        if (hour < 0) { hour = cal.get(Calendar.HOUR_OF_DAY) + 1; minute = 0 }

        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (raw.contains("明天") || raw.contains("明早") || raw.contains("明晚")) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // 今天已过点时从下一次有效时刻开始,避免每日提醒创建后立刻误触发。
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal
    }

    private fun extractContent(raw: String): String {
        var s = raw.replace(Regex("提醒我?|帮我|麻烦|记得|别忘了|设置(一个)?提醒|闹钟|叫醒|定个?|设个?"), "")
        s = s.replace(Regex("(每天|每日|明天|今天|上午|下午|晚上|傍晚)?\\s*[0-9一二三四五六七八九十]+\\s*点\\s*(半|[0-9]+分?)?"), "")
        s = s.replace(Regex("([0-9一二三四五六七八九十]+|半)\\s*(分钟|小时)后|一会儿|等会儿|待会儿"), "")
        s = s.replace(Regex("今晚|明早|明晚"), "")
        s = s.replace(Regex("早中晚|早晚"), "")
        s = s.trim().trim('，', ',', '。', '.', '的')
        return if (s.isBlank()) {
            if (Regex("闹钟|叫醒").containsMatchIn(raw)) "起床" else "该做的事"
        } else s
    }

    private fun describeTime(raw: String): String {
        Regex("([0-9一二三四五六七八九十两]+|半)\\s*(分钟|小时)后|一会儿|等会儿|待会儿").find(raw)?.let {
            return it.value
        }
        Regex("今晚|明早|明晚").find(raw)?.let { return it.value }
        val m = Regex("(每天|明天|今天)?\\s*(上午|下午|晚上)?\\s*([0-9一二三四五六七八九十]+点(?:半)?)").find(raw)
        return m?.value?.trim() ?: "到点"
    }

    private fun cnNum(s: String): Int {
        val map = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
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
