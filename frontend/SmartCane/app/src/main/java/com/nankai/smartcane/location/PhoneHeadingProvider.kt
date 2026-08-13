package com.nankai.smartcane.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

/** Provides true phone heading while stationary, unlike GPS bearing. */
object PhoneHeadingProvider : SensorEventListener {
    private val _headingDeg = MutableStateFlow<Float?>(null)
    val headingDeg: StateFlow<Float?> = _headingDeg.asStateFlow()

    @Volatile
    private var sensorManager: SensorManager? = null
    private var windowManager: WindowManager? = null

    @Synchronized
    fun start(context: Context) {
        if (sensorManager != null) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) {
            sensorManager = manager
        }
    }

    fun latestHeadingDeg(): Float? = _headingDeg.value

    override fun onSensorChanged(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        val adjustedMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val (axisX, axisY) = when (windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, adjustedMatrix)
        val azimuthRadians = SensorManager.getOrientation(adjustedMatrix, FloatArray(3))[0]
        val normalized = ((Math.toDegrees(azimuthRadians.toDouble()) + 360.0) % 360.0).toFloat()
        // One-degree quantization prevents unnecessary map recompositions.
        _headingDeg.value = round(normalized)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
