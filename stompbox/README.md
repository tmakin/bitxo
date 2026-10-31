# Stomp Box

An Android app that turns a Pixel 8 into a foot-tap stomp box. Tap your foot and it plays a
bass note, so you can accompany yourself while playing guitar, ukulele and so on.

There are two ways to detect a tap:

| Mode | How it works | Expected tap → sound latency on Pixel 8 |
|---|---|---|
| **Accelerometer** (recommended) | Strap the phone to your ankle or foot, or lay it on a hard floor next to your foot. The impact spike in a 400+ Hz accelerometer stream triggers the note. | **~15–25 ms** |
| **Camera** | Point the camera at your foot and drag the *trigger zone* box to just above where your toe lands. A note plays when your foot enters the zone. | **~50–80 ms** after the foot enters the zone. Raising the zone makes it fire before the foot lands, which hides part of that. |

These are engineering estimates, not measurements. The app shows live measured figures in the top-left of the screen, so check them on your own phone:

```
camera  60 fps (max 60)  frame→app 42 ms
audio   48000 Hz  buf 192  out 9 ms  MMAP
tap     E2  vel 0.84  detect 44 ms  ≈53 ms total
```

## Why two modes? A note on latency

Most players feel a delay of about 10–20 ms as "instant". That is the same as standing a few
metres from an amp. Beyond about 30–40 ms the note feels late.

- **Audio output is fast in both modes.** Notes are synthesised in C++ inside an
  [Oboe](https://github.com/google/oboe) low-latency stream. On a Pixel 8 that is an exclusive
  AAudio MMAP stream with about 10 ms output latency, and a trigger is picked up at the next 2 ms
  audio burst.
- **The camera is the bottleneck.** Even at 60 fps a frame is up to 16 ms old when exposure
  finishes, and the camera ISP pipeline adds another ~25–50 ms before the app sees it. No vision
  algorithm can remove that. So camera mode uses a cheap trigger-zone detector (well under 1 ms
  per frame, no ML model) and fires *before* the foot lands. Moving the zone higher above the
  floor fires earlier.
- **The accelerometer avoids the camera pipeline.** Samples arrive every ~2.5 ms. The detector
  fires on the first sample over threshold, so the whole chain stays inside the "feels natural"
  window.

**Bluetooth headphones and speakers add 100–300 ms.** Use the phone speaker, USB-C wired
headphones, or a USB-C audio adapter into an amp or PA. The phone speaker can't produce the
fundamental of a low E. The synth adds harmonics so you still hear the pitch, but an amp sounds
much better.

## Using it

1. Install the APK (see below) and open **Stomp Box**.
2. Pick a mode:
   - **Camera:** stand the phone up facing your foot. Drag the *Trigger zone* box so it sits just
     above where your toe lands, and drag the corner to resize it. Lift your foot and press
     **Calibrate (foot up)**. The box turns green while your foot is in it. The bar under the box
     shows the signal, and the white tick is the trigger threshold. **Flip** switches to the front
     camera so you can see the screen while you play.
   - **Accelerometer:** strap the phone to your ankle, or put it flat on a wooden floor or
     stage next to your foot. Adjust **Sensitivity** until light taps trigger but shifting your
     weight doesn't.
3. **Notes:** type one note (`E2`) or a sequence that advances on each tap, e.g. `E2 B1` for
   root/fifth or `A1 A1 E2 E2`. Note names use C4 = middle C, so `E2` is a bass guitar's open E.
   MIDI numbers also work.
4. **Note length** sets how long each note rings. **Test sound** plays a note without tapping.

## Building

Requirements: Android Studio (or the Android SDK command-line tools), JDK 17. The NDK and CMake
are downloaded automatically.

```sh
cd stompbox
./gradlew testDebugUnitTest assembleRelease      # unit tests + APK
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or open the `stompbox/` folder in Android Studio and press Run with the Pixel connected.

GitHub Actions (`.github/workflows/stompbox.yml`) runs the tests and builds the APK on every push
that changes `stompbox/`. Download the APK from the run's **stompbox-apk** artifact. It is signed
with a debug key, so it is fine for your own phone but not for the Play Store.

## Code map

| Path | What |
|---|---|
| `app/src/main/cpp/stomp_engine.cpp` | Oboe output stream, lock-free trigger queue, bass synth (sine + harmonics, pitch "thump", click transient) |
| `app/src/main/java/.../detect/AccelTapDetector.kt` | Gravity high-pass, adaptive threshold, refractory period, velocity from hit strength |
| `app/src/main/java/.../detect/ZoneTapDetector.kt` | Trigger zone: background model, hysteresis, velocity from how fast the foot enters |
| `app/src/main/java/.../detect/NotePattern.kt` | Note-name parsing and per-tap sequence |
| `app/src/main/java/.../input/CameraTapSource.kt` | CameraX at fixed 60 fps, samples a 12×6 luminance grid from the zone |
| `app/src/main/java/.../input/AccelTapSource.kt` | Accelerometer at max rate, no batching, high-priority thread |
| `app/src/main/java/.../MainActivity.kt` | UI, settings, latency read-out |

The detectors are plain Kotlin with no Android dependencies, and their unit tests run on the JVM.

## Ideas for later

- **Microphone mode:** detect the sound of the foot on the floor with a full-duplex Oboe
  stream. That would be about as fast as the accelerometer, without strapping anything on, but it
  needs care so the bass output doesn't re-trigger it.
- **Sonic Pi link:** send each tap as an OSC cue to Sonic Pi (port 4560) so the stomp can drive
  the live loops in `../src`. Wi-Fi adds a few ms of jitter.
- **Pose model:** a MediaPipe foot-landmark model would allow side-on tracking without a zone, at
  the cost of ~10–20 ms more per frame.
