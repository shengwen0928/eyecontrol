package com.example.eyecontrol

import org.json.JSONObject

/**
 * 分區視線模型：螢幕切成 3×3 共 9 區，每區各有一組以該區 9 個點訓練出來的模型；
 * 另有一個用全部 81 點訓練的全域模型，先粗估落在哪一區，再用該區的模型精算。
 *
 * 不同視線角度下眼睛的外觀不同，單一全域模型在邊角容易不準；分區模型讓每個
 * 角度範圍都有自己專屬的對應關係。某區模型缺失時退回全域模型。
 */
class ZonedGazeModel(
    val global: GazeModel,
    val zones: Map<String, GazeModel>,
) {

    /** 先用全域模型粗估落點分區，再用該區模型精算；該區無模型時用全域結果。 */
    fun predict(f: DoubleArray, screenW: Int = 1080, screenH: Int = 2400): Pair<Double, Double> {
        val (gx, gy) = global.predict(f, screenW, screenH)
        val local = zones[zoneIdFor(gx, gy, screenW, screenH)] ?: return gx to gy
        return local.predict(f, screenW, screenH)
    }

    fun toJsonString(): String {
        val json = JSONObject().apply {
            put("version", MODEL_VERSION)
            put("global", JSONObject(global.toJsonString()))
            val zoneObj = JSONObject()
            for ((id, model) in zones) zoneObj.put(id, JSONObject(model.toJsonString()))
            put("zones", zoneObj)
        }
        return json.toString()
    }

    companion object {
        const val ZONE_CENTER = "center"
        const val ZONE_TOP = "top"
        const val ZONE_BOTTOM = "bottom"
        const val ZONE_LEFT = "left"
        const val ZONE_RIGHT = "right"
        const val ZONE_TOP_LEFT = "topLeft"
        const val ZONE_BOTTOM_LEFT = "bottomLeft"
        const val ZONE_TOP_RIGHT = "topRight"
        const val ZONE_BOTTOM_RIGHT = "bottomRight"

        /** 分區順序：中間先，再上面、下面、左邊、右邊、左上、左下、右上、右下。 */
        val ZONE_ORDER = listOf(
            ZONE_CENTER, ZONE_TOP, ZONE_BOTTOM, ZONE_LEFT, ZONE_RIGHT,
            ZONE_TOP_LEFT, ZONE_BOTTOM_LEFT, ZONE_TOP_RIGHT, ZONE_BOTTOM_RIGHT,
        )

        private const val MODEL_VERSION = 1

        fun zoneName(id: String): String = when (id) {
            ZONE_CENTER -> "中間"
            ZONE_TOP -> "上面"
            ZONE_BOTTOM -> "下面"
            ZONE_LEFT -> "左邊"
            ZONE_RIGHT -> "右邊"
            ZONE_TOP_LEFT -> "左上"
            ZONE_BOTTOM_LEFT -> "左下"
            ZONE_TOP_RIGHT -> "右上"
            ZONE_BOTTOM_RIGHT -> "右下"
            else -> id
        }

        /** 依螢幕座標落點判定所屬區：三分法。 */
        fun zoneIdFor(x: Double, y: Double, screenW: Int, screenH: Int): String {
            val col = when {
                x < screenW / 3.0 -> 0
                x < screenW * 2.0 / 3.0 -> 1
                else -> 2
            }
            val row = when {
                y < screenH / 3.0 -> 0
                y < screenH * 2.0 / 3.0 -> 1
                else -> 2
            }
            return when (row to col) {
                0 to 0 -> ZONE_TOP_LEFT
                0 to 1 -> ZONE_TOP
                0 to 2 -> ZONE_TOP_RIGHT
                1 to 0 -> ZONE_LEFT
                1 to 1 -> ZONE_CENTER
                1 to 2 -> ZONE_RIGHT
                2 to 0 -> ZONE_BOTTOM_LEFT
                2 to 1 -> ZONE_BOTTOM
                else -> ZONE_BOTTOM_RIGHT
            }
        }

        fun zoneIdFor(target: Pair<Float, Float>, screenW: Int, screenH: Int): String =
            zoneIdFor(target.first.toDouble(), target.second.toDouble(), screenW, screenH)

        /**
         * 訓練：全域模型吃全部樣本；每區模型只吃落點在該區的樣本（9 個點）。
         * screenW/screenH 傳入時全區共用同一組，避免姿態補償項口徑不一。
         */
        fun train(
            features: List<DoubleArray>,
            targets: List<Pair<Float, Float>>,
            screenW: Double? = null,
            screenH: Double? = null,
            lambda: Double = 5e-4,
        ): ZonedGazeModel {
            val sw = screenW ?: run {
                val maxX = targets.maxOfOrNull { it.first } ?: 1080f
                if (maxX > 0) (maxX / 0.92f).toDouble() else 1080.0
            }
            val sh = screenH ?: run {
                val maxY = targets.maxOfOrNull { it.second } ?: 2400f
                if (maxY > 0) (maxY / 0.92f).toDouble() else 2400.0
            }
            val global = GazeModel.train(features, targets, lambda, sw, sh)
            val grouped = targets.indices.groupBy { zoneIdFor(targets[it], sw.toInt(), sh.toInt()) }
            val zones = grouped
                .filterKeys { it in ZONE_ORDER }
                .mapValues { (_, idx) ->
                    GazeModel.train(
                        idx.map { features[it] },
                        idx.map { targets[it] },
                        lambda, sw, sh,
                    )
                }
            return ZonedGazeModel(global, zones)
        }

        fun fromJsonString(raw: String): ZonedGazeModel? {
            return runCatching {
                val json = JSONObject(raw)
                // 舊格式：頂層就是單一模型（GazeModel.toJsonString），包成只有全域模型的分區模型
                if (json.has("wx")) {
                    val single = GazeModel.fromJsonString(raw) ?: return@runCatching null
                    return@runCatching ZonedGazeModel(single, emptyMap())
                }
                val version = json.optInt("version", MODEL_VERSION)
                if (version != MODEL_VERSION) return@runCatching null
                val global = GazeModel.fromJsonString(json.getString("global"))
                    ?: return@runCatching null
                val zones = mutableMapOf<String, GazeModel>()
                val zoneObj = json.optJSONObject("zones")
                if (zoneObj != null) {
                    for (id in ZONE_ORDER) {
                        if (zoneObj.has(id)) {
                            GazeModel.fromJsonString(zoneObj.getString(id))
                                ?.let { zones[id] = it }
                        }
                    }
                }
                ZonedGazeModel(global, zones)
            }.getOrNull()
        }
    }
}
