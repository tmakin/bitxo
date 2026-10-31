package uk.neatlines.stompbox.audio

/** Kotlin bindings for the Oboe bass synth in `stomp_engine.cpp`. All methods are thread-safe. */
object NativeAudio {
    init {
        System.loadLibrary("stompbox")
    }

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    /** Queue a note. It starts sounding at the next audio burst, about 2 ms later. */
    fun noteOn(midiNote: Int, velocity: Float) = nativeNoteOn(midiNote, velocity.coerceIn(0f, 1f))

    fun setDecaySeconds(seconds: Float) = nativeSetDecay(seconds)

    /** Estimated time from writing a sample to hearing it, or a negative value if unknown. */
    val outputLatencyMillis: Double get() = nativeLatencyMillis()
    val isMMap: Boolean get() = nativeIsMMap()
    val sampleRate: Int get() = nativeSampleRate()
    val bufferFrames: Int get() = nativeBufferFrames()

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Float)
    private external fun nativeSetDecay(seconds: Float)
    private external fun nativeLatencyMillis(): Double
    private external fun nativeIsMMap(): Boolean
    private external fun nativeSampleRate(): Int
    private external fun nativeBufferFrames(): Int
}
