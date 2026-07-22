package com.xiaoling.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.xiaoling.core.Reminders

/** 系统重启、应用更新或用户解锁后尽快恢复“小灵”离线唤醒守护。 */
class WakeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Reminders.restore(context.applicationContext)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        WakeService.start(context.applicationContext)
    }
}
