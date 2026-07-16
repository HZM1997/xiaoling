package com.xiaoling.core

import org.json.JSONObject

/**
 * 端侧快通道:高频指令(打电话/导航/提醒/听/翻译/闲聊问候)本地即时解析,不等网络 → 极致反应。
 * 镜像 server/skills.py;命中不了返回 null,再走云端大脑。弱网/离线下这一层保证 <0.3s 秒回。
 */
object LocalIntents {

    // 端侧翻译词库(普/英/粤),离线即时命中
    private val PHRASES: Map<String, Pair<String, String>> = mapOf(  // 普通话 -> (英语, 粤语)
        "你好" to ("Hello" to "你好"),
        "谢谢" to ("Thank you" to "多谢"),
        "不客气" to ("You're welcome" to "唔使客气"),
        "对不起" to ("Sorry" to "对唔住"),
        "再见" to ("Goodbye" to "拜拜"),
        "多少钱" to ("How much is it?" to "几多钱"),
        "太贵了" to ("That's too expensive" to "太贵啦"),
        "便宜一点" to ("Can it be cheaper?" to "平啲得唔得"),
        "厕所在哪里" to ("Where is the toilet?" to "厕所喺边度"),
        "怎么走" to ("How do I get there?" to "点去"),
        "我迷路了" to ("I'm lost" to "我蕩失路"),
        "我不舒服" to ("I don't feel well" to "我唔舒服"),
        "我头晕" to ("I feel dizzy" to "我头晕"),
        "我胸口疼" to ("My chest hurts" to "我心口痛"),
        "帮帮我" to ("Please help me" to "帮帮我"),
        "我要去医院" to ("I need to go to the hospital" to "我要去医院"),
        "叫救护车" to ("Call an ambulance" to "叫白车"),
        "报警" to ("Call the police" to "报警"),
        "我听不懂" to ("I don't understand" to "我听唔明"),
        "请慢一点说" to ("Please speak slower" to "请讲慢啲"),
        "请再说一遍" to ("Could you say that again?" to "请再讲多次"),
        "吃饭了吗" to ("Have you eaten?" to "食咗饭未"),
        "多喝热水" to ("Drink more hot water" to "多啲饮暖水"),
        "早上好" to ("Good morning" to "早晨"),
        "晚上好" to ("Good evening" to "晚上好"),
        "晚安" to ("Good night" to "早唞"),
        "多保重" to ("Take care" to "保重"),
        "慢走" to ("Take care on your way" to "慢慢行"),
        "现在几点" to ("What time is it now?" to "而家几点"),
        "我爱你" to ("I love you" to "我爱你"),
        "想你了" to ("I miss you" to "挂住你"),
        "祝你健康" to ("Wish you good health" to "祝你身体健康"),
        "新年快乐" to ("Happy New Year" to "新年快乐"),
        "生日快乐" to ("Happy birthday" to "生日快乐"),
        "这个用英语怎么说" to ("How do you say this in English?" to "呢个英文点讲")
    )

    // 反向:英语/粤语 → 普通话(用户说外语,翻回中文)
    private val REVERSE: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((zh, pair) in PHRASES) {
            m[pair.first.lowercase().trimEnd('?', '.', '!')] = zh
            m[pair.second] = zh
        }
        m
    }

    fun parse(text: String): Reply? {
        // —— 举报诈骗号码(数据飞轮):"举报这个号码/这是骗子/拉黑这个号码" ——
        if (Regex("举报|拉黑|这是(个)?骗子|是诈骗|加入黑名单").containsMatchIn(text)) {
            return Reply("好的,正在为您举报这个号码。",
                JSONObject().put("type", "REPORT_NUMBER"), "举报号码", 0.0)
        }
        // —— 标为可信:"这是我女儿/加白/信任这个号码" ——
        if (Regex("这是我(儿子|女儿|家人|亲戚)|标为可信|信任这个号码|加白").containsMatchIn(text)) {
            return Reply("好的,已把最近这个号码标为可信,以后不会再误报。",
                JSONObject().put("type", "TRUST_NUMBER"), "信任号码", 0.0)
        }
        // —— 实时翻译(离线词库即时)——
        parseTranslate(text)?.let { return it }

        var m = Regex("(?:打(?:个)?电话给?|呼叫|拨打?给?)\\s*(.+)").find(text)
        if (m != null) {
            val name = clean(m.groupValues[1])
            return Reply("好的,正在帮您给$name 打电话。",
                JSONObject().put("type", "CALL").put("target", name), "打电话", 0.0)
        }

        m = Regex("(?:导航到?|去|怎么走到?|怎么去)\\s*(.+)").find(text)
        if (m != null) {
            val dest = clean(m.groupValues[1].replace(Regex("(怎么走|怎么去)$"), ""))
            if (dest.isNotBlank()) {
                val uri = "androidamap://poi?sourceApplication=xiaoling&keywords=$dest&dev=0"
                return Reply("好的,这就打开地图,导航到$dest。",
                    JSONObject().put("type", "OPEN_URI").put("uri", uri), "导航", 0.0)
            }
        }

        if (Regex("提醒我?.*(吃药|量血压|喝水|睡觉|起床)").containsMatchIn(text)) {
            return Reply("好的,我会到点提醒您,放心。",
                JSONObject().put("type", "REMIND").put("raw", text), "用药提醒", 0.0)
        }

        if (Regex("(听|放|播放|来一?段?|唱).*(歌|戏|剧|曲|评书|相声|音乐)").containsMatchIn(text)) {
            val kw = clean(Regex("(?:听|放|播放|来一?段?|唱)\\s*(.+)").find(text)?.groupValues?.get(1) ?: "")
            return Reply("好嘞,这就给您放$kw。",
                JSONObject().put("type", "PLAY").put("keyword", kw), "听戏听歌", 0.0)
        }

        // —— 离线问候闲聊兜底(弱网也秒回,不至于冷场)——
        if (Regex("^(你好|您好|在吗|在不在|早上好|晚上好|嗨|哈喽)").containsMatchIn(text)) {
            return Reply("我在呢,您说,想打电话、导航、翻译都可以。", null, "chat", 0.0)
        }

        return null
    }

    private fun parseTranslate(text: String): Reply? {
        if (!Regex("翻译|怎么说|用.{0,3}语|译成|译为").containsMatchIn(text)) return null
        val lang = when {
            Regex("粤语|广东话|白话").containsMatchIn(text) -> "cantonese"
            Regex("普通话|国语").containsMatchIn(text) -> "mandarin"
            else -> "english"
        }
        val langCn = if (lang == "cantonese") "粤语" else if (lang == "mandarin") "普通话" else "英语"
        val content = clean(text.replace(
            Regex("(请)?(帮我)?(把)?|翻译(成|为)?|用.{0,3}语(怎么说|说)?|怎么说|译成|译为|英语|英文|粤语|广东话|白话|普通话|国语|中文|[,。?!]"), ""))
        if (content.isBlank()) return null
        val out = when (lang) {
            "mandarin" -> {
                // 目标普通话:先查反向词库(英语/粤语→中文),命中即用,否则原样返回内容
                REVERSE[content.lowercase().trimEnd('?', '.', '!')] ?: REVERSE[content] ?: content
            }
            "english" -> PHRASES.entries.firstOrNull { content.contains(it.key) || it.key.contains(content) }?.value?.first
            else -> PHRASES.entries.firstOrNull { content.contains(it.key) || it.key.contains(content) }?.value?.second
        }
        // 词库没命中 → 返回 null,交给云端大模型翻译(联网时);离线时上层会走兜底话术
        if (out == null) return null
        return Reply("$content 的$langCn 是:$out",
            JSONObject().put("type", "SPEAK").put("text", out).put("lang", lang), "实时翻译", 0.0)
    }

    private fun clean(s: String): String =
        s.trim().replace(Regex("[的吧呢啊,。!\\s]+$"), "")
}

