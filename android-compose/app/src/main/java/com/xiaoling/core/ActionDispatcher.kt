package com.xiaoling.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.SearchManager
import android.media.MediaStore
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import androidx.core.content.ContextCompat

/**
 * 把大脑返回的 action 变成真实的 Android 行为(拨号/导航/呼救/防诈震动)。
 * 普通电话用 ACTION_DIAL(免权限);SOS 用 ACTION_CALL(需 CALL_PHONE,失败退回 DIAL)。
 */
object ActionDispatcher {

    fun missingRuntimePermissions(ctx: Context, action: JSONObject): List<String> {
        val type = action.optString("type")
        val missing = ArrayList<String>(2)
        if (type == "CALL") {
            val target = action.optString("target")
            if (!looksLikeNumber(target) && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.READ_CONTACTS)
            }
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.CALL_PHONE)
            }
        } else if (type == "CALL_NUMBER" || type == "SOS") {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.CALL_PHONE)
            }
        }
        return missing.distinct()
    }

    /** @return 给用户的补充提示(可为空);真正的语音由 TTS 负责 */
    fun execute(ctx: Context, action: JSONObject): String? {
        val app = ctx.applicationContext
        return when (action.optString("type")) {
            "CALL" -> {
                val target = action.optString("target")
                val num = target.takeIf(::looksLikeNumber)?.filter { it.isDigit() || it == '+' }
                    ?: Contacts.lookup(app, target)
                if (num.isNullOrBlank()) {
                    "没找到联系人「$target」,您可以说全名字再试。"
                } else {
                    if (call(app, num)) null else "电话没有拨出去,请检查电话权限和默认电话应用。"
                }
            }
            "SOS" -> {
                val num = action.optString("call", "120")
                val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    app.startActivity(call)
                } catch (e: Exception) {
                    // 未授 CALL_PHONE 或异常:退回拨号盘
                    view(app, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                }
                null
            }
            "CALL_NUMBER" -> {
                val num = action.optString("number")
                if (num.isNotBlank() && call(app, num)) null else "电话没有拨出去,请检查电话权限。"
            }
            "OPEN_URI" -> {
                val uri = action.optString("uri")
                val ok = view(app, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                if (!ok) {
                    // 高德没装 → 退回系统地图 geo:
                    val kw = Regex("keywords=([^&]+)").find(uri)?.groupValues?.get(1) ?: ""
                    view(app, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$kw")))
                }
                null
            }
            "FRAUD_WARN", "ALERT" -> { vibrate(app); null }
            "PLAY" -> {
                val kw = action.optString("keyword", "戏曲")
                val systemPlay = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, kw)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                }
                val ok = view(app, systemPlay) ||
                    view(app, Intent(Intent.ACTION_VIEW, Uri.parse("qqmusic://qq.com/ui/search?key=" + Uri.encode(kw)))) ||
                    view(app, Intent(Intent.ACTION_VIEW, Uri.parse("orpheus://search/" + Uri.encode(kw)))) ||
                    view(app, Intent(Intent.ACTION_VIEW, Uri.parse("https://m.baidu.com/s?word=" + Uri.encode("$kw 在线播放"))))
                if (ok) null else "手机里没有找到可播放的影音应用。"
            }
            "REMIND" -> {
                // 语音提醒:解析时间并用 AlarmManager 定时;解析不出就存为下一整点提醒
                val raw = action.optString("raw")
                Reminders.schedule(app, raw)
            }
            "SEND_MEMO" -> {
                // 亲情语音留言:把录好的语音分享给匹配到的联系人
                val target = action.optString("target")
                val f = VoiceMemo.lastFile
                if (f == null || !f.exists()) {
                    "还没有录音,先跟我说『录一段留言』吧。"
                } else {
                    val num = Contacts.lookup(app, target)
                    shareAudio(app, f, num, target)
                    null
                }
            }
            "SPEAK" -> null   // 翻译结果由 TTS 念出
            else -> null
        }
    }

    private fun view(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
    }

    private fun call(ctx: Context, number: String): Boolean {
        val uri = Uri.parse("tel:" + Uri.encode(number, "+*#"))
        val direct = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return if (direct) {
            view(ctx, Intent(Intent.ACTION_CALL, uri)) || view(ctx, Intent(Intent.ACTION_DIAL, uri))
        } else {
            view(ctx, Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun looksLikeNumber(value: String): Boolean =
        value.replace(Regex("[\\s-]"), "").matches(Regex("^\\+?[0-9*#]{3,20}$"))

    /** 把语音留言分享给联系人:优先带号码的分享(微信/短信里选到人),否则系统分享面板 */
    private fun shareAudio(ctx: Context, f: java.io.File, number: String?, target: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "这是给${target}的语音留言")
                if (!number.isNullOrBlank()) putExtra("address", number)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(send, "发送语音留言给${target}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* 分享失败,忽略 */ }
    }

    private fun vibrate(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(500)
            }
        } catch (e: Exception) { /* 无震动器,忽略 */ }
    }
}
