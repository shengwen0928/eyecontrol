package com.example.eyecontrol

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 矯正歷史紀錄：條件感知漂移修正的記憶。
 *
 * 每次單點快速校正都會記下一筆：當時的條件向量（亮度桶、裝置
 * pitch/roll、臉距 iod、眼區反光指標）＋量到的游標偏移 (ox, oy)。之後條件變化時，先查歷史：
 * 以前遇過相近條件，就以反距離加權內插出偏移直接套用、不跳提醒；覆蓋的條件
 * 越多，提醒越少、越準。完整重校會清空該情境的歷史（舊偏移配新模型沒有意義）。
 *
 * 每情境各自記錄，存在 SharedPreferences（JSON），保留最近 50 筆。
 * 全部流程都不要求動手；關掉 GazeSettings.conditionCorrection 時不讀不寫。
 */
object ConditionHistory {

    // ---- 閾值：全部集中在此，調校只改這裡 ----

    /** 裝置 pitch/roll 相對上次矯正時的變化量（弧度）：超過就視為「換角度」。約 8.6 度。 */
    const val PITCH_ROLL_CHANGE_RAD = 0.15

    /** 臉距（iod）相對變化比例：超過就視為「換距離」。 */
    const val IOD_CHANGE_RATIO = 0.20

    /** 亮度分桶邊界（lux）：跨桶就視為「換亮度」。室內外常見值：暗室 <10、夜間室內 30–150、日間室內 200–800、窗邊／戶外 >1500。 */
    const val LUX_BUCKET_DARK_MAX = 30f
    const val LUX_BUCKET_DIM_MAX = 200f
    const val LUX_BUCKET_BRIGHT_MAX = 1500f

    /** 條件空間鄰近距離上限：低於此值才算「以前遇過的條件」，可內插。 */
    const val NEIGHBOR_MAX_DISTANCE = 1.0

    /** 每情境保留的最近紀錄筆數。 */
    const val MAX_RECORDS_PER_PROFILE = 50

    /** 反距離加權的平滑項：條件完全相同時不除以零，權重取大值但有限。 */
    const val IDW_EPSILON = 0.1

    /** 內插最多取用的鄰近紀錄筆數（取最近的幾筆，避免遠處舊紀錄拉偏）。 */
    const val IDW_MAX_NEIGHBORS = 5

    /** 套用偏移的單軸上限（像素）：超過就不是漂移、該重校了。 */
    const val MAX_OFFSET_PX = 250f

    /**
     * 眼鏡／反光指標變化量：校正時記錄的眼區反光比例（specularRatio）與目前值
     * 相差超過此值，就視為「眼鏡狀態可能變了」（戴上／拿下眼鏡、反光型態大變）。
     * 這只是啟發式代理指標，不是可靠的眼鏡偵測——只用來觸發既有的
     * 「顯著變化 → 提醒快速校正／歷史內插」流程，不得據此宣稱偵測到眼鏡。
     */
    const val GLASSES_HINT_CHANGE = 0.25

    /** 亮度桶：-1 = 未知（無感測器／尚無資料），0 暗、1 偏暗、2 明亮、3 日光。 */
    fun luxBucket(lux: Float): Int {
        if (!lux.isFinite() || lux < 0f) return -1
        return when {
            lux < LUX_BUCKET_DARK_MAX -> 0
            lux < LUX_BUCKET_DIM_MAX -> 1
            lux < LUX_BUCKET_BRIGHT_MAX -> 2
            else -> 3
        }
    }

    /** 一次矯正當下的條件。pitch/roll 為弧度（NaN = 當時無感測器資料）；iod 為特徵 6（NaN = 未知）。
     * glassesHint 為眼區反光比例（NaN = 當時無影像度量）；只做變化觸發用，不是眼鏡偵測。
     * 新增欄位一律放最後並給預設值，舊存檔讀不到時以 NaN 表示未知、不參與判定。 */
    data class Condition(
        val luxBucket: Int,
        val pitch: Double,
        val roll: Double,
        val iod: Double,
        val glassesHint: Double = Double.NaN,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("luxBucket", luxBucket)
            putDoubleOrNull("pitch", pitch)
            putDoubleOrNull("roll", roll)
            putDoubleOrNull("iod", iod)
            putDoubleOrNull("glassesHint", glassesHint)
        }

        companion object {
            fun fromJson(json: JSONObject): Condition? = runCatching {
                Condition(
                    luxBucket = json.optInt("luxBucket", -1),
                    pitch = json.getDoubleOrNaN("pitch"),
                    roll = json.getDoubleOrNaN("roll"),
                    iod = json.getDoubleOrNaN("iod"),
                    glassesHint = json.getDoubleOrNaN("glassesHint"),
                )
            }.getOrNull()
        }
    }

    /** 一筆矯正紀錄：條件＋量到的偏移＋時間戳（時間戳只供未來除錯／排序，不參與內插）。 */
    data class Record(
        val condition: Condition,
        val offsetX: Float,
        val offsetY: Float,
        val timestampMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("condition", condition.toJson())
            put("offsetX", offsetX.toDouble())
            put("offsetY", offsetY.toDouble())
            put("timestampMs", timestampMs)
        }

        companion object {
            fun fromJson(json: JSONObject): Record? = runCatching {
                val cond = Condition.fromJson(json.getJSONObject("condition")) ?: return@runCatching null
                Record(
                    condition = cond,
                    offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                    offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                    timestampMs = json.optLong("timestampMs", 0L),
                )
            }.getOrNull()
        }
    }

    /**
     * 目前條件相對上次矯正基準是否變化顯著。任一分量未知（NaN／-1）時，該分量
     * 不參與判定——寧可少提醒，也不在沒量到的條件上亂跳提醒。
     */
    fun hasSignificantChange(reference: Condition, current: Condition): Boolean {
        if (reference.luxBucket >= 0 && current.luxBucket >= 0 &&
            reference.luxBucket != current.luxBucket
        ) return true
        if (reference.pitch.isFinite() && current.pitch.isFinite() &&
            abs(current.pitch - reference.pitch) >= PITCH_ROLL_CHANGE_RAD
        ) return true
        if (reference.roll.isFinite() && current.roll.isFinite() &&
            abs(current.roll - reference.roll) >= PITCH_ROLL_CHANGE_RAD
        ) return true
        if (reference.iod > 0.0 && current.iod > 0.0 &&
            abs(current.iod - reference.iod) / reference.iod >= IOD_CHANGE_RATIO
        ) return true
        if (reference.glassesHint.isFinite() && current.glassesHint.isFinite() &&
            abs(current.glassesHint - reference.glassesHint) >= GLASSES_HINT_CHANGE
        ) return true
        return false
    }

    /**
     * 條件空間距離：各分量以「變化閾值」為單位歸一化後相加。
     * 亮度換桶計 1.0（等於一次完整的角度閾值變化）；pitch／roll 每 0.15 弧度計 1.0；
     * iod 每 20% 相對變化計 1.0。未知分量不計。
     */
    fun conditionDistance(a: Condition, b: Condition): Double {
        var d = 0.0
        if (a.luxBucket >= 0 && b.luxBucket >= 0 && a.luxBucket != b.luxBucket) d += 1.0
        if (a.pitch.isFinite() && b.pitch.isFinite()) {
            d += abs(a.pitch - b.pitch) / PITCH_ROLL_CHANGE_RAD
        }
        if (a.roll.isFinite() && b.roll.isFinite()) {
            d += abs(a.roll - b.roll) / PITCH_ROLL_CHANGE_RAD
        }
        if (a.iod > 0.0 && b.iod > 0.0) {
            val mean = (a.iod + b.iod) / 2.0
            d += abs(a.iod - b.iod) / (IOD_CHANGE_RATIO * mean)
        }
        if (a.glassesHint.isFinite() && b.glassesHint.isFinite()) {
            d += abs(a.glassesHint - b.glassesHint) / GLASSES_HINT_CHANGE
        }
        return d
    }

    /**
     * 反距離加權內插：取距離 < [NEIGHBOR_MAX_DISTANCE] 的最近幾筆紀錄，
     * 權重 1/(d + eps)，加權平均出偏移。沒有鄰近紀錄回 null（該跳提醒）。
     * 回傳偏移已夾在 ±[MAX_OFFSET_PX] 內。
     */
    fun interpolate(records: List<Record>, current: Condition): Pair<Float, Float>? {
        val near = records
            .map { it to conditionDistance(current, it.condition) }
            .filter { it.second < NEIGHBOR_MAX_DISTANCE }
            .sortedBy { it.second }
            .take(IDW_MAX_NEIGHBORS)
        if (near.isEmpty()) return null
        var weightSum = 0.0
        var xSum = 0.0
        var ySum = 0.0
        for ((record, d) in near) {
            val w = 1.0 / (d + IDW_EPSILON)
            weightSum += w
            xSum += w * record.offsetX
            ySum += w * record.offsetY
        }
        return (
            (xSum / weightSum).toFloat().coerceIn(-MAX_OFFSET_PX, MAX_OFFSET_PX)
            to
            (ySum / weightSum).toFloat().coerceIn(-MAX_OFFSET_PX, MAX_OFFSET_PX)
            )
    }

    // ---- 持久化（SharedPreferences + JSON，存檔損毀時回空，不炸開呼叫端） ----

    private const val PREFS_NAME = "condition_history"
    private const val KEY_RECORDS_PREFIX = "records_"
    private const val KEY_REFERENCE_PREFIX = "reference_"

    fun loadRecords(context: Context, profileId: String): List<Record> {
        val raw = prefs(context).getString(KEY_RECORDS_PREFIX + profileId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Record.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 附加一筆並保留最新 50 筆；偏移先夾在 ±上限內再存（內插結果與量測結果一致）。 */
    fun addRecord(context: Context, profileId: String, record: Record) {
        val clamped = record.copy(
            offsetX = record.offsetX.coerceIn(-MAX_OFFSET_PX, MAX_OFFSET_PX),
            offsetY = record.offsetY.coerceIn(-MAX_OFFSET_PX, MAX_OFFSET_PX),
        )
        val kept = (loadRecords(context, profileId) + clamped).takeLast(MAX_RECORDS_PER_PROFILE)
        val arr = JSONArray()
        kept.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RECORDS_PREFIX + profileId, arr.toString()).apply()
    }

    /** 完整重校時呼叫：清空該情境的歷史紀錄與基準條件（舊偏移配新模型沒有意義）。 */
    fun clearHistory(context: Context, profileId: String) {
        prefs(context).edit()
            .remove(KEY_RECORDS_PREFIX + profileId)
            .remove(KEY_REFERENCE_PREFIX + profileId)
            .apply()
    }

    fun loadReference(context: Context, profileId: String): Condition? {
        val raw = prefs(context).getString(KEY_REFERENCE_PREFIX + profileId, null) ?: return null
        return runCatching { Condition.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun saveReference(context: Context, profileId: String, condition: Condition) {
        prefs(context).edit()
            .putString(KEY_REFERENCE_PREFIX + profileId, condition.toJson().toString())
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** org.json 不接受非有限 double：寫入時改存 NULL，讀回時以 NaN 表示「未知」。 */
    private fun JSONObject.putDoubleOrNull(key: String, value: Double) {
        if (value.isFinite()) put(key, value) else put(key, JSONObject.NULL)
    }

    private fun JSONObject.getDoubleOrNaN(key: String): Double =
        if (isNull(key)) Double.NaN else optDouble(key, Double.NaN)
}
