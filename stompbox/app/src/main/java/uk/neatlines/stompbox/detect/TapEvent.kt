package uk.neatlines.stompbox.detect

/**
 * A detected foot tap.
 *
 * @param sensorTimeNanos timestamp of the sensor sample / camera frame that caused the trigger,
 *   in the [android.os.SystemClock.elapsedRealtimeNanos] time base.
 * @param velocity normalised hit strength in 0..1, used for note volume.
 */
data class TapEvent(val sensorTimeNanos: Long, val velocity: Float)
