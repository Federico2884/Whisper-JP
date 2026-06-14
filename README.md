# WhisperJP Live Japanese → English Subtitles (On‑Device)

Real‑time, **offline**, on‑device app that listens to Japanese audio playing on the
phone, transcribes it, translates it to English, and shows the result as a
**floating subtitle overlay** on top of any other app (e.g. a video player).

-  Captures audio via the **microphone** (speaker output)
- **Japanese ASR** with sherpa‑onnx (ReazonSpeech zipformer transducer, int8)
- **Japanese → English translation** with Google ML Kit (offline after a one‑time download)
- **Floating overlay** subtitles drawn over other apps
- speech recognition is 100% local with the ML Kit
  language pack fetched.

> Status: working prototype. Accurate Japanese recognition; translation quality is
> limited by the on‑device NMT model (see [Limitations](#limitations)).

---

## How it works (cara kerja)

### Pipeline

```
 ┌──────────────┐   16 kHz mono PCM    ┌─────────────────────────────────────────┐
 │  Microphone  │ ───────────────────► │  AudioCaptureService (foreground)       │
 │ (AudioRecord)│                      │                                         │
 └──────────────┘                      │  ┌───────────────────────────────────┐  │
                                       │  │ SherpaAsr.kt                      │  │
                                       │  │  1. Silero VAD  → speech segments │  │
                                       │  │  2. ReazonSpeech ASR → JA text    │  │
                                       │  └───────────────┬───────────────────┘  │
                                       │                  │ Japanese text        │
                                       │                  ▼                      │
                                       │  ┌───────────────────────────────────┐  │
                                       │  │ MtTranslator.java (ML Kit)        │  │
                                       │  │  JA → EN  (async)                 │  │
                                       │  └───────────────┬───────────────────┘  │
                                       │                  │ English text         │
                                       └──────────────────┼──────────────────────┘
                                                          ▼
                                       ┌─────────────────────────────────────────┐
                                       │ SubtitleOverlay.java                    │
                                       │  WindowManager TYPE_APPLICATION_OVERLAY │
                                       │  -> floating subtitle (auto‑clears 5 s) │
                                       └─────────────────────────────────────────┘
```

### Step by step

1. **Capture.** When you tap *Start*, `MainActivity` starts `AudioCaptureService`,
   a **foreground service** of type `microphone`. The service opens an
   `AudioRecord` on `MediaRecorder.AudioSource.MIC` at **16 kHz, mono, 16‑bit PCM**
   and reads it continuously in ~100 ms chunks on a dedicated worker thread
   (`wjp-asr`).

2. **Voice Activity Detection (VAD).** Each chunk is fed to **Silero VAD**
   (`silero_vad.onnx`) via sherpa‑onnx. The VAD groups audio into *speech segments*
   and emits one when it detects a pause (≈150 ms of silence) or hits the
   `maxSpeechDuration` cap. Silence/non‑speech is dropped — Whisper‑style
   hallucinations are avoided because the recognizer only runs on real speech.
   The raw mic audio is quiet, so it is boosted (`GAIN = 2.0`) and the VAD
   threshold is lowered before feeding the recognizer.

3. **ASR (speech → Japanese text).** Each completed segment is transcribed by the
   **ReazonSpeech offline zipformer transducer** (int8 ONNX, trained on ~35,000 h
   of Japanese) through sherpa‑onnx → produces **Japanese text**. This is a
   *transducer*, so a short utterance is recognised in well under a second — far
   lower latency than a 30‑second‑window Whisper encoder.

4. **Translation (Japanese → English).** The Japanese text is sent to **Google
   ML Kit** on‑device translation (`MtTranslator`). The JA→EN language model
   (~30 MB) downloads once on first use and then runs **fully offline**.
   Translation is asynchronous (ML Kit uses its own threads).

5. **Display.** The English result is posted to `SubtitleOverlay`, which renders it
   in a `WindowManager` overlay window (`TYPE_APPLICATION_OVERLAY`) at the bottom of
   the screen, over whatever app is in front. Each subtitle auto‑clears after 5 s if
   nothing new arrives, and duplicate lines are suppressed.

### Threading model

| Thread | Responsibility |
| --- | --- |
| Main / UI | Control screen, overlay updates (via `Handler`) |
| `wjp-asr` worker | Mic read loop + VAD + ASR (sherpa‑onnx is **not** thread‑safe, so it lives on exactly one thread) |
| ML Kit internal | Asynchronous translation; the result callback hops back to the overlay |

A duplicate‑start guard (`worker != null`) prevents multiple workers / `AudioRecord`s
from being created if *Start* is tapped repeatedly.

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml                 # permissions + service declaration
├── assets/
│   ├── silero_vad.onnx                 # VAD model (~0.6 MB)
│   └── asr/                            # ReazonSpeech JA ASR (int8)
│       ├── encoder.int8.onnx           # ~147 MB
│       ├── decoder.onnx                # ~11 MB
│       ├── joiner.int8.onnx            # ~2.6 MB
│       └── tokens.txt
├── java/com/federico/whisperjp/
│   ├── MainActivity.java               # control UI, permissions, start/stop
│   ├── audio/AudioCaptureService.java  # foreground mic service + pipeline
│   ├── asr/SherpaAsr.kt                # sherpa‑onnx wrapper (VAD + ASR)
│   ├── mt/MtTranslator.java            # ML Kit JA→EN wrapper
│   └── overlay/SubtitleOverlay.java    # floating subtitle window
└── res/layout/activity_main.xml        # Material 3 control screen
```

---

## Models

The ASR/VAD models are kept in `app/src/main/assets/` (uncompressed via
`noCompress += "onnx"`, so sherpa‑onnx reads them straight from the APK). They are
**not** small, so if you clone this repo you must fetch them:

```bash
# Silero VAD
curl -L -o app/src/main/assets/silero_vad.onnx \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx

# ReazonSpeech Japanese ASR (extract the int8 files into app/src/main/assets/asr/)
curl -L -o reazon.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2
tar -xf reazon.tar.bz2
#   encoder-epoch-99-avg-1.int8.onnx → app/src/main/assets/asr/encoder.int8.onnx
#   decoder-epoch-99-avg-1.onnx      → app/src/main/assets/asr/decoder.onnx
#   joiner-epoch-99-avg-1.int8.onnx  → app/src/main/assets/asr/joiner.int8.onnx
#   tokens.txt                       → app/src/main/assets/asr/tokens.txt
```

The ML Kit JA→EN model is downloaded automatically on first run (needs internet once).

---

## Permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Capture audio from the microphone |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Keep capturing while the app is backgrounded |
| `SYSTEM_ALERT_WINDOW` | Draw the subtitle overlay over other apps |
| `POST_NOTIFICATIONS` | Foreground‑service notification (Android 13+) |
| `INTERNET` | One‑time download of the ML Kit translation model |

---

## Build & run

**Requirements**

- Android Studio (AGP **9.2.1**, Gradle **9.4.1**, JDK **21**; Kotlin is built into AGP 9)
- A device on **Android 10+** (`minSdk 29`), **arm64‑v8a**
- The model assets in place (see [Models](#models))

**Build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

**Run / debug**

- Easiest: open in Android Studio, connect the phone, hit **▶ Run** (builds, installs,
  launches in one click). Use the **Logcat** panel and filter by tag
  `AudioCaptureSvc` (the `JA:` / `EN:` lines) or `SherpaAsr` (`rms` / `speechDetected`).
- CLI equivalent:
  ```powershell
  adb install -r app\build\outputs\apk\debug\app-debug.apk
  adb shell am start -n com.federico.whisperjp/.MainActivity
  adb logcat -s AudioCaptureSvc SherpaAsr
  ```

> ⚠️ On some OEMs (e.g. Xiaomi/MIUI) USB install is blocked
> (`INSTALL_FAILED_USER_RESTRICTED`); sideload the APK via the Files app instead, or
> enable “USB debugging (Security settings)”. Vivo/iQOO (Funtouch) allows `adb install`.

---

## Usage

1. Open WhisperJP, tap **Start translation**, grant the microphone / overlay prompts.
2. Play your Japanese video **in Picture‑in‑Picture (PiP)** and keep WhisperJP in
   front. Turn the **media volume up**.
3. English subtitles appear over the video.

**Why PiP + app in front?** Capture is mic‑based, and some OEMs **silence the
microphone for background apps** even with a valid microphone foreground service.
Keeping WhisperJP in the foreground (with the video in a PiP window) keeps the mic
live. See [Limitations](#limitations).

---

## Limitations

- **No internal‑audio capture.** The Android `AudioPlaybackCapture` API is blocked by
  apps that opt out (YouTube, Chrome, …). Only a privileged/system app holding
  `CAPTURE_AUDIO_OUTPUT` (like the built‑in screen recorder) can bypass that. So the
  app uses the **microphone** — it hears the **speaker**, which means the speaker must
  be on and the room reasonably quiet.
- **Background mic silencing.** Some OEMs feed a backgrounded app pure silence. Work
  around it with the **PiP + foreground** setup above.
- **Translation quality.** The Japanese transcription is accurate, but the **offline
  ML Kit NMT** is the weak link on casual/colloquial speech. The only large upgrade is
  a **cloud translator** (Google Translate / DeepL API) — a small change in
  `MtTranslator`, but it sacrifices "fully offline".
- **arm64‑v8a only** (the bundled native libs / models target it).

---

## Tuning

In `app/src/main/java/com/federico/whisperjp/asr/SherpaAsr.kt`:

| Knob | Effect |
| --- | --- |
| `threshold` (Silero) | Lower = more sensitive (catches quieter speech, but more false positives). Currently `0.1`. |
| `GAIN` | Boost for quiet mic audio. `2.0` works; `3.0` over‑amplifies/distorts and the VAD stops firing. |
| `maxSpeechDuration` | Shorter (e.g. `2.5`) = more frequent subtitle updates, less context. |
| `minSilenceDuration` | How long a pause ends a segment. |

Watch the `rms` / `speechDetected` Logcat line while audio plays to dial these in,
then remove that debug log for a clean release build.

---

## Tech stack

- **ASR/VAD:** [sherpa‑onnx](https://github.com/k2-fsa/sherpa-onnx) `1.13.1`
  (JitPack AAR — bundles native `.so` + Kotlin API), ReazonSpeech zipformer + Silero VAD
- **Translation:** Google **ML Kit** on‑device Translation `17.0.3`
- **Android:** AGP 9.2.1 / Gradle 9.4.1, Java 11 + Kotlin (built into AGP 9),
  AndroidX, Material 3, `minSdk 29` / `targetSdk 36`

## Project history (why this architecture)

The first version used **whisper.cpp** in `translate` mode (JA audio → EN text in one
model). It worked, but on‑device Whisper has a fixed ~30‑second encoder window, so even
short clips cost seconds of compute → high latency; the small/base models also
hallucinated stock phrases ("thank you for watching") on music/SFX. It was replaced by
the current **streaming‑style ASR (sherpa‑onnx) + dedicated NMT (ML Kit)** pipeline,
which is lower‑latency (results per utterance) and more accurate on Japanese. The
internal‑audio‑capture path (`AudioPlaybackCaptureConfiguration`) is preserved in git
history for devices/apps that allow it.
