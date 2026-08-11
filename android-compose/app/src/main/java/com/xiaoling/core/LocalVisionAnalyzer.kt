package com.xiaoling.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object LocalVisionAnalyzer {
    suspend fun describe(bitmap: Bitmap, prompt: String = ""): String? = suspendCoroutine { continuation ->
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build()
        )
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                val names = labels
                    .sortedByDescending { it.confidence }
                    .mapNotNull { translate(it.text) }
                    .distinct()
                    .take(3)
                labeler.close()
                val subject = names.joinToString("、")
                val safety = when {
                    names.any { it == "药品" } -> "药名和用法请以包装或医生说明为准，不要只凭外观服用。"
                    names.any { it == "刀具" || it == "工具" } -> "拿取时请注意锋利部位。"
                    names.any { it == "食物" || it == "水果" || it == "蔬菜" } -> "食用前请再确认保质期和是否变质。"
                    else -> ""
                }
                val askedForDetails = Regex("用途|怎么用|能不能吃|什么药|安全吗|真假").containsMatchIn(prompt)
                continuation.resume(
                    if (names.isEmpty()) null
                    else buildString {
                        append("我在画面里看到了").append(subject).append("。")
                        if (safety.isNotBlank()) append(safety)
                        if (askedForDetails && safety.isBlank()) append("仅凭当前画面不能可靠判断具体型号或真假。")
                        append("如果不对，请把目标放在画面中央并靠近一些。")
                    }
                )
            }
            .addOnFailureListener {
                labeler.close()
                continuation.resume(null)
            }
    }

    private fun translate(value: String): String? {
        val text = value.lowercase()
        return when {
            "mobile phone" in text || "smartphone" in text || text == "phone" -> "手机"
            "tablet" in text -> "平板电脑"
            "laptop" in text -> "笔记本电脑"
            "computer" in text || "monitor" in text -> "电脑或显示器"
            "television" in text || text == "tv" -> "电视"
            "camera" in text -> "相机"
            "book" in text -> "书本"
            "bottle" in text -> "瓶子"
            "cup" in text || "mug" in text -> "杯子"
            "plate" in text || "dish" in text -> "盘子"
            "spoon" in text -> "勺子"
            "fork" in text -> "叉子"
            "knife" in text -> "刀具"
            "food" in text || "dish" in text || "cuisine" in text -> "食物"
            "apple" in text -> "苹果"
            "banana" in text -> "香蕉"
            "orange" in text -> "橙子"
            "fruit" in text -> "水果"
            "vegetable" in text -> "蔬菜"
            "flower" in text -> "花"
            "plant" in text || "tree" in text -> "植物"
            "dog" in text -> "狗"
            "cat" in text -> "猫"
            "bird" in text -> "鸟"
            "person" in text || "human" in text || "face" in text -> "人"
            "hand" in text -> "手"
            "shoe" in text || "footwear" in text -> "鞋"
            "clothing" in text || "shirt" in text || "dress" in text || "jacket" in text -> "衣物"
            "chair" in text -> "椅子"
            "table" in text || "desk" in text -> "桌子"
            "furniture" in text -> "家具"
            "car" in text || "vehicle" in text -> "车辆"
            "bicycle" in text || "bike" in text -> "自行车"
            "motorcycle" in text -> "摩托车"
            "medicine" in text || "medication" in text || "pill" in text -> "药品"
            "watch" in text || "clock" in text -> "钟表"
            "glasses" in text || "eyewear" in text -> "眼镜"
            "bag" in text || "handbag" in text -> "包"
            "toy" in text -> "玩具"
            "tool" in text -> "工具"
            "package" in text || "box" in text || "carton" in text -> "包装盒"
            "document" in text || "paper" in text -> "纸张或文件"
            "barcode" in text || "qr code" in text -> "条码或二维码"
            "currency" in text || "money" in text || "banknote" in text -> "现金"
            "appliance" in text -> "家用电器"
            else -> null
        }
    }
}
