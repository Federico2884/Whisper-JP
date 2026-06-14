package com.federico.whisperjp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.federico.whisperjp.audio.AudioCaptureService;

/**
 * Control panel: gathers the runtime permissions the mic pipeline needs
 * (microphone, notifications, overlay), then starts / stops
 * {@link AudioCaptureService}.
 */
public class MainActivity extends AppCompatActivity {

    private TextView status;
    private Button startBtn;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean allGranted = true;
                        for (Boolean granted : result.values()) {
                            if (!Boolean.TRUE.equals(granted)) {
                                allGranted = false;
                            }
                        }
                        if (allGranted) {
                            onStartClicked();
                        } else {
                            setStatus("Microphone / notification permission denied.");
                        }
                    });

    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (Settings.canDrawOverlays(this)) {
                            onStartClicked();
                        } else {
                            setStatus("Overlay permission denied — can't show subtitles.");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        status = findViewById(R.id.status);
        startBtn = findViewById(R.id.btnStart);
        Button stop = findViewById(R.id.btnStop);
        startBtn.setOnClickListener(v -> onStartClicked());
        stop.setOnClickListener(v -> onStopClicked());

        setStatus("Ready to translate");
    }

    private void onStartClicked() {
        if (!hasRuntimePerms()) {
            permLauncher.launch(requiredPerms());
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Grant 'Display over other apps' for WhisperJP…");
            overlayLauncher.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        ContextCompat.startForegroundService(this, new Intent(this, AudioCaptureService.class));
        startBtn.setEnabled(false);
        setStatus("● Listening — subtitles appear over your video");
    }

    private void onStopClicked() {
        stopService(new Intent(this, AudioCaptureService.class));
        startBtn.setEnabled(true);
        setStatus("Stopped.");
    }

    private String[] requiredPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.RECORD_AUDIO};
    }

    private boolean hasRuntimePerms() {
        for (String p : requiredPerms()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }
}
