package com.jingling

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * 极简单页:全屏就是精灵形象 + 一个大按钮(给暂时不便语音时兜底)。
 * 适老原则:超大字、超大点击区、高对比、零层级跳转。
 * 正常使用完全靠"小灵小灵"唤醒,进来即对话,无需手动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var agent: VoiceAgent

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)   // 一张精灵大图 + 一个"按住说话"大按钮

        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }

        agent = VoiceAgent(this).apply { init() }

        // 大按钮兜底:不方便喊唤醒词时,点一下也能说话
        findViewById<android.view.View>(R.id.btnTalk).setOnClickListener { agent.onWake() }
    }
}
