package uk.neatlines.stompbox.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ZoneTapDetectorTest {
    private val frameNs = 16_666_667L // 60 fps
    private val cells = 72
    private val rnd = Random(7)

    private fun background(brightness: Float = 180f) =
        FloatArray(cells) { brightness + (it % 5) * 3f + (rnd.nextFloat() - 0.5f) * 2f }

    /** Foot covering [coverage] of the cells with a dark shoe. */
    private fun foot(coverage: Float) =
        background().also { a -> for (i in 0 until (cells * coverage).toInt()) a[i] = 40f }

    @Test
    fun firesOnceWhenFootEntersAndRearmsWhenItLeaves() {
        val d = ZoneTapDetector(sensitivity = 0.5f)
        val frames = List(10) { background() } + List(10) { foot(0.6f) } +
            List(10) { background() } + List(10) { foot(0.6f) }
        val hits = frames.mapIndexedNotNull { i, f -> d.process(f, i * frameNs)?.let { i } }
        assertEquals(listOf(10, 30), hits)
    }

    @Test
    fun footRestingInZoneDoesNotRetrigger() {
        val d = ZoneTapDetector()
        repeat(5) { d.process(background(), it * frameNs) }
        val hits = (5 until 300).count { d.process(foot(0.5f), it * frameNs) != null }
        assertEquals(1, hits)
    }

    @Test
    fun slowLightingDriftIsIgnored() {
        val d = ZoneTapDetector(sensitivity = 1f)
        for (i in 0 until 600) {
            // Brightness drifts by 60 levels over 10 s.
            assertNull(d.process(background(150f + i * 0.1f), i * frameNs))
        }
    }

    @Test
    fun sensitivityControlsSmallChanges() {
        fun hitsFor(s: Float): Int {
            val d = ZoneTapDetector(sensitivity = s)
            repeat(5) { d.process(background(), it * frameNs) }
            return (5 until 20).count { d.process(foot(0.1f), it * frameNs) != null }
        }
        assertEquals(0, hitsFor(0f))
        assertEquals(1, hitsFor(1f))
    }

    @Test
    fun fastFootIsLouderThanSlowFoot() {
        val fast = ZoneTapDetector(sensitivity = 0.5f).run {
            repeat(5) { process(background(), it * frameNs) }
            process(foot(0.8f), 5 * frameNs)!!
        }
        val slow = ZoneTapDetector(sensitivity = 0.5f).run {
            repeat(5) { process(background(), it * frameNs) }
            (5 until 40).firstNotNullOf { process(foot((it - 5) * 0.02f), it * frameNs) }
        }
        assertTrue("fast=${fast.velocity} slow=${slow.velocity}", fast.velocity > slow.velocity)
    }

    @Test
    fun recalibrateLearnsNewBackground() {
        val d = ZoneTapDetector()
        d.process(foot(0.6f), 0) // started with foot down by mistake
        d.recalibrate()
        d.process(background(), frameNs)
        assertTrue(d.process(foot(0.6f), 2 * frameNs) != null)
    }
}
