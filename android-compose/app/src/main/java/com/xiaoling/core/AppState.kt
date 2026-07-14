package com.xiaoling.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Screen { Home, Guardian }

data class UiState(
    val caption: String = "点下面的大按钮,对我说话",
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
    val familySynced: Boolean = false,
)

/** 编排:语音识别 → 大脑(云端/本地兜底) → TTS → 执行动作 → 形象表情 */
class AppState(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _state = MutableStateFlow(UiState(brainUrl = Settings.brainUrl(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { onSpeakDone() }

    val recognitionAvailable: Boolean get() = speech.isAvailable

    // ---------- 语音输入 ----------
    fun startListening() {
        _state.update { it.copy(listening = true, mascot = MascotState.Listening, caption = "我在听…") }
        speech.listen(
            onText = { text -> onHeard(text) },
            onError = { onSttError() }
        )
    }

    private fun onHeard(text: String) {
        _state.update { it.copy(listening = false) }
        if (text.isBlank()) {
            _state.update { it.copy(mascot = MascotState.Idle, caption = "没太听清,您再说一遍好吗?") }
            return
        }
        process(text)
    }

    private fun onSttError() {
        _state.update { it.copy(listening = false, mascot = MascotState.Idle, caption = "麦克风没准备好,请再点一次说话。") }
    }

    // ---------- 处理:安全优先本地,其余走云端 ----------
    private fun process(text: String) {
        _state.update { it.copy(busy = true, mascot = MascotState.Thinking, caption = "小灵在想…", lastUser = text) }

        // 安全优先:呼救 / 红线诈骗词 → 本地即时处理,不等网络
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
        val mascot = stateFor(type, reply)

        _state.update {
            it.copy(
                busy = false,
                caption = toSay,
                mascot = mascot,
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
        _state.update { if (it.listening || it.busy) it else it.copy(mascot = MascotState.Idle) }
    }

    // ---------- 子女端 ----------
    fun showScreen(s: Screen) { _state.update { it.copy(screen = s) } }

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
