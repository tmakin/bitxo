package uk.neatlines.stompbox.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import uk.neatlines.stompbox.detect.AccelTapDetector
import uk.neatlines.stompbox.detect.TapEvent

/**
 * Streams the accelerometer at the fastest rate the hardware allows (several hundred Hz on a
 * Pixel 8, with HIGH_SAMPLING_RATE_SENSORS declared), with batching disabled, on a dedicated
 * high-priority thread. [onTap] is called on that thread.
 */
class AccelTapSource(
    context: Context,
    val detector: AccelTapDetector,
    private val onTap: (TapEvent) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null

    @Volatile var sampleRateHz = 0f
        private set
    @Volatile var sensorLatencyMs = 0f
        private set

    private var lastTs = 0L

    val isAvailable get() = sensor != null

    /** Fastest rate the sensor reports, Hz. */
    val maxRateHz: Float get() = sensor?.minDelay?.takeIf { it > 0 }?.let { 1e6f / it } ?: 0f

    fun start() {
        val s = sensor ?: return
        if (thread != null) return
        detector.reset()
        val t = HandlerThread("accel-taps", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        thread = t
        // maxReportLatencyUs = 0 disables FIFO batching, so each sample is delivered immediately.
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, 0, Handler(t.looper))
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ts = event.timestamp
        if (lastTs != 0L && ts > lastTs) {
            val hz = 1e9f / (ts - lastTs)
            sampleRateHz += 0.01f * (hz - sampleRateHz)
        }
        lastTs = ts
        val v = event.values
        detector.process(v[0], v[1], v[2], ts)?.let { tap ->
            sensorLatencyMs = (SystemClock.elapsedRealtimeNanos() - ts) / 1e6f
            onTap(tap)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
