package uk.neatlines.stompbox.detect

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Camera "trigger zone" tap detector.
 *
 * The user places a small box on the camera preview just above where their toe lands. With the
 * foot raised the zone shows background, and the detector learns that as its reference. When the
 * foot drops into the zone the image differs from the reference and a tap fires immediately.
 *
 * Because the zone sits slightly *above* the floor, the trigger fires a few milliseconds before
 * the foot actually lands. That hides part of the camera pipeline's latency. Moving the zone
 * higher fires earlier.
 *
 * Input is a small grid of mean luminance values (0..255) sampled from the zone each frame.
 * Pure Kotlin with no Android dependencies, so it can be unit tested on the JVM.
 */
class ZoneTapDetector(
    /** 0 = needs a big change in the zone, 1 = triggers on subtle changes. */
    @Volatile var sensitivity: Float = 0.5f,
    private val refractoryNanos: Long = 100_000_000L,
    private val backgroundAdaptRate: Float = 0.05f,
) {
    enum class State { EMPTY, OCCUPIED }

    private var reference: FloatArray? = null
    private var prevScore = 0f
    private var prevTimeNanos = 0L
    private var lastTapNanos = Long.MIN_VALUE / 2
    private var lowFrames = 0

    @Volatile var state = State.EMPTY
        private set

    /** Mean absolute difference from the reference, as a fraction of full scale (0..1). */
    @Volatile var lastScore = 0f
        private set

    /** Score that triggers a tap at the current sensitivity. */
    val onThreshold: Float
        get() {
            // 0.15 at sensitivity 0, 0.02 at sensitivity 1 (log scale).
            val s = sensitivity.coerceIn(0f, 1f)
            return 0.15f * exp(ln(0.02f / 0.15f) * s)
        }

    /** Forget the background. The next frame becomes the new "foot up" reference. */
    fun recalibrate() {
        reference = null
    }

    fun process(cells: FloatArray, timeNanos: Long): TapEvent? {
        val ref = reference
        if (ref == null || ref.size != cells.size) {
            reference = cells.copyOf()
            state = State.EMPTY
            prevScore = 0f
            prevTimeNanos = timeNanos
            lowFrames = 0
            lastScore = 0f
            return null
        }

        var sum = 0f
        for (i in cells.indices) sum += abs(cells[i] - ref[i])
        val score = sum / cells.size / 255f
        lastScore = score

        val on = onThreshold
        val off = on * 0.5f
        val dt = ((timeNanos - prevTimeNanos).coerceAtLeast(1_000_000L)) / 1e9f
        val rise = (score - prevScore) / dt // score units per second
        prevScore = score
        prevTimeNanos = timeNanos

        var event: TapEvent? = null
        when (state) {
            State.EMPTY -> {
                if (score > on && timeNanos - lastTapNanos > refractoryNanos) {
                    state = State.OCCUPIED
                    lowFrames = 0
                    lastTapNanos = timeNanos
                    // How far into the zone the foot moved per 60 fps frame, relative to the
                    // threshold: 1x -> 0.3 velocity, 8x or more -> 1.0 (log scale).
                    val ratio = (rise / 60f / on).coerceAtLeast(1f)
                    val v = 0.3f + 0.7f * (ln(ratio) / ln(8f))
                    event = TapEvent(timeNanos, v.coerceIn(0.3f, 1f))
                } else if (score < off) {
                    // Track slow lighting / exposure drift while the zone is clear.
                    for (i in cells.indices) ref[i] += backgroundAdaptRate * (cells[i] - ref[i])
                }
            }
            State.OCCUPIED -> {
                // Require two clear frames before re-arming, to ignore single-frame noise.
                if (score < off) {
                    lowFrames++
                    if (lowFrames >= 2) state = State.EMPTY
                } else {
                    lowFrames = 0
                }
            }
        }
        return event
    }
}
