package com.xiaoling

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** 崩溃信息展示页:纯代码 UI(不依赖任何资源/主题),把堆栈显示出来供截图上报 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "无崩溃信息"
        val tv = TextView(this).apply {
            text = "小灵启动出错了,麻烦把这一屏截图发给开发者定位问题:\n\n$trace"
            setTextIsSelectable(true)
            setPadding(28, 56, 28, 28)
            textSize = 12f
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
