package com.xiaoling.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
    val busy: Boolean = false,
    val screen: Screen = Screen.Home,
    val lastUser: String = "",
    val brainUrl: String = "",
    // 子女端·看护统计
    val fraudBlocked: Int = 0,
    val sosLabel: String = "无",
    val medsOk: Boolean = true,
    val familySynced: Boolean = false
)

/** 编排:常听式语音识别 → 大脑(云端/本地兜底) → TTS → 执行动作 → 形象状态 */
class AppState(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _state = MutableStateFlow(UiState(brainUrl = Settings.brainUrl(app), fraudBlocked = FraudStore.count(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { onSpeakDone() }

    @Volatile private var autoOn = false     // 常听开关(跨主线程/ TTS 回调线程读写)
    @Volatile private var speaking = false   // TTS 播报中(此时不听,避免听到自己)
    @Volatile private var alarmUntil = 0L    // 警惕态持续到的时间戳(多个事件叠加不早退)

    init {
        // 后台(短信/来电)检测到诈骗 → 形象即时警惕
        AlarmBus.events.onEach { onExternalFraud(it) }.launchIn(viewModelScope)
        // App 被诈骗事件冷启动 / 刚打开时,读取近 2 分钟内的待处理事件
        FraudStore.takePendingIfRecent(app)?.let { onExternalFraud(it) }
    }

    /** 来自短信/来电的高危事件:把形象切到警惕态,并语音+震动强反馈 */
    private fun onExternalFraud(reason: String) {
        FraudStore.clearPending(app)
        val say = "注意!$reason。千万不要转账、不要提供验证码、不要点链接。"
        speaking = true
        _state.update { it.copy(mascot = MascotState.Alarm, caption = say, busy = false, fraudBlocked = FraudStore.count(app)) }
        try { ActionDispatcher.execute(app, JSONObject().put("type", "FRAUD_WARN")) } catch (e: Exception) {}
        tts.speak(say)
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
                caption = toSay,
                mascot = stateFor(type, reply),
                fraudBlocked = FraudStore.count(app),
                sosLabel = if (type == "SOS") "刚刚" else it.sosLabel
            )
        }
        tts.speak(toSay)
        if (type == "FRAUD_WARN" || type == "SOS") scheduleAlarmReset()
    }

    private fun stateFor(type: String?, reply: Reply): MascotState = when {
        type == "FRAUD_WARN" || reply.risk >= 0.55 || reply.skill.contains("防诈") -> MascotState.Alarm
        type == "SOS" || reply.skill.contains("呼救") -> MascotState.Alarm
        reply.skill == "chat" || reply.skill.contains("陪伴") -> MascotState.Caring
        else -> MascotState.Talking
    }

    private fun onSpeakDone() {
        speaking = false
        _state.update {
            if (it.listening) it
            else it.copy(mascot = if (it.mascot == MascotState.Alarm) MascotState.Alarm else MascotState.Idle)
        }
        if (autoOn) restartSoon()
    }

    // ---------- 设置 / 子女端 ----------
    fun showScreen(s: Screen) {
        _state.update { it.copy(screen = s) }
    }

    fun setBrainUrl(url: String) {
        Settings.setBrainUrl(app, url)
        _state.update { it.copy(brainUrl = Settings.brainUrl(app)) }
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
            tts.speak(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speech.destroy()
        tts.shutdown()
    }
}
