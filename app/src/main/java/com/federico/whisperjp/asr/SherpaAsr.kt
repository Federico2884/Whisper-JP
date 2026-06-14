package com.federico.whisperjp.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * On-device Japanese ASR via sherpa-onnx.
 *
 * Silero VAD segments the mic stream into utterances; the ReazonSpeech offline
 * zipformer transducer (int8) transcribes each completed segment. This replaces
 * Whisper for the speech→text stage and gives much lower latency (results after
 * each pause, not after a fixed 30 s window). Translation is done separately.
 *
 * Models are read straight from the APK assets (uncompressed). Not thread-safe —
 * call from one worker thread.
 */
class SherpaAsr(assetManager: AssetManager) {

    private val sampleRate = 16000

    private val vad = Vad(
        assetManager,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                // Sensitive settings: mic-captured speaker audio is quiet, so a low
                // threshold + short min-durations catch more speech, and a short
                // max-duration makes continuous speech emit subtitles more often.
                threshold = 0.1f,
                minSilenceDuration = 0.15f,
                minSpeechDuration = 0.1f,
                windowSize = 512,
                maxSpeechDuration = 2.5f,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private val recognizer = OfflineRecognizer(
        assetManager,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "asr/encoder.int8.onnx",
                    decoder = "asr/decoder.onnx",
                    joiner = "asr/joiner.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                modelType = "transducer",
                numThreads = 4,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    private var dbgT = 0L

    /** Feed 16-bit little-endian PCM (mono, 16 kHz). Returns completed JA utterances. */
    fun accept(pcm16: ByteArray, length: Int): List<String> {
        val samples = toFloat(pcm16, length)
        val now = System.currentTimeMillis()
        if (now - dbgT > 2000) {
            var sum = 0.0
            for (s in samples) sum += (s * s).toDouble()
            val rms = if (samples.isNotEmpty()) Math.sqrt(sum / samples.size) else 0.0
            Log.i("SherpaAsr", "rms=%.5f speechDetected=%b".format(rms, vad.isSpeechDetected()))
            dbgT = now
        }
        vad.acceptWaveform(samples)
        return drain()
    }

    /** Flush buffered speech (call when stopping). */
    fun flush(): List<String> {
        vad.flush()
        return drain()
    }

    private fun drain(): List<String> {
        val results = ArrayList<String>()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val stream = recognizer.createStream()
            stream.acceptWaveform(segment.samples, sampleRate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            if (text.isNotEmpty()) {
                results.add(text)
            }
        }
        return results
    }

    fun release() {
        vad.release()
        recognizer.release()
    }

    private fun toFloat(b: ByteArray, length: Int): FloatArray {
        val n = length / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = b[2 * i].toInt() and 0xff
            val hi = b[2 * i + 1].toInt() and 0xff
            // Boost the quiet mic-captured speaker audio (with clipping guard).
            var v = ((hi shl 8) or lo).toShort().toFloat() / 32768.0f * GAIN
            if (v > 1.0f) v = 1.0f else if (v < -1.0f) v = -1.0f
            out[i] = v
        }
        return out
    }

    private companion object {
        const val GAIN = 2.0f
    }
}
