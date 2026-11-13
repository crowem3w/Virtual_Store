package org.example.test

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the phone's orientation (gyro + accelerometer, no magnetometer, so it
 * is not disturbed by indoor magnetic interference) and exposes it as a
 * camera rotation for the 3D scene.
 *
 * Scene convention: +Y up, camera looks along -Z, 1 unit = 1 metre.
 * Phone held upright in portrait, back camera pointing at the scene.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val r = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

    @Volatile var hasData = false
        private set

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(r) { SensorManager.getRotationMatrixFromVector(r, event.values) }
        hasData = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Raw device->world rotation (row-major 3x3, East/North/Up), as delivered by the sensor. */
    fun rawMatrix(out: FloatArray) {
        synchronized(r) { r.copyInto(out) }
    }

    /** Fills [out] (row-major 3x3) with the camera rotation in scene axes: Rcam = M * R. */
    fun cameraMatrix(out: FloatArray) {
        synchronized(r) {
            // M maps (East, North, Up) -> scene (x = East, y = Up, z = -North)
            out[0] = r[0]; out[1] = r[1]; out[2] = r[2]
            out[3] = r[6]; out[4] = r[7]; out[5] = r[8]
            out[6] = -r[3]; out[7] = -r[4]; out[8] = -r[5]
        }
    }
}

/** First-person state: position on the floor plus a yaw offset set by "Recenter". */
class Walker(startX: Float = 0f, startZ: Float = 1.5f) {
    var x = startX
    var z = startZ
    var needsRecenter = true
    private var yawOffset = 0f
    private var lastNanos = 0L
    private val m = FloatArray(9)

    /**
     * Returns the camera orientation and advances the position.
     * [stickX]/[stickY] are in -1..1 (right / forward).
     */
    fun update(now: Long, tracker: OrientationTracker, stickX: Float, stickY: Float, speed: Float = 1.4f): Quaternion {
        tracker.cameraMatrix(m)

        if (needsRecenter && tracker.hasData) {
            yawOffset = atan2(-m[2], m[8]) // heading of the camera's forward vector
            needsRecenter = false
        }

        // Rotate the camera about the world Y axis by yawOffset: m' = Ry(yawOffset) * m
        val c = cos(yawOffset); val s = sin(yawOffset)
        val r0 = floatArrayOf(m[0], m[1], m[2])
        val r2 = floatArrayOf(m[6], m[7], m[8])
        for (i in 0..2) {
            m[i] = c * r0[i] + s * r2[i]
            m[6 + i] = -s * r0[i] + c * r2[i]
        }

        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now

        // Walk relative to where the camera is facing, flattened onto the floor.
        var fx = -m[2]; var fz = -m[8]
        val len = sqrt(fx * fx + fz * fz)
        if (len > 1e-3f) {
            fx /= len; fz /= len
            val rx = -fz; val rz = fx
            x += (fx * stickY + rx * stickX) * speed * dt
            z += (fz * stickY + rz * stickX) * speed * dt
        }
        return toQuaternion(m)
    }

    private fun toQuaternion(m: FloatArray): Quaternion {
        val trace = m[0] + m[4] + m[8]
        return when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                Quaternion((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25f * s)
            }
            m[0] > m[4] && m[0] > m[8] -> {
                val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
                Quaternion(0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
            }
            m[4] > m[8] -> {
                val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
                Quaternion((m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
            }
            else -> {
                val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
                Quaternion((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s, (m[3] - m[1]) / s)
            }
        }
    }
}
