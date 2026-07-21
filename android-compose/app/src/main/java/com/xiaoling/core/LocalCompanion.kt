package com.xiaoling.core

/** 云端暂不可用时的轻量陪伴回复,避免重复固定宣传话术。 */
object LocalCompanion {
    fun reply(text: String, previousUser: String = ""): Reply {
        val compact = text.trim().replace(Regex("\\s+"), " ").take(28)
        val speech = when {
            Regex("你好|您好|在吗|小灵").containsMatchIn(compact) ->
                "在呢,您慢慢说,我一直听着。"
            Regex("孤单|寂寞|想聊天|陪我|睡不着").containsMatchIn(compact) ->
                "我陪着您。今天有什么事一直放在心里?"
            Regex("不开心|生气|难过|烦|担心").containsMatchIn(compact) ->
                "听起来这件事让您不太好受。您慢慢讲,我听着呢。"
            Regex("谢谢|多谢").containsMatchIn(compact) ->
                "不用客气,能帮上您就好。"
            Regex("再见|不聊了|休息了").containsMatchIn(compact) ->
                "好,您先歇一会儿。有需要直接叫我。"
            compact.contains("?") || Regex("什么|怎么|为什么|多少|哪里|哪儿|能不能").containsMatchIn(compact) ->
                "您问的是“$compact”。我这会儿没查到可靠答案,不想随口说错。网络恢复后我再认真帮您查。"
            previousUser.isNotBlank() && Regex("刚才|接着|然后|那个").containsMatchIn(compact) ->
                "我记得您刚才说的是“${previousUser.take(18)}”。您接着说,我在听。"
            else -> varied(compact)
        }
        return Reply(speech, null, "offline:companion", 0.0)
    }

    private fun varied(text: String): String {
        val subject = text.ifBlank { "这件事" }
        val options = listOf(
            "我听见您说“$subject”了。您是想聊聊它,还是要我帮您办件事?",
            "好,这句话我记下了。关于“$subject”,您再多说一点好吗?",
            "我在认真听。您说的“$subject”,最想让我帮您解决哪一部分?",
            "明白一些了。您可以接着说“$subject”后面要做什么。"
        )
        return options[(text.hashCode() and Int.MAX_VALUE) % options.size]
    }
}
