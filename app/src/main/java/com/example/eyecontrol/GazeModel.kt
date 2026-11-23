/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 視線 → 螢幕座標的線性回歸模型（兩組權重：x 與 y 各一）。
 * 校正時以嶺回歸（ridge regression）解正規方程訓練；特徵 25 維，
 * 9 點 × 數十樣本的資料量下解 26×26 線性系統（25 維特徵 + 1 維姿態補償項）即可，不需要任何 ML 框架。
 * 特徵 22–24 為連續光特徵（log lux 置中值及其與雙眼平均虹膜位置的交互項），
 * 由多光源校正資料學出；執行期每幀以當前 lux 重新計算並夾取到訓練跨度內
 * （見 predict 的 logLux 參數與設計文件 docs/light-robust-gaze-design.md）。
 *
 * 幾何修正（全部只吃「相對於校正時」的變化量，避免與臉轉向補償重複計算）：
 * 臉轉向——特徵 7/8 的帶符號差值 × kx/ky，訓練時聯合學出；
 * 裝置角度——旋轉向量的 pitch/roll 與校正時基準的差值，一階幾何修正（轉回＋拉伸）；
 * 臉遠近——特徵 6（iod，臉距倒數的代理）的比值，繞螢幕中心等比縮放。
 * 裝置與臉距修正不靠訓練資料學係數（校正時裝置不動、臉距固定，學不出來），
 * 只靠校正基準＋幾何關係；舊模型沒有基準時修正自動關閉，重校後才生效。
 * 光特徵例外：光以絕對 log 尺度進模型，不取與校正時的差值——光線連續變化，
 * 模型必須在每個光線下都成立，不得以單一校正光線為基準。
 */
class GazeModel(
    private val wx: DoubleArray,
    private val wy: DoubleArray,
    var basePitch: Double = 0.0,
    var baseYaw: Double = 0.0,
    var kx: Double = 1.6,
    var ky: Double = 1.3,
    /** 校正時裝置姿勢基準（弧度）；null = 舊模型、無基準，陀螺儀修正關閉。 */
    var deviceBasePitch: Double? = null,
    var deviceBaseRoll: Double? = null,
    /** 校正時臉距基準（特徵 6 iod 的平均）；<= 0 = 舊模型、無基準，臉距修正關閉。 */
    var baseIod: Double = 0.0,
    /** 訓練集 log10(lux) 的實際跨度：執行期 lux 夾取到此範圍內再進模型，範圍外
     * 不丟光特徵、取邊界效應（見 predict）。預設為絕對範圍，僅防禦性用途。 */
    var lightMinLog: Double = GazeFeatureExtractor.LOG_LUX_MIN,
    var lightMaxLog: Double = GazeFeatureExtractor.LOG_LUX_MAX,
) {

    /**
     * 預測螢幕座標（像素）：先做幾何頭部晃動補償，再依序做裝置角度修正與臉距縮放。
     * devicePitch / deviceRoll 為 NaN（無感測器資料）或開關關閉時，對應修正自動跳過。
     * headPoseCompensation=false 時跳過 kx/ky 幾何項、只輸出 ridge 映射（對照實驗用；
     * 預設 true，現有呼叫端行為不變）。
     * logLux：當前 log10(lux)（連續光輸入）。有限時以訓練跨度夾取後重算光特徵
     * f[22..24] 再預測——範圍外取邊界效應，絕不丟掉光特徵；NaN 時（評估器路徑、
     * 無感測器資料）沿用特徵陣列內建的光值。呼叫端傳入的陣列絕不被修改。
     */
    fun predict(
        f: DoubleArray,
        screenW: Int = 1080,
        screenH: Int = 2400,
        devicePitch: Double = Double.NaN,
        deviceRoll: Double = Double.NaN,
        gyroCompensation: Boolean = true,
        distanceCompensation: Boolean = true,
        headPoseCompensation: Boolean = true,
        logLux: Double = Double.NaN,
    ): Pair<Double, Double> {
        // 特徵不足時直接失敗，不要讓 f[7]/f[8] 拋出難懂的越界錯誤
        require(f.size >= 9) { "特徵維度不足：需要至少 9 維，實際 ${f.size} 維" }
        // 連續光輸入：以訓練跨度夾取後重算光特徵，寫入複製的陣列；
        // 特徵不足 25 維時無光欄位可寫，沿用原陣列（舊陣列相容路徑）。
        val fUse = if (logLux.isFinite() && f.size >= GazeFeatureExtractor.DIM) {
            val copy = f.copyOf()
            val clamped = logLux.coerceIn(lightMinLog, lightMaxLog)
            val light = GazeFeatureExtractor.lightFeatures(clamped, f[13], f[14])
            val start = GazeFeatureExtractor.LIGHT_FEATURE_START
            copy[start] = light[0]
            copy[start + 1] = light[1]
            copy[start + 2] = light[2]
            copy
        } else {
            f
        }
        var px = 0.0
        var py = 0.0
        for (i in fUse.indices) {
            px += wx[i] * fUse[i]
            py += wy[i] * fUse[i]
        }

        // 頭部姿態補償 (Head pose compensation)：headPoseCompensation=false 時跳過，
        // 只輸出 ridge 映射（對照實驗用，見 docs/head-pose-compensation-experiment.md）。
        val curPitch = fUse[7]
        val curYaw = fUse[8]
        val deltaPitch = curPitch - basePitch
        val deltaYaw = curYaw - baseYaw

        // 幾何補償：使用自適應的 kx 與 ky
        var outX = px
        var outY = py
        if (headPoseCompensation) {
            outX += deltaYaw * screenW * kx
            outY -= deltaPitch * screenH * ky
        }

        // 裝置角度修正：只吃與校正基準的差值。
        // 注意：特徵 7/8 是相機影像座標系的量測，手機轉動時它們也會變，kx/ky 項可能已把
        // 同一裝置轉動算過一次——pitch 方向兩邊有重複計算的可能，未經實機對照驗證前預設關閉
        // （見 GazeSettings.gyroCompensation），roll 方向因特徵已做 roll 旋轉而不受影響。
        val baseP = deviceBasePitch
        val baseR = deviceBaseRoll
        if (gyroCompensation && baseP != null && baseR != null &&
            devicePitch.isFinite() && deviceRoll.isFinite()
        ) {
            val corrected = correctForDeviceTilt(
                outX, outY, screenW, screenH, devicePitch, deviceRoll, baseP, baseR,
            )
            outX = corrected.first
            outY = corrected.second
        }

        // 臉距縮放：iod 與距離成反比；臉靠近（iod 變大）時同一眼球轉角覆蓋的螢幕距離變小，
        // 故繞螢幕中心等比縮小偏移量；臉遠離時反之。只吃與校正基準的比值。
        if (distanceCompensation && baseIod > 0.0 && fUse.size > 6 && fUse[6] > 0.0) {
            val scale = (baseIod / fUse[6]).coerceIn(MIN_DISTANCE_SCALE, MAX_DISTANCE_SCALE)
            val cx = screenW / 2.0
            val cy = screenH / 2.0
            outX = cx + (outX - cx) * scale
            outY = cy + (outY - cy) * scale
        }

        return outX to outY
    }

    /** 模型序列化：給 CalibrationStore 做情境存檔用；舊版 save()/load() 也走這裡。 */
    fun toJsonString(): String {
        val json = JSONObject().apply {
            put("wx", JSONArray(wx.toList()))
            put("wy", JSONArray(wy.toList()))
            put("basePitch", basePitch)
            put("baseYaw", baseYaw)
            put("kx", kx)
            put("ky", ky)
            put("baseIod", baseIod)
            put("lightMinLog", lightMinLog)
            put("lightMaxLog", lightMaxLog)
            deviceBasePitch?.let { put("deviceBasePitch", it) }
            deviceBaseRoll?.let { put("deviceBaseRoll", it) }
            put("version", MODEL_VERSION)
        }
        return json.toString()
    }

    fun save(context: Context) {
        prefs(context).edit().putString(KEY, toJsonString()).apply()
    }

    companion object {
        private const val PREFS = "gaze_model"
        private const val KEY = "model_json"

        /**
         * 模型存檔格式版本。特徵定義或存檔欄位改變時遞增；
         * 舊版存檔（無 version 欄位）與版本不符者皆視為未校正（須重校，避免靜默誤用舊格式模型）。
         * v2：kx/ky 改以正負範圍學習（符號由校正資料決定），v1 模型一律重校。
         * v3：特徵 22 維 → 25 維（新增連續光特徵 f[22..24]）＋光跨度欄位，
         * v2 模型一律重校（授權的契約變更，見 docs/light-robust-gaze-design.md §6）。
         */
        private const val MODEL_VERSION = 3

        /**
         * 版本相容判定（純函數，單元測試不碰 org.json stub 也能驗）：
         * 只有版本號與權重維度同時符合才算可用模型。
         */
        fun isCompatibleVersion(version: Int, weightSize: Int): Boolean =
            version == MODEL_VERSION && weightSize == GazeFeatureExtractor.DIM

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun fromJsonString(raw: String): GazeModel? {
            return runCatching {
                val json = JSONObject(raw)
                fun arr(k: String): DoubleArray {
                    val a = json.getJSONArray(k)
                    return DoubleArray(a.length()) { a.getDouble(it) }
                }
                val wxArr = arr("wx")
                val wyArr = arr("wy")
                val basePitchVal = json.optDouble("basePitch", 0.0)
                val baseYawVal = json.optDouble("baseYaw", 0.0)
                val kxVal = json.optDouble("kx", 1.6)
                val kyVal = json.optDouble("ky", 1.3)
                val version = json.optInt("version", 0)
                // 幾何基準為選填：舊存檔沒有這幾欄時，修正自動關閉（不拿 0.0 假裝量過）
                val deviceBasePitchVal = json.optDouble("deviceBasePitch", Double.NaN)
                    .takeIf { it.isFinite() }
                val deviceBaseRollVal = json.optDouble("deviceBaseRoll", Double.NaN)
                    .takeIf { it.isFinite() }
                val baseIodVal = json.optDouble("baseIod", 0.0)
                val lightMinVal = json.optDouble(
                    "lightMinLog", GazeFeatureExtractor.LOG_LUX_MIN,
                )
                val lightMaxVal = json.optDouble(
                    "lightMaxLog", GazeFeatureExtractor.LOG_LUX_MAX,
                )
                val m = GazeModel(
                    wxArr, wyArr, basePitchVal, baseYawVal, kxVal, kyVal,
                    deviceBasePitchVal, deviceBaseRollVal, baseIodVal,
                    lightMinVal, lightMaxVal,
                )
                // 版本不符或特徵維度不符，視為未校正（避免靜默誤用舊格式模型）
                if (!isCompatibleVersion(version, m.wx.size)) null else m
            }.getOrNull()
        }

        fun load(context: Context): GazeModel? {
            val raw = prefs(context).getString(KEY, null) ?: return null
            return fromJsonString(raw)
        }

        fun exists(context: Context) = load(context) != null

        /**
         * 嶺回歸訓練：w = (XᵀX + λI)⁻¹ Xᵀy，以高斯消去法解線性系統。
         * @param features 每筆樣本的特徵向量（維度 = GazeFeatureExtractor.DIM）
         * @param targets  對應的螢幕座標（像素）
         * @param screenW  螢幕寬（像素）；呼叫端已知時傳入，否則由目標座標最大值估算
         * @param screenH  螢幕高（像素）；呼叫端已知時傳入，否則由目標座標最大值估算
         * @param lightLogLux 與 features 平行的每筆 log10(lux)（NaN = 當時無感測器資料）；
         * 傳入時用它算光跨度（精確），不傳時由特徵 f[22] 還原。
         */
        fun train(
            features: List<DoubleArray>,
            targets: List<Pair<Float, Float>>,
            lambda: Double = 5e-4,
            screenW: Double? = null,
            screenH: Double? = null,
            deviceBasePitch: Double? = null,
            deviceBaseRoll: Double? = null,
            lightLogLux: List<Double>? = null,
        ): GazeModel {
            require(features.isNotEmpty() && features.size == targets.size)
            val d = features[0].size // 22

            var pitchSum = 0.0
            var yawSum = 0.0
            var iodSum = 0.0
            for (n in features.indices) {
                pitchSum += features[n][7]
                yawSum += features[n][8]
                iodSum += features[n][6]
            }
            val basePitch = pitchSum / features.size
            val baseYaw = yawSum / features.size
            val baseIod = iodSum / features.size

            // 螢幕維度：呼叫端已知時直接傳入最準（校正頁用實際 view 尺寸）；
            // 未傳入時沿用舊估算（CalibrationActivity 使用 8% 邊距，最大座標約在 92%）
            val sw = screenW ?: run {
                val maxX = targets.maxOfOrNull { it.first } ?: 1080f
                if (maxX > 0) (maxX / 0.92f).toDouble() else 1080.0
            }
            val sh = screenH ?: run {
                val maxY = targets.maxOfOrNull { it.second } ?: 2400f
                if (maxY > 0) (maxY / 0.92f).toDouble() else 2400.0
            }

            // 擴展特徵向量維度，將姿勢補償項放入特徵矩陣中，利用嶺回歸同時優化 w 與 k
            val extD = d + 1

            // 1. 訓練 X 軸 (同時求得 wx 與 kx)
            val ataX = Array(extD) { DoubleArray(extD) }
            val atx = DoubleArray(extD)
            for (n in features.indices) {
                val f = features[n]
                val (tx, _) = targets[n]
                val curYaw = f[8]
                val deltaYaw = curYaw - baseYaw
                val compTermX = deltaYaw * sw

                val fExt = DoubleArray(extD)
                System.arraycopy(f, 0, fExt, 0, d)
                fExt[d] = compTermX

                for (i in 0 until extD) {
                    for (j in 0 until extD) ataX[i][j] += fExt[i] * fExt[j]
                    atx[i] += fExt[i] * tx
                }
            }
            for (i in 0 until extD) ataX[i][i] += lambda
            val solX = solve(ataX, atx)
            val wx = solX.copyOfRange(0, d)
            // 補償係數的符號由校正資料決定，不預設方向：特徵 7/8 是相機影像座標系的量測，
            // pitch 增大對應螢幕上的哪個方向從未在實機上驗證過；舊版強制限制在正值 0.5–3.0，
            // 資料要負值時會被夾成 +0.5，垂直補償整個算反、游標被壓向中下方（v0.4.1 修正）。
            val kx = solX[d].coerceIn(-3.0, 3.0) // 限制補償範圍以確保穩定

            // 2. 訓練 Y 軸 (同時求得 wy 與 ky)
            val ataY = Array(extD) { DoubleArray(extD) }
            val aty = DoubleArray(extD)
            for (n in features.indices) {
                val f = features[n]
                val (_, ty) = targets[n]
                val curPitch = f[7]
                val deltaPitch = curPitch - basePitch
                val compTermY = -deltaPitch * sh

                val fExt = DoubleArray(extD)
                System.arraycopy(f, 0, fExt, 0, d)
                fExt[d] = compTermY

                for (i in 0 until extD) {
                    for (j in 0 until extD) ataY[i][j] += fExt[i] * fExt[j]
                    aty[i] += fExt[i] * ty
                }
            }
            for (i in 0 until extD) ataY[i][i] += lambda
            val solY = solve(ataY, aty)
            val wy = solY.copyOfRange(0, d)
            // 同 X 軸：ky 的符號由資料決定（見上）；強制正值會讓頭部上仰時游標反被往下推，
            // 看上面要把眼球往上翻到底才夠得到（v0.4.1 修正）。
            val ky = solY[d].coerceIn(-3.0, 3.0) // 限制補償範圍以確保穩定

            val lightSpan = computeLightSpan(features, lightLogLux)
            return GazeModel(
                wx, wy, basePitch, baseYaw, kx, ky,
                deviceBasePitch, deviceBaseRoll, baseIod,
                lightSpan.first, lightSpan.second,
            )
        }

        /**
         * 訓練集光跨度（log10 lux 的最小–最大值）：呼叫端有平行 lux 記錄時
         * 以它為準（NaN 樣本不計）；否則由特徵 f[22] 還原
         *（f[22] = logLux − 中心值）；特徵不足 25 維時無光資訊，取絕對範圍。
         * 跨度退化（最小＝最大，例如全程同一光線或全程無感測器）時，
         * 執行期 lux 夾取到該單一值，光特徵恆為訓練時的值——行為一致，
         * 由校正報告誠實列出跨度，不靜默假裝跨過光線範圍。
         */
        private fun computeLightSpan(
            features: List<DoubleArray>,
            lightLogLux: List<Double>?,
        ): Pair<Double, Double> {
            val finite = lightLogLux
                ?.filterIndexed { i, v -> i < features.size && v.isFinite() }
                ?: emptyList()
            if (finite.isNotEmpty()) {
                return finite.min() to finite.max()
            }
            val start = GazeFeatureExtractor.LIGHT_FEATURE_START
            if (features.isNotEmpty() && features[0].size > start) {
                val logs = features.map { it[start] + GazeFeatureExtractor.LIGHT_CENTER_LOG }
                return logs.min() to logs.max()
            }
            return GazeFeatureExtractor.LOG_LUX_MIN to GazeFeatureExtractor.LOG_LUX_MAX
        }

        /** 裝置角度修正的變化量上限（弧度）：超過視為劇烈晃動，不再放大修正。 */
        private const val MAX_DEVICE_ROLL_DELTA = 0.6
        private const val MAX_DEVICE_PITCH_DELTA = 0.5

        /** 臉距縮放比值上限：臉貼很近或離很遠時，不讓預測點被縮放出合理範圍。 */
        private const val MIN_DISTANCE_SCALE = 0.7
        private const val MAX_DISTANCE_SCALE = 1.4

        /**
         * 裝置角度一階幾何修正（純函數，方便以後單元測試）：
         * 裝置相對校正姿勢轉了多少，預測點就繞螢幕中心轉回來多少；
         * 前傾／後仰再沿垂直方向以 1/cos 拉伸（透視一階近似，支點取螢幕中心）。
         * 旋轉方向的符號約定需實機驗證（錯了改一行）；覺得修正有害時關設定裡的開關即可。
         * 另注意：特徵 7/8 分不清頭動與手機動，pitch 方向可能與 kx/ky 頭部補償重複計算，
         * 故此修正預設關閉，須實機開／關對照確認有效再開。
         */
        fun correctForDeviceTilt(
            x: Double,
            y: Double,
            screenW: Int,
            screenH: Int,
            devicePitch: Double,
            deviceRoll: Double,
            basePitch: Double,
            baseRoll: Double,
        ): Pair<Double, Double> {
            val dRoll = (deviceRoll - baseRoll).coerceIn(-MAX_DEVICE_ROLL_DELTA, MAX_DEVICE_ROLL_DELTA)
            val dPitch = (devicePitch - basePitch).coerceIn(-MAX_DEVICE_PITCH_DELTA, MAX_DEVICE_PITCH_DELTA)
            if (dRoll == 0.0 && dPitch == 0.0) return x to y
            val cx = screenW / 2.0
            val cy = screenH / 2.0
            val cos = kotlin.math.cos(dRoll)
            val sin = kotlin.math.sin(dRoll)
            val dx = x - cx
            val dy = y - cy
            val rx = dx * cos + dy * sin
            val ry = -dx * sin + dy * cos
            val scaleY = 1.0 / kotlin.math.cos(dPitch)
            return (cx + rx) to (cy + ry * scaleY)
        }

        /** 高斯消去法（含部分主元選取）解 A·w = b。A 會被複製，不改動原矩陣。 */
        private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
            val n = bIn.size
            val a = Array(n) { aIn[it].copyOf() }
            val b = bIn.copyOf()

            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) {
                    if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
                }
                if (pivot != col) {
                    val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                    val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
                }
                val p = a[col][col]
                if (kotlin.math.abs(p) < 1e-12) continue // 退化行，交給正則項兜底
                for (r in col + 1 until n) {
                    val factor = a[r][col] / p
                    for (c in col until n) a[r][c] -= factor * a[col][c]
                    b[r] -= factor * b[col]
                }
            }
            val w = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var s = b[row]
                for (c in row + 1 until n) s -= a[row][c] * w[c]
                w[row] = if (kotlin.math.abs(a[row][row]) < 1e-12) 0.0 else s / a[row][row]
            }
            return w
        }
    }
}
