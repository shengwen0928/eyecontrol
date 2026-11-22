package com.example.eyecontrol

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 環境光感測：讀 TYPE_LIGHT（lux），供條件感知漂移修正判斷「亮度換桶」。
 *
 * 亮度變化慢，用 SENSOR_DELAY_NORMAL 即可；指數平滑去抖。
 * 沒有光感測器的裝置就回報 unavailable，亮度條件視為未知、不參與變化偵測
 * （其他條件照常用）。寫法與 DeviceOrientationTracker 對齊。
 */
class LightSensorTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensor: Sensor? = null

    /** 感測器是否可用且已註冊。 */
    @Volatile
    var available = false
        private set

    @Volatile
    private var luxValue = Float.NaN

    fun start() {
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val s = sensor ?: return
        available = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        if (!available) sensor = null
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensor = null
        available = false
    }

    /** 目前平滑後的 lux；尚無資料或感測器不可用時回 null（呼叫端不得用 0 假裝量到）。 */
    fun snapshot(): Float? {
        if (!available) return null
        val v = luxValue
        return if (v.isFinite() && v >= 0f) v else null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val current = event.values.firstOrNull() ?: return
        if (!current.isFinite() || current < 0f) return
        val alpha = 0.2f
        luxValue = if (luxValue.isNaN()) current else luxValue + (current - luxValue) * alpha
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
