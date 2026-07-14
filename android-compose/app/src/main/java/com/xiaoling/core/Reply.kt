package com.xiaoling.core

import org.json.JSONObject

/** 大脑返回:说什么 + 执行什么动作 + 命中技能 + 风险分 */
data class Reply(
    val speech: String,
    val action: JSONObject?,
    val skill: String,
    val risk: Double
)
