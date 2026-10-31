package uk.neatlines.stompbox.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AccelTapDetectorTest {
    private val periodNs = 2_500_000L // 400 Hz

    /** Phone lying flat: gravity on z, small noise, and an impulse at each index in [hits]. */
    private fun run(det: AccelTapDetector, samples: Int, hits: Map<Int, Float>): List<Pair<Int, TapEvent>> {
        val rnd = Random(1)
        val out = mutableListOf<Pair<Int, TapEvent>>()
        for (i in 0 until samples) {
            var z = 9.81f + (rnd.nextFloat() - 0.5f) * 0.1f
            // Each hit is a short decaying oscillation lasting ~10 samples.
            for ((start, amp) in hits) {
                val k = i - start
                if (k in 0 until 10) z += amp * (if (k % 2 == 0) 1f else -0.8f) * (1f - k / 10f)
            }
            det.process(0.02f, -0.01f, z, i * periodNs)?.let { out += i to it }
        }
        return out
    }

    @Test
    fun noiseAloneNeverTriggers() {
        assertTrue(run(AccelTapDetector(sensitivity = 1f), 4000, emptyMap()).isEmpty())
    }

    @Test
    fun triggersOnFirstSampleOfImpact() {
        val taps = run(AccelTapDetector(sensitivity = 0.5f), 2000, mapOf(400 to 8f, 1000 to 8f))
        assertEquals(listOf(400, 1000), taps.map { it.first })
    }

    @Test
    fun ringingDoesNotDoubleTrigger() {
        val taps = run(AccelTapDetector(sensitivity = 1f), 1000, mapOf(300 to 15f))
        assertEquals(1, taps.size)
    }

    @Test
    fun lowSensitivityIgnoresLightTaps() {
        assertTrue(run(AccelTapDetector(sensitivity = 0f), 1000, mapOf(300 to 1.5f)).isEmpty())
        assertEquals(1, run(AccelTapDetector(sensitivity = 0.8f), 1000, mapOf(300 to 1.5f)).size)
    }

    @Test
    fun harderHitsAreLouder() {
        val soft = run(AccelTapDetector(sensitivity = 0.6f), 1000, mapOf(300 to 2f)).single().second
        val hard = run(AccelTapDetector(sensitivity = 0.6f), 1000, mapOf(300 to 20f)).single().second
        assertTrue(hard.velocity > soft.velocity)
    }

    @Test
    fun firstSampleOnlyInitialises() {
        assertNull(AccelTapDetector().process(0f, 0f, 30f, 0))
        val d = AccelTapDetector(sensitivity = 1f)
        d.process(0f, 0f, 9.8f, 0)
        assertNotNull(d.process(0f, 0f, 30f, periodNs))
    }
}
