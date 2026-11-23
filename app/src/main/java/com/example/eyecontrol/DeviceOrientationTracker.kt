/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 裝置姿勢追蹤：讀融合陀螺儀的旋轉向量（Rotation Vector），換算成裝置相對重力方向的
 * pitch / roll / yaw（弧度），供視線預測做「相對於校正時姿勢」的幾何修正。
 *
 * 注意：不用生陀螺儀角速度——積分會有漂移。旋轉向量是陀螺儀＋加速度計融合、
 * 帶重力參考的絕對姿勢，不漂移；yaw 會慢漂，但修正只用 pitch / roll，不受影響。
 * 優先用 TYPE_GAME_ROTATION_VECTOR（不吃磁力計，不受磁場干擾），沒有才退回
 * TYPE_ROTATION_VECTOR；兩者都沒有就回報 unavailable，樣本不帶角度、修正自動關閉。
 */
class DeviceOrientationTracker(context: Context) : SensorEventListener {

    data class Orientation(
        val pitch: Double,
        val roll: Double,
        val yaw: Double,
        /** 未經平滑的原始 orientation（弧度），與 pitch／roll 同一時刻的感測器原始值。 */
        val rawPitch: Double,
        val rawRoll: Double,
        val rawYaw: Double,
        /** 旋轉向量原始分量（x, y, z, w）；w 缺失時為 NaN。 */
        val rvX: Double,
        val rvY: Double,
        val rvZ: Double,
        val rvW: Double,
    )

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensor: Sensor? = null

    /** 感測器是否可用且已註冊。 */
    @Volatile
    var available = false
        private set

    @Volatile
    private var pitchRad = Double.NaN

    @Volatile
    private var rollRad = Double.NaN

    @Volatile
    private var yawRad = Double.NaN

    /** 未經平滑的原始 orientation 與旋轉向量（診斷紀錄用，NaN = 尚無資料）。 */
    @Volatile
    private var rawPitchRad = Double.NaN

    @Volatile
    private var rawRollRad = Double.NaN

    @Volatile
    private var rawYawRad = Double.NaN

    @Volatile
    private var rvX = Double.NaN

    @Volatile
    private var rvY = Double.NaN

    @Volatile
    private var rvZ = Double.NaN

    @Volatile
    private var rvW = Double.NaN

    fun start() {
        val sm = sensorManager
        sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val s = sensor ?: return
        available = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        if (!available) sensor = null
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensor = null
        available = false
    }

    /** 目前平滑後的姿勢，附同一時刻的原始值；尚無資料時回 null（呼叫端不得用 0.0 假裝有量到）。 */
    fun snapshot(): Orientation? {
        if (!available) return null
        val p = pitchRad
        val r = rollRad
        val y = yawRad
        if (p.isNaN() || r.isNaN() || y.isNaN()) return null
        return Orientation(
            pitch = p, roll = r, yaw = y,
            rawPitch = rawPitchRad, rawRoll = rawRollRad, rawYaw = rawYawRad,
            rvX = rvX, rvY = rvY, rvZ = rvZ, rvW = rvW,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        // 原始值先存（診斷紀錄用）：未平滑的 orientation 與旋轉向量分量，事後才能
        // 分清是手機真動了、還是平滑／跨 ±π 換算造成的假跳變。
        rawPitchRad = orientation[1].toDouble()
        rawRollRad = orientation[2].toDouble()
        rawYawRad = orientation[0].toDouble()
        rvX = event.values.getOrNull(0)?.toDouble() ?: Double.NaN
        rvY = event.values.getOrNull(1)?.toDouble() ?: Double.NaN
        rvZ = event.values.getOrNull(2)?.toDouble() ?: Double.NaN
        rvW = event.values.getOrNull(3)?.toDouble() ?: Double.NaN
        // orientation[0]=azimuth(yaw)，[1]=pitch，[2]=roll，單位弧度。
        // 只比「與校正時的差值」，絕對座標系的定義不影響修正，兩端同一條路徑即可。
        // 指數平滑去抖：角速度變化快時跟得上，靜止時不抖。
        val alpha = 0.35
        pitchRad = smooth(pitchRad, orientation[1].toDouble(), alpha)
        rollRad = smooth(rollRad, orientation[2].toDouble(), alpha)
        yawRad = smooth(yawRad, orientation[0].toDouble(), alpha)
    }

    private fun smooth(prev: Double, current: Double, alpha: Double): Double =
        if (prev.isNaN()) current else prev + (current - prev) * alpha

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
