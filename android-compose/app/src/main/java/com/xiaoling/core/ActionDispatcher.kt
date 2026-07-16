package com.xiaoling.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/**
 * 把大脑返回的 action 变成真实的 Android 行为(拨号/导航/呼救/防诈震动)。
 * 普通电话用 ACTION_DIAL(免权限);SOS 用 ACTION_CALL(需 CALL_PHONE,失败退回 DIAL)。
 */
object ActionDispatcher {

    /** @return 给用户的补充提示(可为空);真正的语音由 TTS 负责 */
    fun execute(ctx: Context, action: JSONObject): String? {
        val app = ctx.applicationContext
        return when (action.optString("type")) {
            "CALL" -> {
                val target = action.optString("target")
                val num = Contacts.lookup(app, target)
                if (num.isNullOrBlank()) {
                    "没找到联系人「$target」,您可以说全名字再试。"
                } else {
                    view(app, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                    null
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
                if (num.isNotBlank()) view(app, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                null
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
            "FRAUD_WARN" -> { vibrate(app); null }
            "PLAY", "REMIND", "SPEAK" -> null   // 仅语音播报(翻译结果由 TTS 念出)
            else -> null
        }
    }

    private fun view(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
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
