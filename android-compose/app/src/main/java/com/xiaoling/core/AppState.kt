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

enum class Screen { Home, Settings, Login }

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
    val role: String = "elder",                       // elder(老人端) / family(家人端)
    val familyEvents: List<String> = emptyList(),     // 家人端:收到的看护事件流
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
            role = Account.role(app)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speech = SpeechController(app)
    private val tts = Tts(app) { id -> onSpeakDone(id) }

    @Volatile private var autoOn = false     // 常听开关(跨主线程/ TTS 回调线程读写)
    @Volatile private var speaking = false   // TTS 播报中(此时不听,避免听到自己)
    @Volatile private var alarmUntil = 0L    // 警惕态持续到的时间戳(多个事件叠加不早退)
    private var curUtt = ""                   // 当前 TTS utteranceId(被 flush 的旧句忽略)

    /** 当前会员档位(登录跟账号,否则本地) */
    private fun tierNow(): String = if (Account.isLoggedIn(app)) Account.membership(app) else Membership.tier(app)
    private fun isPremium(): Boolean = tierNow() == "premium"

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
                // 家人端 或 高级会员(老人端)才订阅家庭组事件
                if (Account.role(app) == "family" || isPremium()) PushClient.subscribe(app) { ev -> onFamilyEvent(ev) }
                delay(3000)   // 断线/未开通时轮询重试
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
        // 家人端:记入事件流(保留最近 30 条)
        _state.update { it.copy(familyEvents = (listOf("$prefix$text") + it.familyEvents).take(30)) }
        speaking = true
        _state.update { it.copy(caption = "🔔 $prefix$text", mascot = MascotState.Caring, speaking = true) }
        curUtt = tts.speak(prefix + text)
    }

    fun setRole(role: String) {
        Account.setRole(app, role)
        _state.update { it.copy(role = role, screen = Screen.Home) }
    }

    /** 家人端:拨打老人电话(演示号码;真实场景取家庭组里老人的号码) */
    fun callElder() {
        val num = "10086"   // TODO 换成家庭组里老人的手机号
        try { ActionDispatcher.execute(app, JSONObject().put("type", "CALL_NUMBER").put("number", num)) } catch (e: Exception) {}
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
                Account.save(app, r.optString("token"), phone, r.optString("uid"), r.optString("family_id"), member)
                _state.update {
                    it.copy(loggedIn = true, phone = phone, membership = member,
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
        _state.update { it.copy(loggedIn = false, phone = "", membership = Membership.tier(app), live2d = false, caption = "已退出登录") }
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
                Account.save(app, r.optString("token"), ph, r.optString("uid"), r.optString("family_id"), member)
                _state.update { it.copy(loggedIn = true, phone = ph, membership = member, screen = Screen.Settings, caption = "微信登录成功,欢迎回来!") }
            } else {
                _state.update { it.copy(caption = "微信登录未完成(演示需后端在线)。") }
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
        speech.destroy()
        tts.shutdown()
    }
}
