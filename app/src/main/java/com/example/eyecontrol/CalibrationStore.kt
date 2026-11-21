package com.example.eyecontrol

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 情境校正設定檔：每個情境（坐著／躺著／平放）各存一組完整的 9 點校正模型，
 * 使用時只吃目前情境的那組，不混用——不同姿勢下眼睛與螢幕的幾何關係不同，
 * 混在一起訓練反而更不準。沒校正過的情境不會 fallback 吃別組的模型。
 *
 * 舊版只有單一模型：首次讀取時遷移到預設情境（坐著），使用者不必重校。
 */
object CalibrationStore {

    const val PREFS_NAME = "calibration_profiles"
    const val KEY_ACTIVE_PROFILE = "active_profile"

    const val PROFILE_SITTING = "sitting"
    const val PROFILE_LYING = "lying"
    const val PROFILE_FLAT = "flat"

    data class Profile(val id: String, val name: String)

    val PROFILES = listOf(
        Profile(PROFILE_SITTING, "坐著"),
        Profile(PROFILE_LYING, "躺著"),
        Profile(PROFILE_FLAT, "平放"),
    )

    /** 校正紀錄：何時校正、留一法交叉驗證誤差（px，未知時為 null）。 */
    data class ProfileMeta(val calibratedAt: Long, val looErrorPx: Double?)

    private const val KEY_MIGRATED = "migrated_from_legacy"
    private const val KEY_MODEL_PREFIX = "model_"
    private const val KEY_META_PREFIX = "meta_"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun profileName(id: String): String =
        PROFILES.firstOrNull { it.id == id }?.name ?: id

    fun getActiveProfileId(context: Context): String {
        migrateIfNeeded(context)
        val id = prefs(context).getString(KEY_ACTIVE_PROFILE, PROFILE_SITTING)
        return if (PROFILES.any { it.id == id }) id!! else PROFILE_SITTING
    }

    fun setActiveProfileId(context: Context, id: String) {
        if (PROFILES.none { it.id == id }) return
        prefs(context).edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun loadModel(context: Context, id: String): GazeModel? {
        migrateIfNeeded(context)
        val raw = prefs(context).getString(KEY_MODEL_PREFIX + id, null) ?: return null
        return GazeModel.fromJsonString(raw)
    }

    fun exists(context: Context, id: String): Boolean = loadModel(context, id) != null

    fun getMeta(context: Context, id: String): ProfileMeta? {
        migrateIfNeeded(context)
        val raw = prefs(context).getString(KEY_META_PREFIX + id, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ProfileMeta(
                calibratedAt = json.optLong("calibratedAt", 0L),
                looErrorPx = if (json.isNull("looErrorPx")) {
                    null
                } else {
                    json.optDouble("looErrorPx", Double.NaN).takeIf { !it.isNaN() }
                },
            )
        }.getOrNull()
    }

    fun saveModel(context: Context, id: String, model: GazeModel, looErrorPx: Double?) {
        val meta = JSONObject().apply {
            put("calibratedAt", System.currentTimeMillis())
            if (looErrorPx != null && !looErrorPx.isNaN()) {
                put("looErrorPx", looErrorPx)
            } else {
                put("looErrorPx", JSONObject.NULL)
            }
        }
        prefs(context).edit()
            .putString(KEY_MODEL_PREFIX + id, model.toJsonString())
            .putString(KEY_META_PREFIX + id, meta.toString())
            .apply()
    }

    /**
     * 舊版單一模型遷移：只有第一次、且舊模型存在、預設情境還沒有模型時才搬；
     * 之後靠旗標不再重跑。舊存檔保留不刪。
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val legacy = GazeModel.load(context)
        if (legacy != null && p.getString(KEY_MODEL_PREFIX + PROFILE_SITTING, null) == null) {
            p.edit()
                .putString(KEY_MODEL_PREFIX + PROFILE_SITTING, legacy.toJsonString())
                .putString(
                    KEY_META_PREFIX + PROFILE_SITTING,
                    JSONObject().apply {
                        put("calibratedAt", 0L)
                        put("looErrorPx", JSONObject.NULL)
                    }.toString(),
                )
                .apply()
        }
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}
