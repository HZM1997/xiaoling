package com.xiaoling.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object LocalVisionAnalyzer {
    suspend fun describe(bitmap: Bitmap): String? = suspendCoroutine { continuation ->
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
                continuation.resume(
                    if (names.isEmpty()) null
                    else "我在画面里看到了${names.joinToString("、")}。请把要识别的物品放在画面中央，我能看得更准确。"
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
            else -> null
        }
    }
}
