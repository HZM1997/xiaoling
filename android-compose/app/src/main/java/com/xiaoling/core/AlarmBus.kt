package com.xiaoling.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 进程内事件总线:后台(短信接收器/来电服务)检测到诈骗时,推给 App UI 让形象即时"警惕"。
 * App 存活时有订阅者即时反应;App 未启动时靠通知 + 冷启动读取 FraudStore.pending 兜底。
 */
object AlarmBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun post(reason: String) { _events.tryEmit(reason) }
}
