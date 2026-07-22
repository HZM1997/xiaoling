package com.xiaoling.core

import org.json.JSONObject

/**
 * 离线安全兜底:断网/服务器不可达时,仍在本地处理「紧急呼救」和「红线诈骗词」。
 * 规则镜像自 server/fraud_rules.json 的 redline 与 skills 的 SOS 触发。
 */
object LocalSafetyNet {
    private val sos = Regex("救命|摔倒|喘不上气|胸口疼|心脏|不行了|急救|晕倒|120")
    private val redline = listOf(
        "屏幕共享", "远程控制", "念一下收到的验证码", "验证码", "转到安全账户",
        "把钱转到", "输入银行卡密码", "扫这个码", "公检法", "涉嫌洗钱",
        "下载会议软件", "打开录屏", "刷流水", "先垫钱", "注销校园贷",
        "百万保障", "关闭国家反诈中心", "快递理赔", "客服退款",
        "上门取现金", "取现金交给骑手", "购买黄金邮寄", "邮寄黄金", "邮寄现金",
        "开启NFC碰一碰", "手机贴近银行卡", "安装远程控制软件"
    )
    private val consultation = Regex("是不是诈骗|会不会是骗子|像不像诈骗|能不能转账|要不要转账|怎么防诈|如何防诈|反诈|有人.{0,12}(让我|叫我|要求我)|对方.{0,12}(让我|叫我|要求我)")
    private val suspicious = listOf(
        "转账", "汇款", "退款", "中奖", "刷单", "投资群", "陌生链接", "下载软件",
        "共享屏幕", "银行卡", "验证码", "安全账户", "现金", "黄金", "客服", "公检法"
    )

    fun handle(text: String): Reply? {
        if (sos.containsMatchIn(text)) {
            val a = JSONObject()
                .put("type", "SOS").put("call", "120")
                .put("notify_family", true).put("send_location", true)
            return Reply("别怕,我马上帮您拨打120,请保持冷静。", a, "紧急呼救", 1.0)
        }
        val hit = redline.firstOrNull { it in text }
        if (hit != null) {
            val a = JSONObject().put("type", "FRAUD_WARN")
                .put("level", "high").put("category", "红线操作")
            return Reply(
                "注意!这非常像诈骗:对方提到「$hit」。千万不要转账、不要提供验证码!",
                a, "防诈骗预警", 0.96
            )
        }
        if (consultation.containsMatchIn(text)) {
            val clues = suspicious.filter { it in text }
            if (clues.isNotEmpty()) {
                val a = JSONObject().put("type", "FRAUD_WARN")
                    .put("level", "medium").put("category", "可疑话术")
                return Reply(
                    "先不要转账、不要给验证码、不要下载对方指定的软件。您提到${clues.take(3).joinToString("、")},风险比较高,先挂断并打官方电话或问家人核实。",
                    a, "防诈咨询", 0.78
                )
            }
            return Reply(
                "拿不准时先做到三件事:不转账、不说验证码、不点陌生链接。把对方原话告诉我,我再帮您判断。",
                null, "防诈咨询", 0.2
            )
        }
        return null
    }
}
