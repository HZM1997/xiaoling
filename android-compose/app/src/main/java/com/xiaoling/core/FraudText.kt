package com.xiaoling.core

/**
 * 短信/文本诈骗判定(端侧,镜像 server/fraud_rules.json 的红线 + 高频词)。
 * 返回 (是否高危, 原因)。
 */
object FraudText {
    private val redline = listOf(
        "屏幕共享", "远程控制", "念一下收到的验证码", "验证码", "转到安全账户",
        "把钱转到", "输入银行卡密码", "扫这个码", "点击链接", "登录网址",
        "下载会议软件", "打开录屏", "刷流水", "先垫钱", "百万保障",
        "关闭国家反诈中心", "快递理赔", "客服退款"
    )
    private val words = listOf(
        "公检法", "涉嫌洗钱", "通缉令", "安全账户", "冻结", "医保卡异常", "社保卡",
        "银保监", "征信有问题", "解除分期", "注销校园贷", "退款", "理赔", "中奖",
        "返利", "刷单", "垫付", "保证金", "会员到期自动扣费", "包裹有毒品",
        "冒充客服", "冒充公检法", "安全账户", "高额回报"
    )

    fun assess(text: String): Pair<Boolean, String> {
        val t = text
        val red = redline.firstOrNull { it in t }
        if (red != null) return true to "对方要求「$red」,是诈骗典型手法"
        val hits = words.filter { it in t }
        if (hits.size >= 2) return true to ("提到「" + hits.take(2).joinToString("、") + "」")
        if (hits.size == 1) return true to "提到「${hits[0]}」,请提高警惕"
        return false to ""
    }
}
