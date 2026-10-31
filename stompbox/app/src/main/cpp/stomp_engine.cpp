// Low-latency bass synth for the stomp box.
//
// Oboe opens an exclusive, low-latency output stream. On a Pixel 8 that is an AAudio MMAP stream
// with output latency around 10 ms. Notes are synthesised in the audio callback rather than
// played from samples, so a trigger costs nothing until the next audio burst. That is at most
// one burst, about 2 ms at 48 kHz.

#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

#define LOG_TAG "StompEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float kTwoPi = 6.28318530718f;
constexpr int kMaxVoices = 4;
constexpr uint32_t kQueueSize = 64;

struct NoteEvent {
    int note;
    float velocity;
};

// Triggers arrive from the camera, sensor and UI threads. Producers share a mutex (they are not
// real-time). The audio thread only reads, without locking.
class EventQueue {
public:
    bool push(const NoteEvent &e) {
        std::lock_guard<std::mutex> guard(producerLock_);
        uint32_t head = head_.load(std::memory_order_relaxed);
        uint32_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail >= kQueueSize) return false;
        buffer_[head % kQueueSize] = e;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    bool pop(NoteEvent &e) {
        uint32_t tail = tail_.load(std::memory_order_relaxed);
        uint32_t head = head_.load(std::memory_order_acquire);
        if (tail == head) return false;
        e = buffer_[tail % kQueueSize];
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

private:
    std::array<NoteEvent, kQueueSize> buffer_{};
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
    std::mutex producerLock_;
};

struct Voice {
    bool active = false;
    bool releasing = false;
    float phase = 0.f;
    float freq = 0.f;
    float level = 0.f;     // velocity-scaled peak
    float env = 0.f;       // amplitude envelope, 1 -> 0
    float attack = 0.f;    // 0 -> 1 ramp, de-clicks the onset
    float pitchEnv = 0.f;  // 1 -> 0, short downward pitch sweep for punch
    float clickEnv = 0.f;  // short noise transient for definition on small speakers
    float clickLp = 0.f;
    uint32_t age = 0;
};

// A punchy electric-bass style voice. Sine plus 2nd and 3rd harmonics, soft-clipped, so that the
// note's pitch is still heard on a phone speaker that can't reproduce the fundamental.
class BassSynth {
public:
    void setSampleRate(int32_t sr) {
        sampleRate_ = static_cast<float>(sr);
        attackStep_ = 1.f / (0.002f * sampleRate_);
        pitchCoef_ = std::exp(-1.f / (0.03f * sampleRate_));
        clickCoef_ = std::exp(-1.f / (0.004f * sampleRate_));
        releaseCoef_ = std::exp(-1.f / (0.012f * sampleRate_));
        setDecaySeconds(decaySeconds_);
    }

    void setDecaySeconds(float seconds) {
        decaySeconds_ = seconds < 0.05f ? 0.05f : seconds;
        decayCoef_.store(std::exp(-1.f / (decaySeconds_ / 4.6f * sampleRate_)));  // -40 dB at t
    }

    void noteOn(int note, float velocity) {
        // Bass is monophonic: quickly fade any ringing note so it doesn't muddy the new one.
        Voice *target = nullptr;
        for (auto &v : voices_) {
            if (v.active) v.releasing = true;
            if (!v.active && target == nullptr) target = &v;
        }
        if (target == nullptr) {
            target = &voices_[0];
            for (auto &v : voices_) if (v.age > target->age) target = &v;
        }
        Voice &v = *target;
        v.active = true;
        v.releasing = false;
        v.phase = 0.f;
        v.freq = 440.f * std::pow(2.f, (note - 69) / 12.f);
        v.level = 0.25f + 0.75f * velocity;
        v.env = 1.f;
        v.attack = 0.f;
        v.pitchEnv = 1.f;
        v.clickEnv = 1.f;
        v.clickLp = 0.f;
        v.age = 0;
    }

    void render(float *out, int32_t frames, int32_t channels) {
        const float decayCoef = decayCoef_.load(std::memory_order_relaxed);
        const float master = 0.9f;
        for (int32_t i = 0; i < frames; ++i) {
            float mix = 0.f;
            for (auto &v : voices_) {
                if (!v.active) continue;
                const float f = v.freq * (1.f + 0.6f * v.pitchEnv);
                v.phase += kTwoPi * f / sampleRate_;
                if (v.phase >= kTwoPi) v.phase -= kTwoPi;

                float s = std::sin(v.phase) + 0.45f * std::sin(2.f * v.phase) +
                          0.2f * std::sin(3.f * v.phase);
                s = std::tanh(1.6f * s);

                // Noise click: xorshift noise through a one-pole low-pass.
                noise_ ^= noise_ << 13;
                noise_ ^= noise_ >> 17;
                noise_ ^= noise_ << 5;
                const float n = static_cast<float>(noise_) * (2.f / 4294967295.f) - 1.f;
                v.clickLp += 0.3f * (n - v.clickLp);

                mix += v.level * (v.env * v.attack * s + 0.35f * v.clickEnv * v.clickLp);

                if (v.attack < 1.f) v.attack = std::fmin(1.f, v.attack + attackStep_);
                v.env *= v.releasing ? releaseCoef_ : decayCoef;
                v.pitchEnv *= pitchCoef_;
                v.clickEnv *= clickCoef_;
                ++v.age;
                if (v.env < 1e-4f) v.active = false;
            }
            const float sample = std::tanh(master * mix);
            for (int32_t c = 0; c < channels; ++c) out[i * channels + c] = sample;
        }
    }

private:
    std::array<Voice, kMaxVoices> voices_{};
    float sampleRate_ = 48000.f;
    float decaySeconds_ = 0.8f;
    std::atomic<float> decayCoef_{0.9999f};
    float attackStep_ = 0.01f;
    float pitchCoef_ = 0.999f;
    float clickCoef_ = 0.99f;
    float releaseCoef_ = 0.99f;
    uint32_t noise_ = 0x12345678u;
};

class StompEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start() {
        std::lock_guard<std::mutex> guard(streamLock_);
        return openAndStartLocked();
    }

    void stop() {
        std::lock_guard<std::mutex> guard(streamLock_);
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    void noteOn(int note, float velocity) { queue_.push({note, velocity}); }

    void setDecaySeconds(float seconds) { synth_.setDecaySeconds(seconds); }

    double latencyMillis() {
        std::lock_guard<std::mutex> guard(streamLock_);
        if (!stream_) return -1.0;
        auto result = stream_->calculateLatencyMillis();
        return result ? result.value() : -1.0;
    }

    bool isMMap() {
        std::lock_guard<std::mutex> guard(streamLock_);
        return stream_ && oboe::OboeExtensions::isMMapUsed(stream_.get());
    }

    int32_t sampleRate() {
        std::lock_guard<std::mutex> guard(streamLock_);
        return stream_ ? stream_->getSampleRate() : 0;
    }

    int32_t bufferFrames() {
        std::lock_guard<std::mutex> guard(streamLock_);
        return stream_ ? stream_->getBufferSizeInFrames() : 0;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override {
        NoteEvent e{};
        while (queue_.pop(e)) synth_.noteOn(e.note, e.velocity);
        synth_.render(static_cast<float *>(audioData), numFrames, stream->getChannelCount());
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) override {
        // Typically the output device changed (headphones plugged / unplugged). Reopen.
        LOGI("Stream closed (%s), restarting", oboe::convertToText(error));
        std::lock_guard<std::mutex> guard(streamLock_);
        stream_.reset();
        openAndStartLocked();
    }

private:
    bool openAndStartLocked() {
        if (stream_) return true;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Game)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("openStream failed: %s", oboe::convertToText(result));
            stream_.reset();
            return false;
        }
        // Two bursts is the smallest buffer that avoids glitches on most devices.
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        synth_.setSampleRate(stream_->getSampleRate());
        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return false;
        }
        LOGI("Started: %d Hz, burst %d, buffer %d, MMAP %d", stream_->getSampleRate(),
             stream_->getFramesPerBurst(), stream_->getBufferSizeInFrames(),
             oboe::OboeExtensions::isMMapUsed(stream_.get()));
        return true;
    }

    std::mutex streamLock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    EventQueue queue_;
    BassSynth synth_;
};

StompEngine &engine() {
    static StompEngine instance;
    return instance;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeStart(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeNoteOn(JNIEnv *, jobject, jint note,
                                                          jfloat velocity) {
    engine().noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeSetDecay(JNIEnv *, jobject, jfloat seconds) {
    engine().setDecaySeconds(seconds);
}

JNIEXPORT jdouble JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeLatencyMillis(JNIEnv *, jobject) {
    return engine().latencyMillis();
}

JNIEXPORT jboolean JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeIsMMap(JNIEnv *, jobject) {
    return engine().isMMap() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeSampleRate(JNIEnv *, jobject) {
    return engine().sampleRate();
}

JNIEXPORT jint JNICALL
Java_uk_neatlines_stompbox_audio_NativeAudio_nativeBufferFrames(JNIEnv *, jobject) {
    return engine().bufferFrames();
}

}  // extern "C"
