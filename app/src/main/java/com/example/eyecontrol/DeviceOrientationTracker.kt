/*
 * Copyright (C) 2026 SuShengWen(ZhuZhu)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

    data class Orientation(val pitch: Double, val roll: Double, val yaw: Double)

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

    /** 目前平滑後的姿勢；尚無資料時回 null（呼叫端不得用 0.0 假裝有量到）。 */
    fun snapshot(): Orientation? {
        if (!available) return null
        val p = pitchRad
        val r = rollRad
        val y = yawRad
        if (p.isNaN() || r.isNaN() || y.isNaN()) return null
        return Orientation(p, r, y)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
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
