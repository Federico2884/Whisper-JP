package com.federico.whisperjp.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Floating subtitle window drawn over other apps (DESIGN.md §2, component 4).
 *
 * <p>Requires the SYSTEM_ALERT_WINDOW permission. All window operations are
 * marshalled onto the main thread.
 */
public final class SubtitleOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView view;
    private boolean shown;

    // Auto-hide a subtitle if nothing new arrives within this window.
    private static final long CLEAR_DELAY_MS = 5000;
    private final Runnable clearRunnable = () -> {
        if (view != null) {
            view.setText("");
            view.setVisibility(View.INVISIBLE);
        }
    };

    public SubtitleOverlay(Context context) {
        this.ctx = context.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        ui.post(() -> {
            if (shown) {
                return;
            }
            view = new TextView(ctx);
            view.setText("…");
            view.setTextColor(Color.WHITE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            view.setBackgroundColor(Color.argb(170, 0, 0, 0));
            view.setPadding(36, 18, 36, 18);

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = 160;
            try {
                wm.addView(view, lp);
                shown = true;
            } catch (Exception e) {
                view = null;
            }
        });
    }

    public void setText(String text) {
        ui.post(() -> {
            if (view != null) {
                view.setText(text);
                view.setVisibility(View.VISIBLE);
                ui.removeCallbacks(clearRunnable);
                ui.postDelayed(clearRunnable, CLEAR_DELAY_MS);
            }
        });
    }

    public void hide() {
        ui.post(() -> {
            ui.removeCallbacks(clearRunnable);
            if (shown && view != null) {
                try {
                    wm.removeView(view);
                } catch (Exception ignored) {
                }
                view = null;
                shown = false;
            }
        });
    }
}
