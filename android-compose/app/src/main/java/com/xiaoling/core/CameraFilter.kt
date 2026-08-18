package com.xiaoling.core

import android.graphics.ColorMatrix

/** Parsed locally so camera style changes stay immediate during weak network. */
data class CameraStyleIntent(
    val filter: CameraFilter? = null,
    val strength: Float? = null,
    val exposure: Float? = null,
    val saturation: Float? = null,
    val whitening: Float? = null,
    val smoothing: Float? = null,
    val description: String? = null,
)

/**
 * Small, offline camera styles. The same style is sent to realtime so a voice
 * response can acknowledge exactly what the preview is showing.
 */
enum class CameraFilter(
    val id: String,
    val label: String,
    val aliases: Set<String>,
) {
    Natural("natural", "原色", setOf("原色", "原图", "真实", "自然", "不调色")),
    Warm("warm", "暖阳", setOf("暖阳", "暖色", "暖调", "暖一点")),
    Cream("cream", "奶油", setOf("奶油", "奶油感", "人像", "温柔", "柔和")),
    Mist("mist", "柔雾", setOf("柔雾", "雾感", "朦胧", "梦幻")),
    Cool("cool", "清冷", setOf("清冷", "冷色", "冷调", "蓝调", "小清新")),
    Vivid("vivid", "通透", setOf("通透", "鲜艳", "浓郁", "鲜明", "清晰")),
    Sunset("sunset", "落日", setOf("落日", "日落", "夕阳", "橘调")),
    Forest("forest", "森系", setOf("森系", "森林", "清绿", "自然绿")),
    TealOrange("teal_orange", "青橙", setOf("青橙", "橙青", "电影感")),
    Vintage("vintage", "复古", setOf("复古", "怀旧", "老照片")),
    Film("film", "胶片", setOf("胶片", "胶卷", "颗粒感")),
    HongKong("hong_kong", "港风", setOf("港风", "港片", "港式")),
    Mono("mono", "黑白", setOf("黑白", "灰度", "单色")),
    Noir("noir", "暗调", setOf("暗黑白", "硬黑白", "纪实黑白", "暗调黑白"));

    fun colorMatrix(
        strength: Float = 1f,
        exposure: Float = 0f,
        saturation: Float = 1f,
        whitening: Float = 0f,
    ): ColorMatrix? {
        val safeStrength = strength.coerceIn(0.25f, 1f)
        val safeExposure = exposure.coerceIn(-0.35f, 0.35f)
        val safeSaturation = saturation.coerceIn(0.35f, 1.65f)
        val safeWhitening = whitening.coerceIn(0f, 1f)
        if (this == Natural && kotlin.math.abs(safeExposure) < 0.001f &&
            kotlin.math.abs(safeSaturation - 1f) < 0.001f && safeWhitening < 0.001f) return null
        val target = targetMatrix()
        val output = FloatArray(20) { index ->
            IDENTITY[index] + (target[index] - IDENTITY[index]) * safeStrength
        }
        val light = safeExposure * 42f + safeWhitening * 24f
        val whiteContrast = 1f - safeWhitening * 0.06f
        output[0] *= whiteContrast
        output[6] *= whiteContrast
        output[12] *= whiteContrast
        output[4] += light
        output[9] += light
        output[14] += light
        return ColorMatrix(output).apply {
            if (kotlin.math.abs(safeSaturation - 1f) >= 0.001f) {
                postConcat(ColorMatrix().apply { setSaturation(safeSaturation) })
            }
        }
    }

    private fun targetMatrix(): FloatArray = when (this) {
        Natural -> IDENTITY
        Warm -> floatArrayOf(
            1.10f, 0.025f, -0.01f, 0f, 8f,
            0.01f, 1.02f, 0.01f, 0f, 2f,
            -0.02f, 0.015f, 0.86f, 0f, -7f,
            0f, 0f, 0f, 1f, 0f,
        )
        Cream -> floatArrayOf(
            1.02f, 0.035f, 0.012f, 0f, 13f,
            0.018f, 0.99f, 0.018f, 0f, 8f,
            0.012f, 0.028f, 0.91f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f,
        )
        Mist -> floatArrayOf(
            0.84f, 0.055f, 0.025f, 0f, 24f,
            0.025f, 0.85f, 0.045f, 0f, 23f,
            0.018f, 0.042f, 0.90f, 0f, 26f,
            0f, 0f, 0f, 1f, 0f,
        )
        Cool -> floatArrayOf(
            0.88f, 0.018f, 0.018f, 0f, -6f,
            -0.008f, 1.01f, 0.025f, 0f, 3f,
            0.008f, 0.035f, 1.13f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        )
        Sunset -> floatArrayOf(
            1.15f, 0.045f, -0.015f, 0f, 13f,
            0.025f, 0.99f, 0.005f, 0f, 3f,
            -0.025f, 0.02f, 0.76f, 0f, -12f,
            0f, 0f, 0f, 1f, 0f,
        )
        Forest -> floatArrayOf(
            0.86f, 0.035f, 0.012f, 0f, -4f,
            0.01f, 1.10f, 0.02f, 0f, 7f,
            0.01f, 0.045f, 0.88f, 0f, 1f,
            0f, 0f, 0f, 1f, 0f,
        )
        TealOrange -> floatArrayOf(
            1.12f, 0.025f, -0.025f, 0f, 8f,
            -0.015f, 1.01f, 0.04f, 0f, 1f,
            -0.03f, 0.055f, 1.12f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f,
        )
        Vintage -> ColorMatrix().apply {
            setSaturation(0.68f)
            postConcat(ColorMatrix(floatArrayOf(
                1.08f, 0.04f, 0.02f, 0f, 12f,
                0.02f, 0.96f, 0.02f, 0f, 4f,
                0.01f, 0.05f, 0.80f, 0f, -4f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }.array.copyOf()
        Film -> ColorMatrix().apply {
            setSaturation(0.78f)
            postConcat(ColorMatrix(floatArrayOf(
                1.03f, 0.02f, 0.01f, 0f, 7f,
                0.01f, 0.98f, 0.01f, 0f, 3f,
                0.01f, 0.03f, 0.90f, 0f, -2f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }.array.copyOf()
        HongKong -> floatArrayOf(
            1.13f, 0.02f, -0.01f, 0f, 7f,
            0.015f, 0.92f, 0.035f, 0f, -1f,
            -0.015f, 0.045f, 1.04f, 0f, 3f,
            0f, 0f, 0f, 1f, 0f,
        )
        Mono -> ColorMatrix().apply { setSaturation(0f) }.array.copyOf()
        Noir -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                1.22f, 0f, 0f, 0f, -16f,
                0f, 1.22f, 0f, 0f, -16f,
                0f, 0f, 1.22f, 0f, -16f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }.array.copyOf()
        Vivid -> ColorMatrix().apply { setSaturation(1.34f) }.array.copyOf()
    }

    companion object {
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        fun fromId(value: String?): CameraFilter =
            entries.firstOrNull { it.id == value } ?: Natural

        /** Convert everyday photographic descriptions into immediate preview parameters. */
        fun parseVoice(text: String): CameraStyleIntent? {
            val value = text.trim()
            if (value.isBlank() || !isStyleRequest(value)) return null

            val preset = when {
                Regex("战国|亡国|没落公主|落难公主|古国|残败|苍凉|宿命感|破碎感|废墟古风").containsMatchIn(value) ->
                    CameraStyleIntent(Vintage, 0.88f, -0.10f, 0.58f, 0.08f, 0.10f, "低饱和冷旧胶片")
                Regex("网红|小红书|博主|出片|高级人像|氛围感人像|精致自拍").containsMatchIn(value) ->
                    CameraStyleIntent(Cream, 0.82f, 0.13f, 1.10f, 0.32f, 0.34f, "通透网红人像")
                Regex("清澈|清透|干净感|水光|水感|空气感|透明感").containsMatchIn(value) ->
                    CameraStyleIntent(Cool, 0.68f, 0.09f, 0.90f, 0.16f, 0.12f, "清澈冷调")
                Regex("白月光|初恋感|纯欲|仙气|柔光|朦胧人像").containsMatchIn(value) ->
                    CameraStyleIntent(Mist, 0.74f, 0.12f, 0.88f, 0.28f, 0.30f, "柔光白月光")
                Regex("赛博|霓虹|未来感|科技感|蓝橙电影").containsMatchIn(value) ->
                    CameraStyleIntent(TealOrange, 0.92f, -0.04f, 1.28f, 0.04f, 0.04f, "青橙赛博电影")
                Regex("古风|古装|国风|东方感|故事感|电影叙事").containsMatchIn(value) ->
                    CameraStyleIntent(Film, 0.82f, -0.05f, 0.76f, 0.10f, 0.12f, "克制古风胶片")
                Regex("祛黄|去黄|肤色均匀|气色好|红润一点|有气色").containsMatchIn(value) ->
                    CameraStyleIntent(Cream, 0.56f, 0.04f, 1.04f, 0.14f, 0.24f, "自然匀净肤色")
                Regex("美颜|美妆|自拍美颜|自然美颜|白里透红|柔焦人像").containsMatchIn(value) ->
                    CameraStyleIntent(Cream, 0.66f, 0.08f, 1.03f, 0.26f, 0.30f, "自然美颜人像")
                else -> null
            }

            val explicitFilter = entries
                .sortedByDescending { candidate -> candidate.aliases.maxOf(String::length) }
                .firstOrNull { candidate -> candidate.aliases.any(value::contains) }
            val filter = explicitFilter ?: preset?.filter
            val explicitStrength = when {
                explicitFilter == Natural -> 1f
                Regex("一点点|淡一点|浅一点|弱一点|轻一点|不要太|柔一点").containsMatchIn(value) -> 0.52f
                Regex("适中|正常一点|刚好").containsMatchIn(value) -> 0.72f
                Regex("浓一点|重一点|强一点|明显一点|更浓|更强").containsMatchIn(value) -> 0.96f
                else -> null
            }
            val explicitExposure = when {
                Regex("调亮|亮一点|明亮一点|提亮|白一点").containsMatchIn(value) -> 0.15f
                Regex("调暗|暗一点|压暗|深一点").containsMatchIn(value) -> -0.15f
                else -> null
            }
            val explicitSaturation = when {
                Regex("低饱和|降饱和|素一点|灰一点").containsMatchIn(value) -> 0.70f
                Regex("高饱和|加饱和|饱和一点").containsMatchIn(value) -> 1.35f
                else -> null
            }
            val explicitWhitening = when {
                Regex("关闭美白|不要美白|恢复肤色").containsMatchIn(value) -> 0f
                Regex("不要假白|自然肤色|美白自然|降低美白|美白少一点").containsMatchIn(value) -> 0.12f
                Regex("美白.*(强|多)|白很多|再白一点").containsMatchIn(value) -> 0.50f
                Regex("祛黄|去黄|肤色均匀|红润一点|有气色").containsMatchIn(value) -> 0.14f
                Regex("美白|白一点|提亮肤色|肤色亮|白里透红").containsMatchIn(value) -> 0.30f
                else -> null
            }
            val explicitSmoothing = when {
                Regex("关闭磨皮|不要磨皮").containsMatchIn(value) -> 0f
                Regex("保留皮肤纹理|磨皮自然|自然磨皮|降低磨皮|磨皮少一点|别磨太狠").containsMatchIn(value) -> 0.14f
                Regex("磨皮.*(强|多)|皮肤更光滑").containsMatchIn(value) -> 0.55f
                Regex("肤色均匀|祛黄|去黄").containsMatchIn(value) -> 0.24f
                Regex("磨皮|祛痘|皮肤细腻|皮肤光滑|柔焦|美颜").containsMatchIn(value) -> 0.32f
                else -> null
            }
            return CameraStyleIntent(
                filter = filter,
                strength = explicitStrength ?: preset?.strength,
                exposure = explicitExposure ?: preset?.exposure,
                saturation = explicitSaturation ?: preset?.saturation,
                whitening = explicitWhitening ?: preset?.whitening,
                smoothing = explicitSmoothing ?: preset?.smoothing,
                description = preset?.description,
            ).takeIf {
                it.filter != null || it.strength != null || it.exposure != null || it.saturation != null ||
                    it.whitening != null || it.smoothing != null
            }
        }

        fun fromVoice(text: String): CameraFilter? = parseVoice(text)?.filter

        /** Offline fallback for reference images. It copies only broad color
         * characteristics; semantic mood is handled by the vision service. */
        fun analyzeReferenceLocally(bitmap: android.graphics.Bitmap): CameraStyleIntent? {
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val stepX = (bitmap.width / 64).coerceAtLeast(1)
            val stepY = (bitmap.height / 64).coerceAtLeast(1)
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            var count = 0
            var y = stepY / 2
            while (y < bitmap.height) {
                var x = stepX / 2
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    red += (pixel shr 16) and 0xff
                    green += (pixel shr 8) and 0xff
                    blue += pixel and 0xff
                    count++
                    x += stepX
                }
                y += stepY
            }
            if (count == 0) return null
            val r = red / count
            val g = green / count
            val b = blue / count
            val brightness = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
            val spread = (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
            return when {
                brightness < 0.34 && spread < 0.16 -> CameraStyleIntent(
                    Noir, 0.68f, -0.06f, 0.62f, 0.04f, 0.08f, "参考图暗调纪实")
                spread < 0.12 -> CameraStyleIntent(
                    Film, 0.64f, (brightness - 0.52).toFloat().coerceIn(-0.12f, 0.12f),
                    0.72f, 0.08f, 0.10f, "参考图低饱和胶片")
                b - r > 12.0 -> CameraStyleIntent(
                    Cool, 0.68f, (0.54 - brightness).toFloat().coerceIn(-0.10f, 0.12f),
                    0.92f, 0.12f, 0.10f, "参考图清冷色调")
                r - b > 16.0 -> CameraStyleIntent(
                    Warm, 0.72f, (0.52 - brightness).toFloat().coerceIn(-0.10f, 0.10f),
                    1.04f, 0.12f, 0.10f, "参考图暖色调")
                spread > 0.34 -> CameraStyleIntent(
                    Vivid, 0.62f, (0.50 - brightness).toFloat().coerceIn(-0.08f, 0.10f),
                    1.18f, 0.08f, 0.06f, "参考图鲜明色彩")
                else -> CameraStyleIntent(
                    Natural, 0.70f, (0.52 - brightness).toFloat().coerceIn(-0.10f, 0.10f),
                    1.0f, 0.10f, 0.08f, "参考图自然色调")
            }
        }

        private fun isStyleRequest(text: String): Boolean = Regex(
            "滤镜|调色|色调|风格|效果|换成|切成|调成|弄成|做成|来个|恢复原色|原图|不调色|" +
            "美颜|美妆|柔焦|美白|磨皮|祛痘|肤色|纹理|祛黄|去黄|红润|气色|假白|白里透红|恢复自然|出片|网红|小红书|清澈|清透|空气感|白月光|初恋感|" +
                "战国|亡国|没落公主|落难公主|古风|古装|国风|宿命感|破碎感|赛博|霓虹|" +
                "调(亮|暗|淡|浓|重|强|弱)|调(高|低).*饱和|拍(得|成|出|个|一张).*(暖|冷|复古|黑白|鲜艳|明亮|原色)|" +
                "画面.*(暖|冷|复古|黑白|鲜艳|明亮|原色|感觉)|要.*(暖|冷|复古|黑白|鲜艳|明亮|原色|感觉)"
        ).containsMatchIn(text)
    }
}
