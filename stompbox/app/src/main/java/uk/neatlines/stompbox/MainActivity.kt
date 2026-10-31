package uk.neatlines.stompbox

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import uk.neatlines.stompbox.audio.NativeAudio
import uk.neatlines.stompbox.detect.AccelTapDetector
import uk.neatlines.stompbox.detect.NotePattern
import uk.neatlines.stompbox.detect.TapEvent
import uk.neatlines.stompbox.detect.ZoneTapDetector
import uk.neatlines.stompbox.input.AccelTapSource
import uk.neatlines.stompbox.input.CameraTapSource
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Mode { CAMERA, ACCEL }

    private class LastTap(val note: Int, val velocity: Float, val detectMs: Float, val atMs: Long)

    private val zoneDetector = ZoneTapDetector()
    private val accelDetector = AccelTapDetector()
    private lateinit var cameraSource: CameraTapSource
    private lateinit var accelSource: AccelTapSource

    @Volatile private var pattern: NotePattern = NotePattern.parse(DEFAULT_PATTERN)!!
    @Volatile private var lastTap: LastTap? = null

    private var mode = Mode.CAMERA
    private var frontCamera = false
    private var running = false

    private lateinit var overlay: ZoneOverlayView
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var accelPanel: View
    private lateinit var accelMeter: ProgressBar
    private lateinit var flip: Button
    private lateinit var calibrate: Button
    private lateinit var sensitivity: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var decayLabel: TextView

    private val prefs by lazy { getSharedPreferences("stompbox", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            if (running) startInput()
        } else {
            Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
            findViewById<RadioGroup>(R.id.mode).check(R.id.modeAccel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 15 draws edge-to-edge for targetSdk 35, so keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        overlay = findViewById(R.id.overlay)
        previewView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        accelPanel = findViewById(R.id.accelPanel)
        accelMeter = findViewById(R.id.accelMeter)
        flip = findViewById(R.id.flip)
        calibrate = findViewById(R.id.calibrate)
        sensitivity = findViewById(R.id.sensitivity)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        decayLabel = findViewById(R.id.decayLabel)
        // TextureView keeps the preview composited with the overlay and avoids SurfaceView z-order issues.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        cameraSource = CameraTapSource(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            detector = zoneDetector,
            zoneProvider = { overlay.zone },
            onTap = ::onTap,
            onImageSize = overlay::setImageSize,
        )
        accelSource = AccelTapSource(this, accelDetector, ::onTap)

        loadPrefs()
        setupControls()
    }

    override fun onResume() {
        super.onResume()
        running = true
        if (!NativeAudio.start()) Toast.makeText(this, R.string.audio_failed, Toast.LENGTH_LONG).show()
        NativeAudio.setDecaySeconds(decaySeconds(prefs.getInt(KEY_DECAY, 40)))
        startInput()
        ui.post(uiTick)
    }

    override fun onPause() {
        running = false
        ui.removeCallbacks(uiTick)
        stopInput()
        NativeAudio.stop()
        super.onPause()
    }

    /** Called from the camera or sensor thread. Plays the note straight away, before any UI work. */
    private fun onTap(event: TapEvent) {
        val note = pattern.next()
        NativeAudio.noteOn(note, event.velocity)
        val detectMs = (SystemClock.elapsedRealtimeNanos() - event.sensorTimeNanos) / 1e6f
        lastTap = LastTap(note, event.velocity, detectMs, SystemClock.uptimeMillis())
    }

    private fun startInput() {
        stopInput()
        when (mode) {
            Mode.CAMERA -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                    return
                }
                cameraSource.start(frontCamera)
            }
            Mode.ACCEL -> {
                if (!accelSource.isAvailable) {
                    Toast.makeText(this, R.string.no_accel, Toast.LENGTH_LONG).show()
                    return
                }
                accelSource.start()
            }
        }
    }

    private fun stopInput() {
        cameraSource.stop()
        accelSource.stop()
    }

    private fun setupControls() {
        val modeGroup = findViewById<RadioGroup>(R.id.mode)
        modeGroup.check(if (mode == Mode.CAMERA) R.id.modeCamera else R.id.modeAccel)
        applyModeUi()
        modeGroup.setOnCheckedChangeListener { _, id ->
            mode = if (id == R.id.modeAccel) Mode.ACCEL else Mode.CAMERA
            prefs.edit { putString(KEY_MODE, mode.name) }
            applyModeUi()
            if (running) startInput()
        }

        flip.setOnClickListener {
            frontCamera = !frontCamera
            prefs.edit { putBoolean(KEY_FRONT, frontCamera) }
            if (running && mode == Mode.CAMERA) startInput()
        }

        calibrate.setOnClickListener {
            when (mode) {
                Mode.CAMERA -> zoneDetector.recalibrate()
                Mode.ACCEL -> accelDetector.reset()
            }
        }

        findViewById<Button>(R.id.testPad).setOnClickListener {
            onTap(TapEvent(SystemClock.elapsedRealtimeNanos(), 0.9f))
        }

        overlay.onZoneChanged = { z ->
            zoneDetector.recalibrate()
            prefs.edit {
                putFloat(KEY_ZONE_L, z.left); putFloat(KEY_ZONE_T, z.top)
                putFloat(KEY_ZONE_R, z.right); putFloat(KEY_ZONE_B, z.bottom)
            }
        }

        val patternInput = findViewById<EditText>(R.id.pattern)
        patternInput.setText(prefs.getString(KEY_PATTERN, DEFAULT_PATTERN))
        patternInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                val parsed = NotePattern.parse(s.toString())
                if (parsed == null) {
                    patternInput.error = getString(R.string.bad_pattern)
                } else {
                    patternInput.error = null
                    pattern = parsed
                    prefs.edit { putString(KEY_PATTERN, s.toString()) }
                }
            }
        })

        sensitivity.setOnSeekBarChangeListener(object : SeekBarListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val s = progress / 100f
                when (mode) {
                    Mode.CAMERA -> zoneDetector.sensitivity = s
                    Mode.ACCEL -> accelDetector.sensitivity = s
                }
                sensitivityLabel.text = getString(R.string.sensitivity_label, progress)
                if (fromUser) prefs.edit { putInt(sensitivityKey(), progress) }
            }
        })

        val decay = findViewById<SeekBar>(R.id.decay)
        decay.setOnSeekBarChangeListener(object : SeekBarListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = decaySeconds(progress)
                NativeAudio.setDecaySeconds(seconds)
                decayLabel.text = getString(R.string.decay_label, seconds)
                if (fromUser) prefs.edit { putInt(KEY_DECAY, progress) }
            }
        })
        decay.progress = prefs.getInt(KEY_DECAY, 40)
        decayLabel.text = getString(R.string.decay_label, decaySeconds(decay.progress))
    }

    private fun applyModeUi() {
        val camera = mode == Mode.CAMERA
        previewView.visibility = if (camera) View.VISIBLE else View.INVISIBLE
        overlay.visibility = if (camera) View.VISIBLE else View.GONE
        flip.visibility = if (camera) View.VISIBLE else View.GONE
        accelPanel.visibility = if (camera) View.GONE else View.VISIBLE
        calibrate.setText(if (camera) R.string.calibrate else R.string.calibrate_accel)
        val progress = prefs.getInt(sensitivityKey(), 50)
        sensitivity.progress = progress
        zoneDetector.sensitivity = prefs.getInt(KEY_SENS_CAMERA, 50) / 100f
        accelDetector.sensitivity = prefs.getInt(KEY_SENS_ACCEL, 50) / 100f
        sensitivityLabel.text = getString(R.string.sensitivity_label, progress)
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 50)
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        var inputMs = 0f
        when (mode) {
            Mode.CAMERA -> {
                overlay.level = zoneDetector.lastScore / zoneDetector.onThreshold
                overlay.occupied = zoneDetector.state == ZoneTapDetector.State.OCCUPIED
                inputMs = cameraSource.pipelineLatencyMs
                sb.append(fmt("camera  %.0f fps (max %d)  frame→app %.0f ms\n",
                    cameraSource.fps, cameraSource.targetFps, inputMs))
            }
            Mode.ACCEL -> {
                val thr = accelDetector.threshold
                accelMeter.progress = if (thr > 0) (100 * accelDetector.lastMagnitude / thr).toInt() else 0
                inputMs = accelSource.sensorLatencyMs
                sb.append(fmt("accel   %.0f Hz (max %.0f)  thr %.2f m/s²\n",
                    accelSource.sampleRateHz, accelSource.maxRateHz, thr))
            }
        }
        val outMs = NativeAudio.outputLatencyMillis
        sb.append(fmt("audio   %d Hz  buf %d  out %s  %s\n", NativeAudio.sampleRate, NativeAudio.bufferFrames,
            if (outMs >= 0) fmt("%.0f ms", outMs) else "?", if (NativeAudio.isMMap) "MMAP" else "legacy"))
        lastTap?.let { t ->
            val total = t.detectMs + outMs.coerceAtLeast(0.0).toFloat()
            sb.append(fmt("tap     %s  vel %.2f  detect %.0f ms  ≈%.0f ms total", NotePattern.name(t.note),
                t.velocity, t.detectMs, total))
            if (SystemClock.uptimeMillis() - t.atMs < 150) sb.append("  ●")
        } ?: sb.append("tap     –")
        status.text = sb
    }

    private fun loadPrefs() {
        mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(Mode.CAMERA)
        frontCamera = prefs.getBoolean(KEY_FRONT, false)
        pattern = NotePattern.parse(prefs.getString(KEY_PATTERN, DEFAULT_PATTERN) ?: "")
            ?: NotePattern.parse(DEFAULT_PATTERN)!!
        if (prefs.contains(KEY_ZONE_L)) {
            overlay.moveZoneTo(RectF(
                prefs.getFloat(KEY_ZONE_L, 0f), prefs.getFloat(KEY_ZONE_T, 0f),
                prefs.getFloat(KEY_ZONE_R, 1f), prefs.getFloat(KEY_ZONE_B, 1f),
            ))
        }
    }

    private fun sensitivityKey() = if (mode == Mode.CAMERA) KEY_SENS_CAMERA else KEY_SENS_ACCEL

    /** Seek bar 0..100 maps exponentially onto 0.1 .. 3 s. */
    private fun decaySeconds(progress: Int) = (0.1 * Math.pow(30.0, progress / 100.0)).toFloat()

    private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

    private abstract class SeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }

    companion object {
        private const val DEFAULT_PATTERN = "E2"
        private const val KEY_MODE = "mode"
        private const val KEY_FRONT = "front"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_SENS_CAMERA = "sens_camera"
        private const val KEY_SENS_ACCEL = "sens_accel"
        private const val KEY_DECAY = "decay"
        private const val KEY_ZONE_L = "zone_l"
        private const val KEY_ZONE_T = "zone_t"
        private const val KEY_ZONE_R = "zone_r"
        private const val KEY_ZONE_B = "zone_b"
    }
}
