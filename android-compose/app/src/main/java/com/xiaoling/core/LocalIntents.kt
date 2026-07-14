package com.xiaoling.core

import org.json.JSONObject

/**
 * 端侧快通道:高频指令(打电话/导航/提醒/听)本地即时解析,不等网络 → 极致反应。
 * 镜像 server/skills.py;命中不了返回 null,再走云端大脑。
 */
object LocalIntents {

    fun parse(text: String): Reply? {
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

        return null
    }

    private fun clean(s: String): String =
        s.trim().replace(Regex("[的吧呢啊,。!\\s]+$"), "")
}
