package com.federico.whisperjp.audio;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.federico.whisperjp.R;
import com.federico.whisperjp.asr.SherpaAsr;
import com.federico.whisperjp.mt.MtTranslator;
import com.federico.whisperjp.overlay.SubtitleOverlay;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground microphone service running the pipeline:
 * mic → sherpa-onnx (Silero VAD + ReazonSpeech JA ASR) → Japanese text →
 * ML Kit JA→EN translation → floating overlay.
 */
public class AudioCaptureService extends Service {

    public static final String ACTION_STOP = "com.federico.whisperjp.STOP";

    private static final String TAG = "AudioCaptureSvc";
    private static final String CHANNEL_ID = "whisperjp_capture";
    private static final int NOTIF_ID = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int READ_BYTES = 3200; // 100 ms @ 16 kHz, 16-bit mono

    private SubtitleOverlay overlay;
    private SherpaAsr asr;
    private MtTranslator mt;
    private AudioRecord record;
    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean destroyed;
    private volatile String lastShown = "";

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new SubtitleOverlay(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Guard against duplicate starts (repeated taps / sticky redelivery):
        // spawning a second worker would create a second AudioRecord + model and
        // they'd fight over the mic.
        if (worker != null) {
            return START_STICKY;
        }
        startForegroundNotification();
        overlay.show();
        overlay.setText("Loading models…");

        worker = new Thread(this::runPipeline, "wjp-asr");
        worker.start();
        return START_STICKY;
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO verified by the activity
    private void runPipeline() {
        try {
            asr = new SherpaAsr(getAssets());
        } catch (Throwable t) {
            Log.e(TAG, "ASR init failed", t);
            postOverlay("ASR load failed: " + t.getMessage());
            stopSelf();
            return;
        }

        mt = new MtTranslator();
        mt.prepare(ok -> Log.i(TAG, "MT model ready=" + ok));

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE * 2 * 6); // ~6 s, tolerates decode pauses

        record = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .build();
        record.startRecording();
        running.set(true);
        postOverlay("Listening…");

        final byte[] buf = new byte[READ_BYTES];
        while (running.get()) {
            int n = record.read(buf, 0, buf.length);
            if (n <= 0) {
                continue;
            }
            List<String> utterances = asr.accept(buf, n);
            for (String ja : utterances) {
                Log.i(TAG, "JA: " + ja);
                handleUtterance(ja);
            }
        }
        // Flush trailing speech.
        for (String ja : asr.flush()) {
            handleUtterance(ja);
        }
    }

    private void handleUtterance(String japanese) {
        if (mt != null && mt.isReady()) {
            mt.translate(japanese, english -> {
                Log.i(TAG, "EN: " + english);
                show(english);
            });
        } else {
            // Translator model still downloading — show the Japanese so the user
            // sees ASR is working.
            show(japanese);
        }
    }

    private void show(String text) {
        if (text == null) {
            return;
        }
        String t = text.trim();
        if (t.isEmpty() || t.equals(lastShown)) {
            return;
        }
        lastShown = t;
        postOverlay(t);
    }

    private void postOverlay(String text) {
        if (overlay != null) {
            overlay.setText(text);
        }
    }

    private void startForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Live translation", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stopIntent = new Intent(this, AudioCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WhisperJP")
                .setContentText("Translating audio…")
                .setSmallIcon(R.mipmap.ic_launcher)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running.set(false);
        if (worker != null) {
            try {
                worker.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
            record = null;
        }
        if (asr != null) {
            asr.release();
            asr = null;
        }
        if (mt != null) {
            mt.close();
            mt = null;
        }
        if (overlay != null) {
            overlay.hide();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
