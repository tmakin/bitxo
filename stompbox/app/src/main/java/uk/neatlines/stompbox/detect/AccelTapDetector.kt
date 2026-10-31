package uk.neatlines.stompbox.detect

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects foot taps from raw accelerometer samples.
 *
 * Works with the phone strapped to the foot / ankle, or lying on a hard floor next to the foot
 * (the impact travels through the floor). Gravity and slow movement are removed with a
 * one-pole high-pass filter, then a trigger fires on the *first* sample that crosses an
 * adaptive threshold, so detection adds no more than one sample period (~2.5 ms at 400 Hz).
 *
 * Pure Kotlin with no Android dependencies, so it can be unit tested on the JVM.
 */
class AccelTapDetector(
    /** 0 = only hard stomps trigger, 1 = the lightest tap triggers. */
    @Volatile var sensitivity: Float = 0.5f,
    private val refractoryNanos: Long = 90_000_000L,
    private val gravityTauSeconds: Float = 0.15f,
) {
    private val gravity = FloatArray(3)
    private var initialised = false
    private var lastSampleNanos = 0L
    private var lastTapNanos = Long.MIN_VALUE / 2
    private var armed = true
    private var noiseFloor = 0.05f

    /** High-passed acceleration magnitude of the last sample, m/s². */
    @Volatile var lastMagnitude = 0f
        private set

    /** Current trigger threshold, m/s². */
    @Volatile var threshold = 0f
        private set

    fun reset() {
        initialised = false
        armed = true
        noiseFloor = 0.05f
        lastTapNanos = Long.MIN_VALUE / 2
    }

    /** Feed one accelerometer sample (m/s²). Returns a [TapEvent] if this sample is a new tap. */
    fun process(x: Float, y: Float, z: Float, timeNanos: Long): TapEvent? {
        if (!initialised) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            lastSampleNanos = timeNanos
            initialised = true
            return null
        }
        val dt = ((timeNanos - lastSampleNanos).coerceIn(0L, 100_000_000L)) / 1e9f
        lastSampleNanos = timeNanos

        val hx = x - gravity[0]
        val hy = y - gravity[1]
        val hz = z - gravity[2]
        val mag = sqrt(hx * hx + hy * hy + hz * hz)
        lastMagnitude = mag

        // Follow gravity / slow orientation changes.
        val alpha = 1f - exp(-dt / gravityTauSeconds)
        gravity[0] += alpha * hx
        gravity[1] += alpha * hy
        gravity[2] += alpha * hz

        // Minimum threshold: ~6 m/s² at sensitivity 0, ~0.25 m/s² at sensitivity 1.
        val s = sensitivity.coerceIn(0f, 1f)
        val minThreshold = 6f * exp(ln(0.25f / 6f) * s)
        val thr = max(minThreshold, noiseFloor * 6f)
        threshold = thr

        if (armed && mag > thr && timeNanos - lastTapNanos > refractoryNanos) {
            armed = false
            lastTapNanos = timeNanos
            // Map 1x..8x threshold onto 0.35..1 velocity (log scale).
            val ratio = mag / thr
            val v = 0.35f + 0.65f * (ln(ratio) / ln(8f))
            return TapEvent(timeNanos, v.coerceIn(0.05f, 1f))
        }
        if (!armed) {
            if (mag < thr * 0.5f && timeNanos - lastTapNanos > refractoryNanos) armed = true
        } else {
            // Only learn the noise floor between hits.
            noiseFloor += 0.002f * (mag - noiseFloor)
        }
        return null
    }
}
