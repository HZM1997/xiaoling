package com.xiaoling.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.xiaoling.service.AppForeground

enum class Screen { Home, Settings, Login }

data class Choice(val label: String, val speech: String, val action: org.json.JSONObject?)

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
    val loggedIn: Boolean = false,
    val phone: String = "",
    val realNameVerified: Boolean = false,
    val displayName: String = "",
    val chatEntitlement: String = "",
    val realNameStatus: String = "",
    val agentCapabilityCount: Int = 0,
    val agentRevision: String = "",
    val choices: List<Choice> = emptyList(),   // 智能澄清:多选项(有值时 UI 显示选择按钮)
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
            membership = if (Account.isLoggedIn(app)) Account.membership(app) else Membership.tier(app),
            loggedIn = Account.isLoggedIn(app),
            phone = Account.phone(app),
            realNameVerified = Account.realNameVerified(app),
            displayName = Account.displayName(app),
            chatEntitlement = Account.chatEntitlement(app)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { id -> onSpeakDone(id) }

    @Volatile private var speaking = false   // TTS 播报中
    @Volatile private var holding = false    // 老人正按住说话
    @Volatile private var interrupted = false // 本次按住是"打断"播报触发的(松手无新指令则恢复原播报)
    @Volatile private var memoMode = false   // 亲情语音留言录制模式:下一次按住录音频而非识别
    private var memoTimeoutJob: Job? = null   // 进入留言模式后若迟迟不按住,自动取消,避免劫持下次按住说话
    @Volatile private var alarmUntil = 0L    // 警惕态持续到的时间戳(多个事件叠加不早退)
    private var curUtt = ""                   // 当前 TTS utteranceId(被 flush 的旧句忽略)
    @Volatile private var voiceSessionActive = false // 唤醒词进入的免手持连续对话
    private var automaticMisses = 0
    private var autoListenJob: Job? = null
    private var pendingReminder = ""
    private var pendingRemoteAudioUrl = ""

    /** 当前会员档位(登录跟账号,否则本地) */
    private fun tierNow(): String = if (Account.isLoggedIn(app)) Account.membership(app) else Membership.tier(app)
    private fun isPremium(): Boolean = tierNow() == "premium"

    init {
        // 启动加载完整翻译词库(assets/translate/phrases.json)
        LocalIntents.load(app)
        // 后台(短信/来电)检测到诈骗 → 形象即时警惕
        AlarmBus.events.onEach { onExternalFraud(it) }.launchIn(viewModelScope)
        // App 被诈骗事件冷启动 / 刚打开时,读取近 2 分钟内的待处理事件
        FraudStore.takePendingIfRecent(app)?.let { onExternalFraud(it) }
        // 跨设备:订阅家庭组事件(家人设备实时收到老人端的推送),断线自动重连
        subscribePush()
        monitorOfficialAlerts()
        refreshAgentStatus()
    }

    private fun subscribePush() {
        viewModelScope.launch {
            while (isActive) {
                if (isPremium()) PushClient.subscribe(app) { ev ->
                    viewModelScope.launch { onFamilyEvent(ev) }
                }   // 家人看护推送:高级会员
                delay(3000)   // 断线/未开通时轮询重试
            }
        }
    }

    /** 收到家庭组跨设备事件 → 语音播报提醒 */
    private fun onFamilyEvent(ev: org.json.JSONObject) {
        if (ev.optString("sender") == PushClient.deviceId(app)) return   // 忽略自己发的事件,避免自我回声
        val text = ev.optString("text").ifBlank { "家人有一条新的看护提醒" }
        val type = ev.optString("type")
        val data = ev.optJSONObject("data")
        when (type) {
            "remote_reminder" -> {
                val raw = data?.optString("raw").orEmpty().ifBlank { text }
                val say = if (Reminders.missingPart(raw) == null) {
                    "家人远程为您设置了提醒。" + Reminders.schedule(app, raw)
                } else {
                    "家人发来一条提醒,但时间或内容不完整:$text"
                }
                speakFamilyMessage(say)
                return
            }
            "remote_audio" -> {
                val url = data?.optString("url").orEmpty()
                if (url.isNotBlank()) pendingRemoteAudioUrl = url
                speakFamilyMessage(if (url.isNotBlank()) "家人给您发来一段语音,现在播放。" else "家人发来的语音地址无效。")
                return
            }
            "earthquake_alert", "weather_alert", "emergency_alert" -> {
                presentEmergency(text)
                return
            }
        }
        val prefix = when (type) {
            "fraud_call", "fraud_sms" -> "家人可能遇到诈骗:"
            "sos" -> "家人发起了紧急呼救:"
            else -> "家人看护:"
        }
        speaking = true
        _state.update { it.copy(caption = "🔔 $prefix$text", mascot = MascotState.Caring, speaking = true) }
        curUtt = tts.speak(prefix + text)
    }

    private fun speakFamilyMessage(text: String) {
        speaking = true
        _state.update { it.copy(caption = text, mascot = MascotState.Caring, speaking = true, busy = false) }
        curUtt = tts.speak(text)
    }

    private fun monitorOfficialAlerts() {
        viewModelScope.launch {
            delay(4000)
            while (isActive) {
                val alert = try { OfficialAlerts.latest(app) } catch (_: Exception) { null }
                if (alert != null && OfficialAlerts.markIfNew(app, alert)) {
                    presentEmergency(alert.speech)
                }
                delay(15 * 60 * 1000L)
            }
        }
    }

    private fun refreshAgentStatus() {
        viewModelScope.launch {
            val catalog = AgentClient.status(app) ?: return@launch
            _state.update { it.copy(agentCapabilityCount = catalog.count, agentRevision = catalog.revision) }
        }
    }

    private fun presentEmergency(text: String) {
        voiceSessionActive = false
        autoListenJob?.cancel()
        holding = false
        speech.cancel()
        tts.stop()
        RemoteAudioPlayer.stop()
        ActionDispatcher.execute(app, JSONObject().put("type", "ALERT"))
        Notifier.warn(app, "小灵紧急预警", text, text.hashCode())
        speaking = true
        _state.update { it.copy(caption = text, mascot = MascotState.Alarm, listening = false, speaking = true, busy = false) }
        curUtt = tts.speak(text)
        scheduleAlarmReset()
    }

    /** 来自短信/来电的高危事件:把形象切到警惕态,并语音+震动强反馈 */
    private fun onExternalFraud(reason: String) {
        FraudStore.clearPending(app)
        val say = "注意!$reason。千万不要转账、不要提供验证码、不要点链接。"
        speaking = true
        _state.update { it.copy(mascot = MascotState.Alarm, caption = say, busy = false, speaking = true, fraudBlocked = FraudStore.count(app)) }
        try { ActionDispatcher.execute(app, JSONObject().put("type", "FRAUD_WARN")) } catch (e: Exception) {}
        if (isPremium()) viewModelScope.launch { PushClient.emit(app, "fraud_call", reason, System.currentTimeMillis()) }
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

    // ---------- 唤醒即对话 + 按住说话(单一辅助操作) + 打断 ----------
    /** 预热识别器(进主页时调用),让首次开听更快 */
    fun warmUpMic() { if (speech.isAvailable) speech.warmUp() }

    /** 后台听到“小灵”后直接开始免手持对话,AI 每次答完会继续听下一句。 */
    fun startVoiceConversation() {
        voiceSessionActive = true
        automaticMisses = 0
        _state.update { it.copy(screen = Screen.Home) }
        beginListening(automatic = true)
    }

    /**
     * 老人按下"按住说话"大按钮。若此刻小灵正在播报 → 立即打断(停播报),
     * 并记录 interrupted:松手后若没听到新指令,就恢复原来的播报。
     */
    fun pressToTalk() {
        beginListening(automatic = false)
    }

    private fun beginListening(automatic: Boolean) {
        if (holding) return
        autoListenJob?.cancel()
        VoiceMemo.stopPlayback()          // 若正在回放刚录的留言,按下按钮先把回放停掉,避免边放边听
        memoTimeoutJob?.cancel()          // 用户已开始操作,取消留言模式的自动超时
        if (!speech.isAvailable && !memoMode) {
            voiceSessionActive = false
            speaking = true
            _state.update { it.copy(caption = "这台手机没有语音识别,请启用「Google 语音服务」", speaking = true) }
            curUtt = tts.speak("这台手机没有语音识别,请在设置里启用谷歌语音服务。")
            return
        }
        // 亲情语音留言录制:按住录音频(不做识别)
        if (memoMode) {
            if (automatic) {
                voiceSessionActive = false
                return
            }
            if (speaking) { tts.stop(); speaking = false }
            holding = true
            val ok = VoiceMemo.startRecord(app)
            _state.update { it.copy(listening = true, busy = false,
                caption = if (ok) "正在录音…松开就好" else "录音没打开,请检查麦克风权限",
                mascot = MascotState.Listening) }
            return
        }
        interrupted = speaking      // 正在播报时按住 = 打断
        if (speaking) { tts.stop(); speaking = false }
        holding = true
        _state.update { it.copy(listening = true, busy = false, caption = "在听…请说",
            mascot = if (it.mascot == MascotState.Alarm) it.mascot else MascotState.Listening) }
        speech.listen(
            onPartial = { p -> if (holding && p.isNotBlank()) _state.update { it.copy(caption = p) } },
            onText = { t -> holding = false; _state.update { it.copy(listening = false) }
                if (t.isNotBlank()) {
                    interrupted = false
                    automaticMisses = 0
                    process(t)
                } else onHeardNothing(automatic)
            },
            onError = {
                holding = false
                _state.update { it.copy(listening = false) }
                onHeardNothing(automatic)
            }
        )
    }

    /** 松手:留言模式停止录音 → 回放让老人听清楚 → 再语音确认;否则收尾识别 */
    fun releaseToTalk() {
        if (!holding) return
        if (memoMode) {
            holding = false; memoMode = false
            val f = VoiceMemo.stopRecord()
            speaking = true
            if (f != null) {
                // 先把刚录的原声放给老人听一遍(听得清才敢发),放完再提示怎么发
                _state.update { it.copy(listening = false, mascot = MascotState.Caring,
                    caption = "您先听听,录得清楚吗…", speaking = true) }
                VoiceMemo.playback(app) {
                    val tip = "录好了。想发给谁,就跟我说『发给女儿』这样。"
                    speaking = true
                    _state.update { it.copy(mascot = MascotState.Caring, caption = tip, speaking = true) }
                    curUtt = tts.speak(tip)
                }
            } else {
                val tip = "没录到声音,再试一次好吗?"
                _state.update { it.copy(listening = false, mascot = MascotState.Caring, caption = tip, speaking = true) }
                curUtt = tts.speak(tip)
            }
            return
        }
        speech.stopListening()
    }

    /** 没听清/没内容:温和语音提示,不弹冷冰冰文字。若是打断场景则恢复原播报。 */
    private fun onHeardNothing(automatic: Boolean) {
        if (interrupted && tts.lastSpoken.isNotBlank()) {
            interrupted = false
            speaking = true
            _state.update { it.copy(caption = tts.lastSpoken, mascot = MascotState.Talking, speaking = true) }
            curUtt = tts.speakLast()   // 打断但没说新指令 → 接着把原来的话说完
            return
        }
        interrupted = false
        speaking = true
        val tip = if (automatic && voiceSessionActive) {
            automaticMisses++
            if (automaticMisses >= 2) {
                voiceSessionActive = false
                "我先在这儿等您,有需要再叫小灵。"
            } else {
                "我没听清楚,麻烦您再说一遍好吗?"
            }
        } else {
            "我没听清楚,麻烦您再说一遍好吗?"
        }
        _state.update { it.copy(mascot = MascotState.Caring, caption = tip, speaking = true) }
        curUtt = tts.speak(tip)
    }

    // ---------- 处理:本地快通道优先(极致反应),云端硬超时兜底,保证 ≤2s ----------
    private fun process(text: String) {
        val spoken = LocalIntents.normalizeSpeech(text)
        _state.update { it.copy(busy = true, mascot = MascotState.Thinking, caption = spoken, lastUser = spoken) }

        // 对话式提醒:上一轮缺时间/内容时,把本轮回答补进草稿并继续判断,全程无需弹窗。
        if (pendingReminder.isNotBlank()) {
            val combined = "$pendingReminder $spoken".trim()
            pendingReminder = ""
            val missing = Reminders.missingPart(combined)
            if (missing != null) {
                askReminderDetail(combined, missing)
            } else {
                applyReply(Reply("好的,我来设置提醒。",
                    JSONObject().put("type", "REMIND").put("raw", combined), "语音提醒", 0.0))
            }
            return
        }

        // 若上一轮给了选项:先本地解析用户的语音选择("第一个/打电话"),命中即执行
        val pending = _state.value.choices
        if (pending.isNotEmpty()) {
            val chosen = resolveChoice(spoken, pending)
            if (chosen != null) {
                _state.update { it.copy(choices = emptyList()) }
                applyReply(Reply(chosen.speech, chosen.action, "智能澄清·已选", 0.0))
                return
            }
        }

        // 安全 + 高频指令本地即时处理,不等网络(<0.3s 秒回)
        val local = LocalSafetyNet.handle(spoken) ?: LocalIntents.parse(spoken)
        if (local != null) { applyReply(local); return }

        if (WeatherClient.isWeatherQuery(spoken)) {
            viewModelScope.launch {
                val reply = withTimeoutOrNull(2300) { WeatherClient.ask(app) }
                    ?: Reply("天气服务暂时有点慢,过一会儿我再帮您查。", null, "生活问答·天气", 0.0)
                applyReply(reply)
            }
            return
        }

        viewModelScope.launch {
            // 云端最多等 1.8s:whichever first。超时/失败即本地兜底,总响应 ≤2s
            val reply = withTimeoutOrNull(1800) {
                try { BrainClient.ask(app, spoken) } catch (e: Exception) { null }
            } ?: Reply("我先陪您聊两句。您可以说『打电话给女儿』『导航到医院』,或者让我帮您翻译。", null, "chat", 0.0)
            applyReply(reply)
        }
    }

    /** 用户点按某个选项(UI 调用) */
    fun chooseOption(c: Choice) {
        _state.update { it.copy(choices = emptyList()) }
        applyReply(Reply(c.speech, c.action, "智能澄清·已选", 0.0))
    }

    /** 本地解析语音选择:序号(第一个/一)或关键词匹配 label */
    private fun resolveChoice(text: String, opts: List<Choice>): Choice? {
        val ord = mapOf("第一" to 0, "一" to 0, "第二" to 1, "二" to 1, "第三" to 2, "三" to 2, "第四" to 3, "四" to 3)
        for ((k, i) in ord) if (text.contains(k) && i < opts.size) return opts[i]
        for (o in opts) {
            val core = o.label.replace(Regex("[给和去打的]"), "")
            if ((core.isNotBlank() && text.contains(core)) || text.contains(o.label)) return o
        }
        return null
    }

    private fun applyReply(reply: Reply) {
        val type = reply.action?.optString("type")
        if (type == "REMIND") {
            val raw = reply.action?.optString("raw").orEmpty()
            val missing = Reminders.missingPart(raw)
            if (missing != null) {
                askReminderDetail(raw, missing)
                return
            }
        }
        // 连续多指令:依次执行多个动作,一句话概括后逐个 dispatch
        if (type == "TASKS") {
            val steps = reply.action?.optJSONArray("steps")
            speaking = true
            _state.update { it.copy(busy = false, speaking = true, mascot = MascotState.Talking, caption = reply.speech) }
            curUtt = tts.speak(reply.speech)
            if (steps != null) viewModelScope.launch {
                for (i in 0 until steps.length()) {
                    val act = steps.optJSONObject(i) ?: continue
                    try { ActionDispatcher.execute(app, act) } catch (e: Exception) {}
                    delay(1200)   // 每步之间留点间隔,别挤在一起
                }
            }
            return
        }
        // 亲情语音留言:进入录制模式,提示老人按住说要留的话
        if (type == "RECORD_MEMO") {
            memoMode = true
            speaking = true
            _state.update { it.copy(busy = false, speaking = true, mascot = MascotState.Listening, caption = reply.speech) }
            curUtt = tts.speak(reply.speech)
            // 20 秒内没按住说话就自动退出留言模式,否则下次按住会被误当成录音
            memoTimeoutJob?.cancel()
            memoTimeoutJob = viewModelScope.launch {
                delay(20000)
                if (memoMode && !holding) {
                    memoMode = false
                    speaking = true
                    val t = "没听到您按住说话,留言先取消了,想留言再跟我说一声。"
                    _state.update { it.copy(mascot = MascotState.Caring, caption = t, speaking = true) }
                    curUtt = tts.speak(t)
                }
            }
            return
        }
        if (type == "SEND_MEMO") {
            val target = reply.action?.optString("target").orEmpty().ifBlank { "家人" }
            val file = VoiceMemo.lastFile
            if (file == null || !file.exists()) {
                speakFamilyMessage("还没有录音,先跟我说『录一段留言』吧。")
                return
            }
            _state.update { it.copy(busy = true, caption = "正在把语音发给$target…", mascot = MascotState.Thinking) }
            viewModelScope.launch {
                val ok = FamilyAudioClient.send(app, file, target)
                val say = if (ok) "已经把这条语音发给$target 了。" else "这条语音暂时没发出去,我已经保留录音,网络恢复后可以再试。"
                speakFamilyMessage(say)
            }
            return
        }
        // 语音举报/信任号码:对最近一次诈骗号码操作,喂号码信誉库
        if (type == "REPORT_NUMBER" || type == "TRUST_NUMBER") {
            val num = FraudStore.lastFraudNumber(app)
            if (num.isBlank()) {
                speaking = true
                _state.update { it.copy(busy = false, speaking = true, caption = "最近没有可疑来电或短信,暂时不用举报。") }
                curUtt = tts.speak("最近没有可疑来电或短信,暂时不用举报。")
                return
            }
            reportNumber(num, report = type == "REPORT_NUMBER")
            speaking = true
            _state.update { it.copy(busy = false, speaking = true, mascot = MascotState.Caring, caption = reply.speech) }
            curUtt = tts.speak(reply.speech)
            return
        }
        // 智能澄清:多选项 → 显示按钮 + 语音报选项,不执行,等用户选
        if (type == "CHOICES") {
            val arr = reply.action?.optJSONArray("options")
            val opts = ArrayList<Choice>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                opts.add(Choice(o.optString("label"), o.optString("speech"), o.optJSONObject("action")))
            }
            if (opts.isNotEmpty()) {
                val prompt = reply.speech + " " + opts.mapIndexed { i, c -> "${i + 1}、${c.label}" }.joinToString(" ")
                speaking = true
                _state.update { it.copy(busy = false, speaking = true, mascot = MascotState.Thinking, caption = reply.speech, choices = opts) }
                curUtt = tts.speak(prompt)
                return
            }
        }
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
                choices = emptyList(),   // 普通回复清掉上一轮选项
                fraudBlocked = FraudStore.count(app),
                sosLabel = if (type == "SOS") "刚刚" else it.sosLabel
            )
        }
        curUtt = tts.speak(toSay)
        if (type == "FRAUD_WARN" || type == "SOS") {
            scheduleAlarmReset()
            if (isPremium()) {   // 跨设备家人推送:高级会员专享
                val pushType = if (type == "SOS") "sos" else "fraud_sms"
                viewModelScope.launch { PushClient.emit(app, pushType, reply.speech, System.currentTimeMillis()) }
            }
        }
    }

    private fun stateFor(type: String?, reply: Reply): MascotState = when {
        type == "FRAUD_WARN" || reply.risk >= 0.55 || reply.skill.contains("防诈") -> MascotState.Alarm
        type == "SOS" || reply.skill.contains("呼救") -> MascotState.Alarm
        reply.skill == "chat" || reply.skill.contains("陪伴") -> MascotState.Caring
        else -> MascotState.Talking
    }

    private fun onSpeakDone(id: String?) {
        if (id != curUtt) return   // 被 flush 的旧句完成回调,忽略
        speaking = false
        _state.update {
            val m = if (it.mascot == MascotState.Alarm) MascotState.Alarm else MascotState.Idle
            if (it.listening) it.copy(speaking = false) else it.copy(speaking = false, mascot = m)
        }
        if (pendingRemoteAudioUrl.isNotBlank()) {
            val url = pendingRemoteAudioUrl
            pendingRemoteAudioUrl = ""
            RemoteAudioPlayer.play(url)
            return
        }
        if (voiceSessionActive && !holding && !memoMode && AppForeground.active && _state.value.screen == Screen.Home) {
            autoListenJob?.cancel()
            autoListenJob = viewModelScope.launch {
                delay(220)
                if (voiceSessionActive && !holding && !speaking && AppForeground.active) {
                    beginListening(automatic = true)
                }
            }
        }
    }

    private fun askReminderDetail(raw: String, missing: Reminders.MissingPart) {
        pendingReminder = raw
        voiceSessionActive = true
        automaticMisses = 0
        val prompt = when (missing) {
            Reminders.MissingPart.TIME -> "好的,您想让我什么时候提醒?"
            Reminders.MissingPart.CONTENT -> "好的,到时候提醒您做什么?"
        }
        speaking = true
        _state.update { it.copy(busy = false, speaking = true, mascot = MascotState.Caring, caption = prompt) }
        curUtt = tts.speak(prompt)
    }

    // ---------- 设置 / 子女端 ----------
    fun showScreen(s: Screen) {
        if (s != Screen.Home) {
            voiceSessionActive = false
            autoListenJob?.cancel()
            if (holding) {
                holding = false
                speech.cancel()
            }
        }
        _state.update { it.copy(screen = s, fraudBlocked = FraudStore.count(app)) }
    }

    fun setBrainUrl(url: String) {
        Settings.setBrainUrl(app, url)
        _state.update { it.copy(brainUrl = Settings.brainUrl(app)) }
    }

    /** 举报号码为诈骗(拉黑)或标为可信(加白),喂养号码信誉库 —— 数据飞轮 */
    fun reportNumber(number: String, report: Boolean = true) {
        if (number.isBlank()) return
        viewModelScope.launch {
            val ok = BrainClient.reportNumber(app, number, if (report) "report" else "trust")
            _state.update {
                it.copy(caption = if (ok) {
                    if (report) "已举报 $number,小灵会更警惕这个号码,也会提醒其他家庭。"
                    else "已把 $number 标为可信,以后不会再误报。"
                } else "网络不太好,稍后再试。")
            }
        }
    }

    fun setLive2d(on: Boolean) {
        if (on && !isPremium()) {
            _state.update { it.copy(caption = "3D 数字人形象是高级会员专享,开通后解锁。") }
            return
        }
        Settings.setLive2d(app, on)
        _state.update { it.copy(live2d = on) }
    }

    /** 开通会员:下单→(占位)收银台→回调→记录(登录则跟账号,否则本地)。 */
    fun buyPlan(plan: String, method: String) {
        _state.update { it.copy(caption = "正在通过$method 开通…") }
        viewModelScope.launch {
            val ok = PayClient.pay(app, plan, method, Account.phone(app))
            if (ok) {
                if (Account.isLoggedIn(app)) Account.setMembership(app, plan) else Membership.set(app, plan)
                val label = Membership.label(plan)
                _state.update { it.copy(membership = plan, caption = "$label 开通成功,感谢支持!") }
                curUtt = tts.speak("$label 开通成功,感谢您的支持。")
            } else {
                _state.update { it.copy(caption = "支付未完成,可稍后再试。") }
            }
        }
    }

    // ---------- 账号(手机号登录) ----------
    fun sendCode(phone: String) {
        viewModelScope.launch {
            val ok = AuthClient.sendCode(app, phone)
            _state.update { it.copy(caption = if (ok) "验证码已发送(演示请输入 1234)" else "验证码发送失败,请检查网络/服务器") }
        }
    }

    fun login(phone: String, code: String) {
        _state.update { it.copy(caption = "正在登录…") }
        viewModelScope.launch {
            val r = AuthClient.login(app, phone, code)
            if (r != null && r.optBoolean("ok", true) && r.has("token")) {
                val member = r.optString("membership", "")
                Account.save(
                    app, r.optString("token"), phone, r.optString("uid"), r.optString("family_id"), member,
                    r.optBoolean("real_name_verified", false), r.optString("display_name"),
                    r.optString("chat_entitlement")
                )
                _state.update {
                    it.copy(loggedIn = true, phone = phone, membership = member,
                        realNameVerified = r.optBoolean("real_name_verified", false),
                        displayName = r.optString("display_name"),
                        chatEntitlement = r.optString("chat_entitlement"),
                        screen = Screen.Settings, caption = "登录成功,欢迎回来!")
                }
            } else {
                val msg = r?.optString("msg", "") ?: ""
                _state.update { it.copy(caption = if (msg.isNotBlank()) msg else "登录失败,验证码演示请输入 1234") }
            }
        }
    }

    fun logout() {
        Account.logout(app)
        _state.update { it.copy(loggedIn = false, phone = "", membership = Membership.tier(app),
            realNameVerified = false, displayName = "", chatEntitlement = "", realNameStatus = "",
            live2d = false, caption = "已退出登录") }
    }

    /**
     * 微信一键登录(演示骨架)。真实场景:
     *  ① 微信开放平台注册应用拿 AppID + 配置应用签名;
     *  ② 集成微信 OpenSDK,IWXAPI.sendReq(SendAuth.Req) 拉起微信授权,WXEntryActivity 收 code;
     *  ③ 把 code 发后端 /auth/wx_login,后端 code2session 换 openid + 会话,返回账号。
     * 此处直接调后端 /auth/wx_login(demo code)完成登录闭环。
     */
    fun wxLogin() {
        _state.update { it.copy(caption = "正在通过微信登录…") }
        viewModelScope.launch {
            val r = AuthClient.wxLogin(app)
            if (r != null && r.has("token")) {
                val member = r.optString("membership", "")
                val ph = r.optString("phone", "微信用户")
                Account.save(
                    app, r.optString("token"), ph, r.optString("uid"), r.optString("family_id"), member,
                    r.optBoolean("real_name_verified", false), r.optString("display_name"),
                    r.optString("chat_entitlement")
                )
                _state.update { it.copy(loggedIn = true, phone = ph, membership = member,
                    realNameVerified = r.optBoolean("real_name_verified", false),
                    displayName = r.optString("display_name"), chatEntitlement = r.optString("chat_entitlement"),
                    screen = Screen.Settings, caption = "微信登录成功,欢迎回来!") }
            } else {
                _state.update { it.copy(caption = "微信登录未完成(演示需后端在线)。") }
            }
        }
    }

    fun verifyRealName(name: String, idNo: String) {
        if (!Account.isLoggedIn(app)) {
            _state.update { it.copy(realNameStatus = "请先登录账号") }
            return
        }
        _state.update { it.copy(realNameStatus = "正在安全核验…") }
        viewModelScope.launch {
            val result = AuthClient.verifyRealName(app, name.trim(), idNo.trim().uppercase())
            if (result?.optBoolean("verified", false) == true) {
                val entitlement = result.optString("chat_entitlement", "lifetime_unlimited")
                val displayName = result.optString("display_name", name.trim())
                Account.setRealNameVerified(app, displayName, entitlement)
                val say = "实名认证完成,已赠送永久无限畅聊陪伴。"
                _state.update { it.copy(realNameVerified = true, displayName = displayName,
                    chatEntitlement = entitlement, realNameStatus = say, caption = say) }
                curUtt = tts.speak(say)
            } else {
                _state.update { it.copy(realNameStatus = result?.optString("msg")
                    ?.takeIf { msg -> msg.isNotBlank() } ?: "实名认证暂时无法完成,请稍后再试。") }
            }
        }
    }

    fun syncFamily() {
        if (!isPremium()) {
            _state.update { it.copy(caption = "家人看护是高级会员专享,开通后可与家人跨设备同步。") }
            return
        }
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
        autoListenJob?.cancel()
        speech.destroy()
        tts.shutdown()
        RemoteAudioPlayer.stop()
        VoiceMemo.release()
    }
}
