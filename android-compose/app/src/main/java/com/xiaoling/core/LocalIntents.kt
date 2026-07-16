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
        "再见" to ("Goodbye" to "拜拜"),
        "多少钱" to ("How much is it?" to "几多钱"),
        "厕所在哪里" to ("Where is the toilet?" to "厕所喺边度"),
        "我不舒服" to ("I don't feel well" to "我唔舒服"),
        "帮帮我" to ("Please help me" to "帮帮我"),
        "我要去医院" to ("I need to go to the hospital" to "我要去医院"),
        "叫救护车" to ("Call an ambulance" to "叫救护车"),
        "我听不懂" to ("I don't understand" to "我听唔明"),
        "请慢一点说" to ("Please speak slower" to "请讲慢啲"),
        "吃饭了吗" to ("Have you eaten?" to "食咗饭未"),
        "早上好" to ("Good morning" to "早晨"),
        "晚安" to ("Good night" to "晚安"),
        "多保重" to ("Take care" to "保重"),
        "现在几点" to ("What time is it now?" to "而家几点"),
        "太贵了" to ("That's too expensive" to "太贵啦"),
        "我爱你" to ("I love you" to "我爱你"),
        "祝你健康" to ("Wish you good health" to "祝你身体健康")
    )

    fun parse(text: String): Reply? {
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
        val hit = PHRASES.entries.firstOrNull { content.contains(it.key) || it.key.contains(content) }?.value
        val out = when {
            lang == "mandarin" -> content
            hit != null && lang == "english" -> hit.first
            hit != null -> hit.second
            else -> null
        }
        // 词库没命中 → 返回 null,交给云端大模型翻译(联网时);离线时上层会走兜底话术
        if (out == null) return null
        return Reply("$content 的$langCn 是:$out",
            JSONObject().put("type", "SPEAK").put("text", out).put("lang", lang), "实时翻译", 0.0)
    }

    private fun clean(s: String): String =
        s.trim().replace(Regex("[的吧呢啊,。!\\s]+$"), "")
}

