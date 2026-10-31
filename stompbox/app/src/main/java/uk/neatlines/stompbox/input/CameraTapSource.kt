package uk.neatlines.stompbox.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import uk.neatlines.stompbox.detect.TapEvent
import uk.neatlines.stompbox.detect.ZoneTapDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the camera at the highest fixed frame rate available (60 fps on a Pixel 8) and feeds a
 * small luminance grid from the trigger zone to [ZoneTapDetector]. It needs no ML model.
 * Processing a frame takes well under 1 ms, so the delay comes from the camera pipeline itself.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraTapSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    val detector: ZoneTapDetector,
    /** Trigger zone in normalised coordinates of the upright (as displayed) image. */
    private val zoneProvider: () -> RectF,
    /** Called on the analysis thread. */
    private val onTap: (TapEvent) -> Unit,
    /** Called on the main thread once the analysis resolution is known: upright width, height. */
    private val onImageSize: (Int, Int) -> Unit,
) {
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private val cells = FloatArray(GRID_X * GRID_Y)
    private var lastFrameTs = 0L
    private var reportedSize: Size? = null
    @Volatile private var useFront = false

    @Volatile var fps = 0f
        private set
    /** Sensor exposure -> frame available to the app, ms (EMA). */
    @Volatile var pipelineLatencyMs = 0f
        private set
    @Volatile var targetFps = 0
        private set

    fun start(front: Boolean) {
        useFront = front
        if (executor == null) executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                r.run()
            }, "camera-taps")
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        executor?.shutdown()
        executor = null
    }

    private fun bind() {
        val provider = provider ?: return
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fpsRange = pickFpsRange(provider, selector)
        targetFps = fpsRange?.upper ?: 30

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        if (fpsRange != null) {
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(executor ?: return, ::analyze)

        provider.unbindAll()
        reportedSize = null
        detector.recalibrate()
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    /** Prefer a fixed high frame rate (e.g. [60, 60]): fixed ranges stop auto-exposure lowering fps. */
    private fun pickFpsRange(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.filter { it.upper <= 60 }
            .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(image: ImageProxy) {
        image.use {
            val ts = image.imageInfo.timestamp
            val now = SystemClock.elapsedRealtimeNanos()
            if (lastFrameTs != 0L && ts > lastFrameTs) {
                fps += 0.1f * (1e9f / (ts - lastFrameTs) - fps)
            }
            lastFrameTs = ts
            val latency = (now - ts) / 1e6f
            // Only meaningful if the camera timestamps use the realtime clock (true on Pixels).
            if (latency in 0f..1000f) pipelineLatencyMs += 0.1f * (latency - pipelineLatencyMs)

            val rot = image.imageInfo.rotationDegrees
            val uprightW = if (rot % 180 == 0) image.width else image.height
            val uprightH = if (rot % 180 == 0) image.height else image.width
            val size = Size(uprightW, uprightH)
            if (size != reportedSize) {
                reportedSize = size
                ContextCompat.getMainExecutor(context).execute { onImageSize(uprightW, uprightH) }
            }

            if (sampleZone(image, rot)) {
                detector.process(cells, ts)?.let(onTap)
            }
        }
    }

    /** Average the Y plane over a GRID_X x GRID_Y grid covering the zone. */
    private fun sampleZone(image: ImageProxy, rotation: Int): Boolean {
        var zone = RectF(zoneProvider())
        // The front-camera preview is mirrored but the analysis frames are not.
        if (useFront) zone = RectF(1f - zone.right, zone.top, 1f - zone.left, zone.bottom)
        val raw = uprightToRaw(zone, rotation)

        val w = image.width
        val h = image.height
        val x0 = (raw.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (raw.right * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (raw.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (raw.bottom * h).toInt().coerceIn(y0 + 1, h)
        if (x1 - x0 < GRID_X || y1 - y0 < GRID_Y) return false

        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val cellW = (x1 - x0) / GRID_X
        val cellH = (y1 - y0) / GRID_Y
        val step = maxOf(1, minOf(cellW, cellH) / 4)

        for (gy in 0 until GRID_Y) {
            for (gx in 0 until GRID_X) {
                val cx0 = x0 + gx * cellW
                val cy0 = y0 + gy * cellH
                var sum = 0
                var n = 0
                var y = cy0
                while (y < cy0 + cellH) {
                    val row = y * rowStride
                    var x = cx0
                    while (x < cx0 + cellW) {
                        sum += buf.get(row + x * pixStride).toInt() and 0xFF
                        n++
                        x += step
                    }
                    y += step
                }
                cells[gy * GRID_X + gx] = if (n > 0) sum.toFloat() / n else 0f
            }
        }
        return true
    }

    companion object {
        private const val TAG = "CameraTapSource"
        const val GRID_X = 12
        const val GRID_Y = 6

        /**
         * Maps a rect in normalised upright-image coordinates to normalised raw sensor-buffer
         * coordinates. [rotation] is the clockwise rotation that turns the raw buffer upright.
         */
        fun uprightToRaw(r: RectF, rotation: Int): RectF {
            fun map(u: Float, v: Float): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
                90 -> v to 1f - u
                180 -> 1f - u to 1f - v
                270 -> 1f - v to u
                else -> u to v
            }
            val (ax, ay) = map(r.left, r.top)
            val (bx, by) = map(r.right, r.bottom)
            return RectF(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
        }
    }
}
