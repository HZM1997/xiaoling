package com.xiaoling.core

import android.content.Context
import org.json.JSONObject

/**
 * 端侧快通道:高频指令(打电话/导航/提醒/听/翻译/闲聊问候)本地即时解析,不等网络 → 极致反应。
 * 镜像 server/skills.py;命中不了返回 null,再走云端大脑。弱网/离线下这一层保证 <0.3s 秒回。
 * 翻译词库从 assets/translate/phrases.json 加载(端云共用同一份,文本资源压缩,不胀包)。
 */
object LocalIntents {

    // 端侧翻译词库(普通话 -> (英语, 粤语)),启动时从 assets 加载;加载前用兜底小表
    private var PHRASES: Map<String, Pair<String, String>> = mapOf(
        "你好" to ("Hello" to "你好"),
        "谢谢" to ("Thank you" to "多谢"),
        "帮帮我" to ("Please help me" to "帮帮我"),
        "叫救护车" to ("Call an ambulance" to "叫白车"),
        "我不舒服" to ("I don't feel well" to "我唔舒服")
    )

    private var REVERSE: Map<String, String> = buildReverse(PHRASES)

    /** 应用启动时调用一次,从 assets 加载完整词库(90+ 条) */
    fun load(ctx: Context) {
        try {
            val txt = ctx.assets.open("translate/phrases.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(txt).getJSONObject("phrases")
            val map = HashMap<String, Pair<String, String>>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val zh = keys.next()
                val e = obj.getJSONObject(zh)
                map[zh] = e.optString("en") to e.optString("yue")
            }
            if (map.isNotEmpty()) {
                PHRASES = map
                REVERSE = buildReverse(map)
            }
        } catch (e: Exception) { /* 加载失败保留兜底小表 */ }
    }

    private fun buildReverse(p: Map<String, Pair<String, String>>): Map<String, String> {
        val m = HashMap<String, String>(p.size * 2)
        for ((zh, pair) in p) {
            if (pair.first.isNotBlank()) m[pair.first.lowercase().trimEnd('?', '.', '!')] = zh
            if (pair.second.isNotBlank()) m[pair.second] = zh
        }
        return m
    }

    /** 清理老人常见停顿词和口头填充,保留时间、人物、动作等有效信息。 */
    fun normalizeSpeech(input: String): String {
        var text = input.trim()
            .replace('；', '，').replace(';', '，').replace(',', '，')
            .replace(Regex("[。.!！]+$"), "")
            .replace(Regex("^(嗯+|呃+|那个|就是|这个|你看|麻烦你?|请你?|我想让你|我想要你|你帮我|帮我)\\s*"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { input.trim() }
    }

    /** 一句话含多个明确动作时在端侧直接拆成任务队列,不等待云端大模型。 */
    fun parse(text: String): Reply? {
        val normalized = normalizeSpeech(text)
        val parts = normalized.split(Regex("\\s*(?:然后|接着|顺便|并且|同时|还有|再)\\s*"))
            .map { it.trim('，', ' ') }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val replies = parts.map { parseSingle(it) }
            if (replies.all { it?.action != null }) {
                val steps = org.json.JSONArray()
                replies.forEach { steps.put(it!!.action) }
                val summary = replies.joinToString("然后") { it!!.speech.trimEnd('。', '.', '，', ',') }
                return Reply(summary, JSONObject().put("type", "TASKS").put("steps", steps), "端侧连续多指令", 0.0)
            }
        }
        return parseSingle(normalized)
    }

    private fun parseSingle(text: String): Reply? {
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

        // —— 亲情语音留言:录制 ——
        if (Regex("录(一?段)?留言|录(个)?语音|给.{0,4}(留言|录)").containsMatchIn(text)
            && !Regex("发给|发送").containsMatchIn(text)) {
            return Reply("好的,请对我说您想留的话,说完松开按钮就行。",
                JSONObject().put("type", "RECORD_MEMO"), "录留言", 0.0)
        }
        // —— 亲情语音留言:发送给某人(自动匹配联系人)——
        Regex("(?:把(?:这条|刚才的)?(?:语音|留言)?)?发(?:给|送给)\\s*(.+)").find(text)?.let { mm ->
            val who = clean(mm.groupValues[1].replace(Regex("(的)?(语音|留言|录音)$"), ""))
            if (who.isNotBlank()) {
                return Reply("好的,把语音留言发给$who。",
                    JSONObject().put("type", "SEND_MEMO").put("target", who), "发留言", 0.0)
            }
        }

        var m = Regex("(?:给|帮我给)\\s*(.+?)\\s*(?:打|拨)(?:个)?电话").find(text)
            ?: Regex("(.+?)\\s*(?:电话打一个|电话拨一个)").find(text)
            ?: Regex("(?:打(?:个)?电话给?|呼叫|拨打?给?)\\s*(.+)").find(text)
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

        if (Regex("提醒|闹钟|别忘了|记一下").containsMatchIn(text)) {
            return Reply("好的,我来帮您设置提醒。",
                JSONObject().put("type", "REMIND").put("raw", text), "语音任务提醒", 0.0)
        }

        if (Regex("(听|放|播放|来一?段?|唱).*(歌|戏|剧|曲|评书|相声|音乐)|(歌|戏|剧|曲|评书|相声|音乐).*(放|播|来一个)").containsMatchIn(text)) {
            val kw = clean(
                Regex("(?:听|放|播放|来一?段?|唱)\\s*(.+)").find(text)?.groupValues?.get(1)
                    ?: Regex("(.+?)\\s*(?:放|播放|播|来一个)").find(text)?.groupValues?.get(1)
                    ?: "戏曲"
            )
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
