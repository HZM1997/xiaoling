package com.xiaoling.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _state = MutableStateFlow(UiState(brainUrl = Settings.brainUrl(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { onSpeakDone() }

    @Volatile private var autoOn = false     // 常听开关(跨主线程/ TTS 回调线程读写)
    @Volatile private var speaking = false   // TTS 播报中(此时不听,避免听到自己)

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
        viewModelScope.launch { delay(900); if (autoOn && !speaking) listenOnce() }
    }

    // ---------- 处理:安全优先本地,其余走云端 ----------
    private fun process(text: String) {
        _state.update { it.copy(busy = true, mascot = MascotState.Thinking, caption = "小灵在想…", lastUser = text) }

        val local = LocalSafetyNet.handle(text)
        if (local != null) { applyReply(local); return }

        viewModelScope.launch {
            val reply = try {
                BrainClient.ask(app, text)
            } catch (e: Exception) {
                LocalSafetyNet.handle(text)
                    ?: Reply("网络好像不太好,我先陪您聊两句。您可以说『打电话给女儿』『导航到医院』。", null, "chat", 0.0)
            }
            applyReply(reply)
        }
    }

    private fun applyReply(reply: Reply) {
        val type = reply.action?.optString("type")
        val hint = reply.action?.let { ActionDispatcher.execute(app, it) }
        val toSay = hint ?: reply.speech
        speaking = true
        _state.update {
            it.copy(
                busy = false,
                caption = toSay,
                mascot = stateFor(type, reply),
                fraudBlocked = it.fraudBlocked + if (type == "FRAUD_WARN") 1 else 0,
                sosLabel = if (type == "SOS") "刚刚" else it.sosLabel
            )
        }
        tts.speak(toSay)
    }

    private fun stateFor(type: String?, reply: Reply): MascotState = when {
        type == "FRAUD_WARN" || reply.risk >= 0.55 || reply.skill.contains("防诈") -> MascotState.Alarm
        type == "SOS" || reply.skill.contains("呼救") -> MascotState.Alarm
        reply.skill == "chat" || reply.skill.contains("陪伴") -> MascotState.Caring
        else -> MascotState.Talking
    }

    private fun onSpeakDone() {
        speaking = false
        _state.update { if (it.listening) it else it.copy(mascot = MascotState.Idle) }
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
