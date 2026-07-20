package com.xiaoling

import android.app.Application
import android.content.Intent
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃捕获:任何未捕获异常都把完整堆栈+设备信息显示到 CrashActivity,
 * 方便老人/家人直接截图发开发者定位(尤其 release 包在真机上的启动崩溃)。
 */
class XiaolingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = buildString {
                    append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("安卓: ").append(Build.VERSION.RELEASE).append(" (API ")
                        .append(Build.VERSION.SDK_INT).append(")\n")
                    append("版本: ").append(BuildConfig.VERSION_NAME)
                        .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append("线程: ").append(t.name).append("\n\n")
                    append(sw.toString())
                }
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("trace", info)
                )
            } catch (_: Throwable) { /* 上报本身出错也不再抛,免得死循环 */ }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
