package com.xiaoling.core

/**
 * 短信/文本诈骗判定(端侧,镜像 server/fraud_rules.json 的红线 + 高频词)。
 * 返回 (是否高危, 原因)。
 */
object FraudText {
    private val url = Regex("(?:https?://|www\\.)[^\\s，。！？]+", RegexOption.IGNORE_CASE)
    private val rawIp = Regex("https?://(?:\\d{1,3}\\.){3}\\d{1,3}(?:[:/]|$)", RegexOption.IGNORE_CASE)
    private val redline = listOf(
        "屏幕共享", "远程控制", "念一下收到的验证码", "验证码", "转到安全账户",
        "把钱转到", "输入银行卡密码", "扫这个码", "点击链接", "登录网址",
        "下载会议软件", "打开录屏", "刷流水", "先垫钱", "百万保障",
        "关闭国家反诈中心", "快递理赔", "客服退款", "上门取现金", "取现金交给骑手",
        "购买黄金邮寄", "邮寄黄金", "邮寄现金", "开启NFC碰一碰", "手机贴近银行卡",
        "安装远程控制软件", "购买礼品卡并发送卡号", "助记词", "钱包私钥",
        "share your screen", "remote access", "send the otp", "read me the verification code",
        "buy gift cards", "seed phrase", "private key", "transfer to a safe account"
    )
    private val words = listOf(
        "公检法", "涉嫌洗钱", "通缉令", "安全账户", "冻结", "医保卡异常", "社保卡",
        "银保监", "征信有问题", "解除分期", "注销校园贷", "退款", "理赔", "中奖",
        "返利", "刷单", "垫付", "保证金", "会员到期自动扣费", "包裹有毒品",
        "冒充客服", "冒充公检法", "安全账户", "高额回报", "AI换脸", "合成声音",
        "换脸视频", "网约车送现金", "礼品卡", "加密货币付款", "技术支持", "远程修复",
        "SIM卡失效", "账号恢复码", "税务欠款", "移民局", "海关扣押", "居家兼职",
        "gift card", "crypto", "bitcoin", "tech support", "work from home", "money mule",
        "tax debt", "immigration office", "customs seized", "account recovery code"
    )

    fun assess(text: String): Pair<Boolean, String> {
        val t = text.lowercase()
        val red = redline.firstOrNull { it.lowercase() in t }
        if (red != null) return true to "对方要求「$red」,是诈骗典型手法"
        val hits = words.filter { it.lowercase() in t }
        if (hits.size >= 2) return true to ("提到「" + hits.take(2).joinToString("、") + "」")
        if (hits.size == 1) return true to "提到「${hits[0]}」,请提高警惕"
        val link = url.find(text)?.value?.lowercase()
        if (link != null && (
                "xn--" in link || '@' in link || rawIp.containsMatchIn(link) ||
                    listOf("bit.ly", "tinyurl.com", "cutt.ly", "is.gd", "rebrand.ly").any { it in link }
                )) {
            return true to "链接隐藏或混淆了真实网址,不要直接打开"
        }
        return false to ""
    }
}
