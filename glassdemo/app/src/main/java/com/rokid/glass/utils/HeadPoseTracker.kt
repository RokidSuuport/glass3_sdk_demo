package com.rokid.glass.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
class HeadPoseTracker(
    context: Context,
    private val onYawChanged: (yawDeg: Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRv =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var yawOffset = 0f
    private var initialized = false
    private val TAG = "Sensor"

    fun start() {
        sensorManager.registerListener(
            this,
            gameRv,
            SensorManager.SENSOR_DELAY_GAME
        )
        val magnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetic != null) {
            // 有磁力计（指南针能力）
            Log.d(TAG, "-----有磁力计")
        } else {
            // 没有磁力计
            Log.d(TAG, "----没有磁力计")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        initialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(
            rotationMatrix,
            event.values
        )

        SensorManager.getOrientation(rotationMatrix, orientation)

        val yawRad = orientation[0]
        val yawDeg = Math.toDegrees(yawRad.toDouble()).toFloat()

        if (!initialized) {
            yawOffset = yawDeg
            initialized = true
        }

        val relativeYaw = yawDeg - yawOffset
        val normalized = ((relativeYaw + 180f) % 360f) - 180f

        onYawChanged(normalized)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}