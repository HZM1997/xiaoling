package com.xiaoling.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Screen { Home, Settings }

data class UiState(
    val caption: String = "小灵在这儿,想说什么直接说",
    val mascot: MascotState = MascotState.Idle,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val busy: Boolean = false,
    val screen: Screen = Screen.Home,
    val lastUser: String = "",
    val brainUrl: String = "",
    val live2d: Boolean = false,
    val membership: String = "",     // "" / "basic" / "premium"
    // 子女端·看护统计
    val fraudBlocked: Int = 0,
    val sosLabel: String = "无",
    val medsOk: Boolean = true,
    val familySynced: Boolean = false
)

/** 编排:常听式语音识别 → 大脑(云端/本地兜底) → TTS → 执行动作 → 形象状态 */
class AppState(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _state = MutableStateFlow(
        UiState(
            brainUrl = Settings.brainUrl(app),
            fraudBlocked = FraudStore.count(app),
            live2d = Settings.live2dEnabled(app),
            membership = Membership.tier(app)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { id -> onSpeakDone(id) }

    @Volatile private var autoOn = false     // 常听开关(跨主线程/ TTS 回调线程读写)
    @Volatile private var speaking = false   // TTS 播报中(此时不听,避免听到自己)
    @Volatile private var alarmUntil = 0L    // 警惕态持续到的时间戳(多个事件叠加不早退)
    private var curUtt = ""                   // 当前 TTS utteranceId(被 flush 的旧句忽略)

    init {
        // 后台(短信/来电)检测到诈骗 → 形象即时警惕
        AlarmBus.events.onEach { onExternalFraud(it) }.launchIn(viewModelScope)
        // App 被诈骗事件冷启动 / 刚打开时,读取近 2 分钟内的待处理事件
        FraudStore.takePendingIfRecent(app)?.let { onExternalFraud(it) }
        // 跨设备:订阅家庭组事件(家人设备实时收到老人端的推送),断线自动重连
        subscribePush()
    }

    private fun subscribePush() {
        viewModelScope.launch {
            while (isActive) {
                PushClient.subscribe(app) { ev -> onFamilyEvent(ev) }
                delay(3000)   // 断线重连
            }
        }
    }

    /** 收到家庭组跨设备事件 → 提示 + 语音播报(家人设备侧) */
    private fun onFamilyEvent(ev: org.json.JSONObject) {
        if (ev.optString("sender") == PushClient.deviceId(app)) return   // 忽略自己发的事件,避免自我回声
        val text = ev.optString("text").ifBlank { "家人有一条新的看护提醒" }
        val type = ev.optString("type")
        val prefix = when (type) {
            "fraud_call", "fraud_sms" -> "家人可能遇到诈骗:"
            "sos" -> "家人发起了紧急呼救:"
            else -> "家人看护:"
        }
        speaking = true
        _state.update { it.copy(caption = "🔔 $prefix$text", mascot = MascotState.Caring, speaking = true) }
        curUtt = tts.speak(prefix + text)
    }

    /** 来自短信/来电的高危事件:把形象切到警惕态,并语音+震动强反馈 */
    private fun onExternalFraud(reason: String) {
        FraudStore.clearPending(app)
        val say = "注意!$reason。千万不要转账、不要提供验证码、不要点链接。"
        speaking = true
        _state.update { it.copy(mascot = MascotState.Alarm, caption = say, busy = false, speaking = true, fraudBlocked = FraudStore.count(app)) }
        try { ActionDispatcher.execute(app, JSONObject().put("type", "FRAUD_WARN")) } catch (e: Exception) {}
        viewModelScope.launch { PushClient.emit(app, "fraud_call", reason, System.currentTimeMillis()) }
        curUtt = tts.speak(say)
        scheduleAlarmReset()
    }

    private fun scheduleAlarmReset() {
        val until = System.currentTimeMillis() + 6500L
        alarmUntil = until
        viewModelScope.launch {
            delay(6600L)
            // 只有当没有更晚的警报把 deadline 往后推时才复位,避免叠加事件被提前清掉
            if (System.currentTimeMillis() >= alarmUntil) {
                _state.update {
                    if (it.mascot == MascotState.Alarm) it.copy(mascot = MascotState.Idle, caption = "小灵在这儿,想说什么直接说") else it
                }
            }
        }
    }

    // ---------- 常听式语音(免手动) ----------
    fun startAuto() {
        if (autoOn) return
        if (!speech.isAvailable) {
            _state.update { it.copy(caption = "这台手机未检测到语音识别,请启用「Google 语音服务」") }
            return
        }
        autoOn = true
        listenOnce()
    }

    fun stopAuto() {
        autoOn = false
        speech.destroy()
        _state.update { it.copy(listening = false) }
    }

    private fun listenOnce() {
        if (!autoOn || speaking) return
        _state.update {
            it.copy(
                listening = true,
                caption = "在听…有事就跟我说",
                mascot = if (it.mascot == MascotState.Alarm) it.mascot else MascotState.Listening
            )
        }
        speech.listen(
            onText = { t ->
                _state.update { it.copy(listening = false) }
                if (t.isNotBlank()) process(t) else restartSoon()
            },
            onError = { restartSoon() }
        )
    }

    private fun restartSoon() {
        _state.update { it.copy(listening = false) }
        if (!autoOn) return
        viewModelScope.launch { delay(400); if (autoOn && !speaking) listenOnce() }
    }

    // ---------- 处理:本地快通道优先(极致反应),其余走云端 ----------
    private fun process(text: String) {
        _state.update { it.copy(busy = true, mascot = MascotState.Thinking, caption = "好的…", lastUser = text) }

        // 安全 + 高频指令本地即时处理,不等网络
        val local = LocalSafetyNet.handle(text) ?: LocalIntents.parse(text)
        if (local != null) { applyReply(local); return }

        viewModelScope.launch {
            val reply = try {
                BrainClient.ask(app, text)
            } catch (e: Exception) {
                Reply("我先陪您聊两句。您可以说『打电话给女儿』『导航到医院』。", null, "chat", 0.0)
            }
            applyReply(reply)
        }
    }

    private fun applyReply(reply: Reply) {
        val type = reply.action?.optString("type")
        val hint = reply.action?.let { ActionDispatcher.execute(app, it) }
        val toSay = hint ?: reply.speech
        if (type == "FRAUD_WARN") FraudStore.inc(app)
        speaking = true
        _state.update {
            it.copy(
                busy = false,
                speaking = true,
                caption = toSay,
                mascot = stateFor(type, reply),
                fraudBlocked = FraudStore.count(app),
                sosLabel = if (type == "SOS") "刚刚" else it.sosLabel
            )
        }
        curUtt = tts.speak(toSay)
        if (type == "FRAUD_WARN" || type == "SOS") {
            scheduleAlarmReset()
            val pushType = if (type == "SOS") "sos" else "fraud_sms"
            viewModelScope.launch { PushClient.emit(app, pushType, reply.speech, System.currentTimeMillis()) }
        }
    }

    private fun stateFor(type: String?, reply: Reply): MascotState = when {
        type == "FRAUD_WARN" || reply.risk >= 0.55 || reply.skill.contains("防诈") -> MascotState.Alarm
        type == "SOS" || reply.skill.contains("呼救") -> MascotState.Alarm
        reply.skill == "chat" || reply.skill.contains("陪伴") -> MascotState.Caring
        else -> MascotState.Talking
    }

    private fun onSpeakDone(id: String?) {
        if (id != curUtt) return   // 被 flush 的旧句完成回调,忽略,避免边说边听/提前复位
        speaking = false
        _state.update {
            val m = if (it.mascot == MascotState.Alarm) MascotState.Alarm else MascotState.Idle
            if (it.listening) it.copy(speaking = false) else it.copy(speaking = false, mascot = m)
        }
        if (autoOn) restartSoon()
    }

    // ---------- 设置 / 子女端 ----------
    fun showScreen(s: Screen) {
        _state.update { it.copy(screen = s, fraudBlocked = FraudStore.count(app)) }
    }

    fun setBrainUrl(url: String) {
        Settings.setBrainUrl(app, url)
        _state.update { it.copy(brainUrl = Settings.brainUrl(app)) }
    }

    fun setLive2d(on: Boolean) {
        Settings.setLive2d(app, on)
        _state.update { it.copy(live2d = on) }
    }

    /** 开通会员:下单→(占位)收银台→回调→本地记录。plan: "basic"/"premium",method: 微信/支付宝 */
    fun buyPlan(plan: String, method: String) {
        _state.update { it.copy(caption = "正在通过$method 开通…") }
        viewModelScope.launch {
            val ok = PayClient.pay(app, plan, method)
            if (ok) {
                Membership.set(app, plan)
                val label = Membership.label(plan)
                _state.update { it.copy(membership = plan, caption = "$label 开通成功,感谢支持!") }
                curUtt = tts.speak("$label 开通成功,感谢您的支持。")
            } else {
                _state.update { it.copy(caption = "支付未完成,可稍后再试。") }
            }
        }
    }

    fun syncFamily() {
        viewModelScope.launch {
            val msg = try {
                val ctx = JSONObject().put("scene", "family_sync")
                BrainClient.ask(app, "把我最近的情况同步给家人", ctx).speech
            } catch (e: Exception) {
                "已在本地记录,联网后会自动同步给家人。"
            }
            _state.update { it.copy(familySynced = true, caption = msg) }
            curUtt = tts.speak(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speech.destroy()
        tts.shutdown()
    }
}
