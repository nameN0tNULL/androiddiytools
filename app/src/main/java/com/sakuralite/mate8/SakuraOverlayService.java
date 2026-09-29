package com.sakuralite.mate8;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SakuraOverlayService extends Service {
    private static final int NOTIFY_ID = 808;
    private static final String CHANNEL_ID = "sakura_mate8_lite";
    private static final long AUTO_INTERVAL_MS = 2000L;
    // Low-power local change gate:
    // L1: 17x9 grayscale dHash (153 pixel reads, 144 comparisons).
    // L2: 32x12 grayscale MAD only when dHash is suspicious.
    // L3: repeat after 250ms and require a stable frame before network/JPEG.
    private static final int AUTO_DHASH_COLS = 17;
    private static final int AUTO_DHASH_ROWS = 9;
    private static final int AUTO_DHASH_BITS = (AUTO_DHASH_COLS - 1) * AUTO_DHASH_ROWS;
    private static final int AUTO_DHASH_WORDS = (AUTO_DHASH_BITS + 63) / 64;
    private static final int AUTO_DHASH_UNCHANGED_BITS = 3;
    private static final int AUTO_MAD_COLS = 32;
    private static final int AUTO_MAD_ROWS = 12;
    private static final int AUTO_MAD_PIXEL_DIFF = 20;
    private static final float AUTO_MAD_CHANGED_RATIO = 0.03f;
    private static final float AUTO_MAD_AVERAGE_DIFF = 6.0f;
    private static final long AUTO_STABILITY_DELAY_MS = 250L;
    private static final long AUTO_FAILURE_BACKOFF_MS = 10000L;

    private WindowManager wm;
    private TextView translateButton;
    private TextView selectButton;
    private TextView retryButton;
    private TextView autoButton;
    private TextView resultView;
    private WindowManager.LayoutParams translateParams;
    private WindowManager.LayoutParams selectParams;
    private WindowManager.LayoutParams retryParams;
    private WindowManager.LayoutParams autoParams;
    private WindowManager.LayoutParams resultParams;

    private SelectionOverlayView selectionView;
    private WindowManager.LayoutParams selectionParams;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;

    private HandlerThread captureThread;
    private Handler capture;
    private Handler main;
    private ExecutorService network;
    private Runnable autoRunnable;

    private String endpoint;
    private String secret;
    private float fallbackCropRatio = 0.55f;
    private volatile boolean busy;

    private boolean autoTranslateEnabled;
    private boolean pendingAutoMode;
    private boolean pendingForceRetranslate;
    private int oldTranslateVisibility;
    private int oldSelectVisibility;
    private int oldRetryVisibility;
    private int oldAutoVisibility;
    private int oldResultVisibility;

    // Automatic mode is gated locally. Unchanged frames never leave the phone.
    private AutoSignature lastAutoSignature;
    private AutoSignature pendingAutoSignature;
    private long autoBackoffUntil;
    private String lastDisplayedText = "";
    private String lastSuccessfulTranslation = "";
    private boolean lastDisplayedWasTranslation;

    private static final class AutoSignature {
        final long[] hashBits;
        final long[] hashValid;
        final int[] madSample;

        AutoSignature(long[] hashBits, long[] hashValid, int[] madSample) {
            this.hashBits = hashBits;
            this.hashValid = hashValid;
            this.madSample = madSample;
        }
    }

    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("sakura_lite", MODE_PRIVATE);
        main = new Handler(Looper.getMainLooper());
        captureThread = new HandlerThread("SakuraCapture");
        captureThread.start();
        capture = new Handler(captureThread.getLooper());
        network = Executors.newSingleThreadExecutor();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        autoRunnable = new Runnable() {
            @Override public void run() {
                if (autoTranslateEnabled
                        && !busy
                        && selectionView == null
                        && projection != null
                        && System.currentTimeMillis() >= autoBackoffUntil) {
                    requestCapture(true, false);
                }
                if (main != null) main.postDelayed(this, AUTO_INTERVAL_MS);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY_ID, notification());

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        endpoint = intent.getStringExtra("endpoint");
        secret = intent.getStringExtra("secret");
        fallbackCropRatio = intent.getFloatExtra("crop", 0.55f);
        int resultCode = intent.getIntExtra("result_code", 0);
        Intent resultData = intent.getParcelableExtra("result_data");

        MediaProjectionManager m = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (m == null || resultData == null) {
            toast("无法初始化屏幕捕获");
            stopSelf();
            return START_NOT_STICKY;
        }

        projection = m.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            toast("屏幕捕获授权无效");
            stopSelf();
            return START_NOT_STICKY;
        }

        createOverlays();
        ensureCaptureSurface(true);
        main.removeCallbacks(autoRunnable);
        main.postDelayed(autoRunnable, 800L);
        return START_STICKY;
    }

    private Notification notification() {
        String mode = autoTranslateEnabled
                ? "自动翻译运行中"
                : "悬浮翻译运行中";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Sakura Mate8 Lite", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Sakura Mate8 Lite")
                    .setContentText(mode)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setOngoing(true)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Sakura Mate8 Lite")
                .setContentText(mode)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createOverlays() {
        if (wm == null || translateButton != null) return;

        translateButton = smallButton("译");
        selectButton = smallButton("框");
        retryButton = smallButton("重");
        autoButton = smallButton("自");
        autoTranslateEnabled = false;
        updateAutoButtonAppearance();

        translateParams = buttonParams(dp(12), dp(220));
        selectParams = buttonParams(dp(12), dp(282));
        retryParams = buttonParams(dp(12), dp(344));
        autoParams = buttonParams(dp(12), dp(406));

        resultView = new TextView(this);
        resultView.setPadding(dp(14), dp(10), dp(14), dp(10));
        resultView.setVisibility(View.GONE);
        applyTranslationStyle();

        resultParams = new WindowManager.LayoutParams(
                dp(360),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        resultParams.gravity = Gravity.TOP | Gravity.START;

        translateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestCapture(false, false);
            }
        });

        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSelectionOverlay();
            }
        });

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestCapture(false, true);
            }
        });

        autoButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoTranslateEnabled = !autoTranslateEnabled;
                pendingAutoSignature = null;
                autoBackoffUntil = 0L;
                if (autoTranslateEnabled) {
                    // Start from a fresh baseline so enabling "自" immediately
                    // evaluates the current dialogue instead of inheriting old state.
                    lastAutoSignature = null;
                }
                updateAutoButtonAppearance();
                if (autoTranslateEnabled && !busy && selectionView == null && projection != null) {
                    requestCapture(true, false);
                }
            }
        });

        resultView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (resultParams == null || wm == null) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = resultParams.x;
                        startY = resultParams.y;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downRawX);
                        int dy = Math.round(event.getRawY() - downRawY);
                        if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) moved = true;
                        moveTranslationBox(startX + dx, startY + dy, false);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) saveTranslationPosition();
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(translateButton, translateParams);
            wm.addView(selectButton, selectParams);
            wm.addView(retryButton, retryParams);
            wm.addView(autoButton, autoParams);
            wm.addView(resultView, resultParams);
            applyControlLayout();
            applyTranslationPosition();
        } catch (Exception e) {
            toast("无法创建悬浮窗：" + safe(e));
            stopSelf();
        }
    }

    private TextView smallButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(18f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(roundRect(0xDD222222, dp(20)));
        return button;
    }

    private void updateAutoButtonAppearance() {
        if (autoButton == null) return;
        int color = autoTranslateEnabled ? 0xDD2E7D32 : 0xDD222222;
        autoButton.setBackground(roundRect(color, dp(20)));
        autoButton.setText("自");
        autoButton.setContentDescription(autoTranslateEnabled ? "自动翻译已开启" : "自动翻译已关闭");
    }

    private WindowManager.LayoutParams buttonParams(int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(54), dp(54),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = x;
        p.y = y;
        return p;
    }

    private void applyControlLayout() {
        if (wm == null || translateButton == null || selectButton == null
                || retryButton == null || autoButton == null) return;
        if (translateParams == null || selectParams == null
                || retryParams == null || autoParams == null) return;

        DisplayMetrics dm = currentMetrics();

        if (isLandscape(dm)) {
            // 横屏：右上角横向排列，避免竖向空间不足或旋转后重叠。
            int y = dp(18);
            translateParams.x = dp(12);
            selectParams.x = dp(74);
            retryParams.x = dp(136);
            autoParams.x = dp(198);
            translateParams.y = y;
            selectParams.y = y;
            retryParams.y = y;
            autoParams.y = y;
        } else {
            // 竖屏：保持右侧纵向排列。
            int x = dp(12);
            translateParams.x = x;
            selectParams.x = x;
            retryParams.x = x;
            autoParams.x = x;
            translateParams.y = dp(220);
            selectParams.y = dp(282);
            retryParams.y = dp(344);
            autoParams.y = dp(406);
        }

        try { wm.updateViewLayout(translateButton, translateParams); } catch (Exception ignored) {}
        try { wm.updateViewLayout(selectButton, selectParams); } catch (Exception ignored) {}
        try { wm.updateViewLayout(retryButton, retryParams); } catch (Exception ignored) {}
        try { wm.updateViewLayout(autoButton, autoParams); } catch (Exception ignored) {}
    }

    private DisplayMetrics currentMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private boolean isLandscape(DisplayMetrics dm) {
        return dm.widthPixels >= dm.heightPixels;
    }

    private String orientationPrefix(DisplayMetrics dm) {
        return isLandscape(dm) ? "land_" : "port_";
    }

    private void showSelectionOverlay() {
        if (wm == null || selectionView != null || busy) return;

        ensureCaptureSurface(false);
        final DisplayMetrics dm = currentMetrics();
        final String key = orientationPrefix(dm);

        float left = prefs.getFloat(key + "crop_left", 0f) * dm.widthPixels;
        float top = prefs.getFloat(key + "crop_top", 1f - fallbackCropRatio) * dm.heightPixels;
        float right = prefs.getFloat(key + "crop_right", 1f) * dm.widthPixels;
        float bottom = prefs.getFloat(key + "crop_bottom", 1f) * dm.heightPixels;

        selectionView = new SelectionOverlayView(
                this,
                left, top, right, bottom,
                new SelectionOverlayView.Listener() {
                    @Override
                    public void onSelected(float l, float t, float r, float b) {
                        prefs.edit()
                                .putFloat(key + "crop_left", l)
                                .putFloat(key + "crop_top", t)
                                .putFloat(key + "crop_right", r)
                                .putFloat(key + "crop_bottom", b)
                                .apply();
                        lastAutoSignature = null;
                        pendingAutoSignature = null;
                        hideSelectionOverlay();
                        toast((isLandscape(dm) ? "横屏" : "竖屏") + "识别区域已保存");
                    }

                    @Override
                    public void onCancelled() {
                        hideSelectionOverlay();
                    }
                });

        selectionParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        selectionParams.gravity = Gravity.TOP | Gravity.START;

        try {
            hideAllOverlays();
            wm.addView(selectionView, selectionParams);
        } catch (Exception e) {
            selectionView = null;
            restoreControls();
            toast("无法进入框选模式：" + safe(e));
        }
    }

    private void hideSelectionOverlay() {
        if (selectionView != null && wm != null) {
            try { wm.removeView(selectionView); } catch (Exception ignored) {}
            selectionView = null;
        }
        restoreControls();
    }

    private void restoreControls() {
        if (translateButton != null) translateButton.setVisibility(View.VISIBLE);
        if (selectButton != null) selectButton.setVisibility(View.VISIBLE);
        if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
        if (autoButton != null) autoButton.setVisibility(View.VISIBLE);
    }

    private void hideAllOverlays() {
        if (translateButton != null) translateButton.setVisibility(View.INVISIBLE);
        if (selectButton != null) selectButton.setVisibility(View.INVISIBLE);
        if (retryButton != null) retryButton.setVisibility(View.INVISIBLE);
        if (autoButton != null) autoButton.setVisibility(View.INVISIBLE);
        if (resultView != null) resultView.setVisibility(View.GONE);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private synchronized void ensureCaptureSurface(boolean force) {
        if (projection == null || wm == null) return;

        DisplayMetrics dm = currentMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int density = dm.densityDpi;

        if (!force && reader != null && display != null
                && width == captureWidth && height == captureHeight
                && density == captureDensity) {
            applyControlLayout();
            applyTranslationPosition();
            return;
        }

        if (display != null) {
            try { display.release(); } catch (Exception ignored) {}
            display = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }

        captureWidth = width;
        captureHeight = height;
        captureDensity = density;
        lastAutoSignature = null;
        pendingAutoSignature = null;

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        display = projection.createVirtualDisplay(
                "SakuraMate8Lite",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                capture);

        applyControlLayout();
        applyTranslationPosition();
    }

    private void requestCapture(boolean autoMode, boolean forceRetranslate) {
        if (busy || selectionView != null) return;

        ensureCaptureSurface(false);
        if (reader == null) {
            if (!autoMode) showResultText("截图失败：屏幕捕获未就绪");
            return;
        }

        busy = true;
        pendingAutoMode = autoMode;
        pendingForceRetranslate = forceRetranslate;

        oldTranslateVisibility = translateButton == null ? View.GONE : translateButton.getVisibility();
        oldSelectVisibility = selectButton == null ? View.GONE : selectButton.getVisibility();
        oldRetryVisibility = retryButton == null ? View.GONE : retryButton.getVisibility();
        oldAutoVisibility = autoButton == null ? View.GONE : autoButton.getVisibility();
        oldResultVisibility = resultView == null ? View.GONE : resultView.getVisibility();

        if (autoMode && !forceRetranslate) {
            // First capture is a phone-only probe. Do not hide overlays and do not use network.
            capture.postDelayed(new Runnable() {
                @Override public void run() {
                    captureAutoProbeFrame(0);
                }
            }, 40L);
            return;
        }

        startCleanCapture(false);
    }

    private void startCleanCapture(boolean autoMode) {
        boolean hidden = autoMode ? hideAutoIntersections() : hideEverythingForCapture();
        long discardDelay = hidden ? 100L : 40L;
        long captureDelay = hidden ? 190L : 100L;

        capture.postDelayed(new Runnable() {
            @Override public void run() {
                discardFrame();
            }
        }, discardDelay);

        capture.postDelayed(new Runnable() {
            @Override public void run() {
                captureFrame(0);
            }
        }, captureDelay);
    }

    private void captureAutoProbeFrame(final int attempt) {
        Image image = null;
        try {
            if (reader == null) throw new Exception("截图设备未就绪");
            image = reader.acquireLatestImage();
            if (image == null) {
                if (attempt < 10) {
                    capture.postDelayed(new Runnable() {
                        @Override public void run() {
                            captureAutoProbeFrame(attempt + 1);
                        }
                    }, 50L);
                    return;
                }
                throw new Exception("没有取得新画面");
            }

            // Level 1: dHash only. Static frames usually stop here after ~153 pixel reads.
            AutoSignature dhashOnly = dHashFromImage(image);
            if (lastAutoSignature != null && !dHashChanged(lastAutoSignature, dhashOnly)) {
                pendingAutoSignature = null;
                main.post(new Runnable() {
                    @Override public void run() {
                        finishBusy();
                    }
                });
                return;
            }

            // Level 2: only a suspicious dHash result pays for the 32x12 MAD sample.
            AutoSignature candidate = new AutoSignature(
                    dhashOnly.hashBits,
                    dhashOnly.hashValid,
                    madSampleFromImage(image));

            if (lastAutoSignature != null && !madChanged(lastAutoSignature, candidate)) {
                // Treat this as harmless visual noise and absorb the new local baseline.
                lastAutoSignature = candidate;
                pendingAutoSignature = null;
                main.post(new Runnable() {
                    @Override public void run() {
                        finishBusy();
                    }
                });
                return;
            }

            pendingAutoSignature = candidate;

            // Level 3: a second phone-only frame must settle before any JPEG/network work.
            capture.postDelayed(new Runnable() {
                @Override public void run() {
                    captureAutoStabilityFrame(0);
                }
            }, AUTO_STABILITY_DELAY_MS);
        } catch (final Exception e) {
            main.post(new Runnable() {
                @Override public void run() {
                    finishBusy();
                    handleFailure("截图失败：" + safe(e), true);
                }
            });
        } finally {
            if (image != null) image.close();
        }
    }

    private void captureAutoStabilityFrame(final int attempt) {
        Image image = null;
        try {
            if (reader == null) throw new Exception("截图设备未就绪");
            image = reader.acquireLatestImage();
            if (image == null) {
                if (attempt < 8) {
                    capture.postDelayed(new Runnable() {
                        @Override public void run() {
                            captureAutoStabilityFrame(attempt + 1);
                        }
                    }, 50L);
                    return;
                }
                throw new Exception("没有取得稳定画面");
            }

            AutoSignature second = buildAutoSignature(image);
            if (pendingAutoSignature == null
                    || dHashChanged(pendingAutoSignature, second)
                    || madChanged(pendingAutoSignature, second)) {
                // Still moving: animation/transition. Stay completely local this round.
                pendingAutoSignature = null;
                main.post(new Runnable() {
                    @Override public void run() {
                        finishBusy();
                    }
                });
                return;
            }

            pendingAutoSignature = second;

            // Stable meaningful change confirmed. Only now hide intersecting overlays,
            // capture a clean frame, JPEG-encode it and allow it onto the LAN.
            main.post(new Runnable() {
                @Override public void run() {
                    boolean hidden = hideAutoIntersections();
                    long discardDelay = hidden ? 100L : 40L;
                    long captureDelay = hidden ? 190L : 100L;

                    capture.postDelayed(new Runnable() {
                        @Override public void run() {
                            discardFrame();
                        }
                    }, discardDelay);

                    capture.postDelayed(new Runnable() {
                        @Override public void run() {
                            captureFrame(0);
                        }
                    }, captureDelay);
                }
            });
        } catch (final Exception e) {
            main.post(new Runnable() {
                @Override public void run() {
                    pendingAutoSignature = null;
                    finishBusy();
                    handleFailure("截图失败：" + safe(e), true);
                }
            });
        } finally {
            if (image != null) image.close();
        }
    }

    private boolean hideEverythingForCapture() {
        boolean hidden = false;
        if (translateButton != null && translateButton.getVisibility() == View.VISIBLE) {
            translateButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (selectButton != null && selectButton.getVisibility() == View.VISIBLE) {
            selectButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (retryButton != null && retryButton.getVisibility() == View.VISIBLE) {
            retryButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (autoButton != null && autoButton.getVisibility() == View.VISIBLE) {
            autoButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (resultView != null && resultView.getVisibility() == View.VISIBLE) {
            resultView.setVisibility(View.INVISIBLE); hidden = true;
        }
        return hidden;
    }

    private boolean hideAutoIntersections() {
        Rect crop = currentCropRect();
        boolean hidden = false;

        if (translateButton != null && translateButton.getVisibility() == View.VISIBLE
                && Rect.intersects(crop, overlayRect(translateButton, translateParams))) {
            translateButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (selectButton != null && selectButton.getVisibility() == View.VISIBLE
                && Rect.intersects(crop, overlayRect(selectButton, selectParams))) {
            selectButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (retryButton != null && retryButton.getVisibility() == View.VISIBLE
                && Rect.intersects(crop, overlayRect(retryButton, retryParams))) {
            retryButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (autoButton != null && autoButton.getVisibility() == View.VISIBLE
                && Rect.intersects(crop, overlayRect(autoButton, autoParams))) {
            autoButton.setVisibility(View.INVISIBLE); hidden = true;
        }
        if (resultView != null && resultView.getVisibility() == View.VISIBLE
                && Rect.intersects(crop, overlayRect(resultView, resultParams))) {
            resultView.setVisibility(View.INVISIBLE); hidden = true;
        }
        return hidden;
    }

    private Rect currentCropRect() {
        DisplayMetrics dm = currentMetrics();
        String key = orientationPrefix(dm);
        int left = clamp(Math.round(prefs.getFloat(key + "crop_left", 0f) * dm.widthPixels), 0, dm.widthPixels - 1);
        int top = clamp(Math.round(prefs.getFloat(key + "crop_top", 1f - fallbackCropRatio) * dm.heightPixels), 0, dm.heightPixels - 1);
        int right = clamp(Math.round(prefs.getFloat(key + "crop_right", 1f) * dm.widthPixels), left + 1, dm.widthPixels);
        int bottom = clamp(Math.round(prefs.getFloat(key + "crop_bottom", 1f) * dm.heightPixels), top + 1, dm.heightPixels);
        return new Rect(left, top, right, bottom);
    }

    private Rect overlayRect(View view, WindowManager.LayoutParams params) {
        DisplayMetrics dm = currentMetrics();
        int width = params.width > 0 ? params.width : Math.max(view.getWidth(), dp(54));
        int height = params.height > 0 ? params.height : Math.max(view.getHeight(), dp(72));

        int gravity = Gravity.getAbsoluteGravity(
                params.gravity,
                getResources().getConfiguration().getLayoutDirection());

        int left;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) {
            left = dm.widthPixels - params.x - width;
        } else {
            left = params.x;
        }

        int top;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            top = dm.heightPixels - params.y - height;
        } else {
            top = params.y;
        }

        return new Rect(left, top, left + width, top + height);
    }

    private void restoreAfterCapture() {
        if (translateButton != null) translateButton.setVisibility(oldTranslateVisibility);
        if (selectButton != null) selectButton.setVisibility(oldSelectVisibility);
        if (retryButton != null) retryButton.setVisibility(oldRetryVisibility);
        if (autoButton != null) autoButton.setVisibility(oldAutoVisibility);
        if (resultView != null) resultView.setVisibility(oldResultVisibility);

        if (!pendingAutoMode && translateButton != null) {
            translateButton.setText("…");
        }
    }

    private void discardFrame() {
        Image image = null;
        try {
            if (reader != null) image = reader.acquireLatestImage();
        } catch (Exception ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void captureFrame(final int attempt) {
        Image image = null;
        try {
            if (reader == null) throw new Exception("截图设备未就绪");
            image = reader.acquireLatestImage();
            if (image == null) {
                if (attempt < 18) {
                    capture.postDelayed(new Runnable() {
                        @Override public void run() {
                            captureFrame(attempt + 1);
                        }
                    }, 60);
                    return;
                }
                throw new Exception("没有取得新画面");
            }

            final boolean autoMode = pendingAutoMode;
            if (autoMode && !autoTranslateEnabled) {
                main.post(new Runnable() {
                    @Override public void run() {
                        restoreAfterCapture();
                        finishBusy();
                    }
                });
                return;
            }

            final byte[] jpeg = toJpeg(image);
            final boolean forceRetranslate = pendingForceRetranslate;
            final boolean trackAutoBaseline = autoMode
                    || prefs.getBoolean("auto_translate", false);
            final AutoSignature cleanSignature = trackAutoBaseline
                    ? buildAutoSignature(image)
                    : null;

            main.post(new Runnable() {
                @Override public void run() {
                    restoreAfterCapture();
                }
            });

            network.execute(new Runnable() {
                @Override public void run() {
                    send(jpeg, autoMode, forceRetranslate, cleanSignature);
                }
            });
        } catch (final Exception e) {
            final boolean autoMode = pendingAutoMode;
            main.post(new Runnable() {
                @Override public void run() {
                    restoreAfterCapture();
                    finishBusy();
                    handleFailure("截图失败：" + safe(e), autoMode);
                }
            });
        } finally {
            if (image != null) image.close();
        }
    }

    private AutoSignature buildAutoSignature(Image image) {
        AutoSignature dhash = dHashFromImage(image);
        return new AutoSignature(dhash.hashBits, dhash.hashValid, madSampleFromImage(image));
    }

    private AutoSignature dHashFromImage(Image image) {
        int[] gray = sampleGrayGrid(image, AUTO_DHASH_COLS, AUTO_DHASH_ROWS);
        long[] bits = new long[AUTO_DHASH_WORDS];
        long[] valid = new long[AUTO_DHASH_WORDS];

        int bitIndex = 0;
        for (int y = 0; y < AUTO_DHASH_ROWS; y++) {
            int row = y * AUTO_DHASH_COLS;
            for (int x = 0; x < AUTO_DHASH_COLS - 1; x++, bitIndex++) {
                int left = gray[row + x];
                int right = gray[row + x + 1];
                if (left < 0 || right < 0) continue;

                setBit(valid, bitIndex);
                if (left > right) setBit(bits, bitIndex);
            }
        }
        return new AutoSignature(bits, valid, null);
    }

    private int[] madSampleFromImage(Image image) {
        return sampleGrayGrid(image, AUTO_MAD_COLS, AUTO_MAD_ROWS);
    }

    private int[] sampleGrayGrid(Image image, int cols, int rows) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        Rect cropRect = currentCropRect();
        Rect[] masks = visibleOverlayRects();

        int[] sample = new int[cols * rows];

        for (int gy = 0; gy < rows; gy++) {
            int y = cropRect.top + ((gy * 2 + 1) * cropRect.height()) / (rows * 2);
            y = clamp(y, 0, height - 1);

            for (int gx = 0; gx < cols; gx++) {
                int x = cropRect.left + ((gx * 2 + 1) * cropRect.width()) / (cols * 2);
                x = clamp(x, 0, width - 1);
                int index = gy * cols + gx;

                if (isMaskedPoint(x, y, masks)) {
                    sample[index] = -1;
                    continue;
                }

                int offset = y * rowStride + x * pixelStride;
                if (offset < 0 || offset + 2 >= buffer.limit()) {
                    sample[index] = -1;
                    continue;
                }

                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;
                sample[index] = (r * 77 + g * 150 + b * 29) >> 8;
            }
        }
        return sample;
    }

    private void setBit(long[] words, int bitIndex) {
        words[bitIndex >> 6] |= 1L << (bitIndex & 63);
    }

    private Rect[] visibleOverlayRects() {
        Rect[] masks = new Rect[5];
        masks[0] = visibleOverlayRect(translateButton, translateParams);
        masks[1] = visibleOverlayRect(selectButton, selectParams);
        masks[2] = visibleOverlayRect(retryButton, retryParams);
        masks[3] = visibleOverlayRect(autoButton, autoParams);
        masks[4] = visibleOverlayRect(resultView, resultParams);
        return masks;
    }

    private Rect visibleOverlayRect(View view, WindowManager.LayoutParams params) {
        if (view == null || params == null || view.getVisibility() != View.VISIBLE) return null;
        return overlayRect(view, params);
    }

    private boolean isMaskedPoint(int x, int y, Rect[] masks) {
        for (Rect mask : masks) {
            if (mask != null && mask.contains(x, y)) return true;
        }
        return false;
    }

    private boolean dHashChanged(AutoSignature previous, AutoSignature current) {
        if (previous == null || current == null
                || previous.hashBits == null || current.hashBits == null
                || previous.hashValid == null || current.hashValid == null
                || previous.hashBits.length != current.hashBits.length) {
            return true;
        }

        int comparableBits = 0;
        int differentBits = 0;
        for (int i = 0; i < previous.hashBits.length; i++) {
            long common = previous.hashValid[i] & current.hashValid[i];
            comparableBits += Long.bitCount(common);
            differentBits += Long.bitCount((previous.hashBits[i] ^ current.hashBits[i]) & common);
        }

        if (comparableBits < 32) return true;

        int threshold = Math.max(2,
                Math.round(AUTO_DHASH_UNCHANGED_BITS * (comparableBits / (float) AUTO_DHASH_BITS)));
        return differentBits > threshold;
    }

    private boolean madChanged(AutoSignature previous, AutoSignature current) {
        if (previous == null || current == null
                || previous.madSample == null || current.madSample == null
                || previous.madSample.length != current.madSample.length) {
            return true;
        }

        int comparable = 0;
        int changed = 0;
        long totalDiff = 0;

        for (int i = 0; i < previous.madSample.length; i++) {
            int a = previous.madSample[i];
            int b = current.madSample[i];
            if (a < 0 || b < 0) continue;

            comparable++;
            int diff = Math.abs(a - b);
            totalDiff += diff;
            if (diff >= AUTO_MAD_PIXEL_DIFF) changed++;
        }

        if (comparable < 96) return true;

        float changedRatio = changed / (float) comparable;
        float averageDiff = totalDiff / (float) comparable;
        return changedRatio >= AUTO_MAD_CHANGED_RATIO
                || averageDiff >= AUTO_MAD_AVERAGE_DIFF;
    }

    private byte[] toJpeg(Image image) throws Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        DisplayMetrics dm = currentMetrics();
        String key = orientationPrefix(dm);

        float lf = prefs.getFloat(key + "crop_left", 0f);
        float tf = prefs.getFloat(key + "crop_top", 1f - fallbackCropRatio);
        float rf = prefs.getFloat(key + "crop_right", 1f);
        float bf = prefs.getFloat(key + "crop_bottom", 1f);

        int left = clamp(Math.round(lf * width), 0, width - 1);
        int top = clamp(Math.round(tf * height), 0, height - 1);
        int right = clamp(Math.round(rf * width), left + 1, width);
        int bottom = clamp(Math.round(bf * height), top + 1, height);

        Bitmap cropped = Bitmap.createBitmap(padded, left, top, right - left, bottom - top);
        padded.recycle();

        Bitmap output = cropped;
        if (cropped.getWidth() > 1600) {
            int h = Math.max(1, Math.round(cropped.getHeight() * (1600f / cropped.getWidth())));
            output = Bitmap.createScaledBitmap(cropped, 1600, h, true);
            if (output != cropped) cropped.recycle();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!output.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) {
            output.recycle();
            throw new Exception("JPEG 编码失败");
        }
        output.recycle();
        return bytes.toByteArray();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void send(byte[] jpeg, final boolean autoMode, final boolean forceRetranslate,
                      final AutoSignature cleanSignature) {
        try {
            String gameId = prefs.getString("game_id", "default");
            String sessionId = prefs.getString("session_id", "default");
            String sceneId = prefs.getString("scene_id", "default");
            boolean contextEnabled = prefs.getBoolean("context_enabled", true);

            final ApiClient.TranslationResult translated = ApiClient.translateImage(
                    endpoint,
                    secret,
                    jpeg,
                    gameId,
                    sessionId,
                    sceneId,
                    contextEnabled,
                    autoMode,
                    forceRetranslate);

            main.post(new Runnable() {
                @Override public void run() {
                    if (cleanSignature != null) {
                        lastAutoSignature = cleanSignature;
                    } else if (pendingAutoSignature != null) {
                        lastAutoSignature = pendingAutoSignature;
                    }
                    pendingAutoSignature = null;
                    autoBackoffUntil = 0L;
                    finishBusy();
                    if (translated.changed) {
                        showResult(translated);
                    }
                }
            });
        } catch (final Exception e) {
            main.post(new Runnable() {
                @Override public void run() {
                    finishBusy();
                    handleFailure("翻译失败：" + safe(e), autoMode);
                }
            });
        }
    }

    private void handleFailure(String text, boolean autoMode) {
        // Runtime failures/timeouts always use the configured translation overlay style.
        // Avoid system Toasts because they can cover the game's subtitle area.
        pendingAutoSignature = null;
        if (autoMode) {
            autoBackoffUntil = System.currentTimeMillis() + AUTO_FAILURE_BACKOFF_MS;
        }
        showResultText(formatFailureText(text));
    }

    private String formatFailureText(String text) {
        String value = text == null ? "" : text.trim();
        String lower = value.toLowerCase();

        if (lower.contains("timeout") || lower.contains("timed out")
                || value.contains("超时")) {
            return "⚠ 翻译超时，请检查后端";
        }
        if (lower.contains("connection refused") || lower.contains("failed to connect")
                || value.contains("连接失败")) {
            return "⚠ 后端连接失败";
        }
        if (value.contains("HTTP 401") || value.contains("HTTP 403")) {
            return "⚠ 后端认证失败";
        }

        value = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (value.length() > 56) {
            value = value.substring(0, 56) + "…";
        }
        if (value.startsWith("翻译失败：") || value.startsWith("截图失败：")) {
            return "⚠ " + value;
        }
        return "⚠ 翻译失败：" + value;
    }

    private void finishBusy() {
        busy = false;
        if (translateButton != null) translateButton.setText("译");
        pendingAutoMode = false;
        pendingForceRetranslate = false;
    }

    private void showResult(ApiClient.TranslationResult result) {
        if (resultView == null) return;

        String translation = processTranslationText(result.translation);

        // Compare the final Chinese itself. Even if OCR debug text changed,
        // identical Chinese must not refresh the overlay and cause a visible flash.
        if (lastDisplayedWasTranslation
                && resultView.getVisibility() == View.VISIBLE
                && translation.equals(lastSuccessfulTranslation)) {
            return;
        }

        applyTranslationStyle();
        applyTranslationPosition();

        String displayText = translation;
        if (prefs.getBoolean("show_ocr", false) && !result.ocrText.isEmpty()) {
            displayText = "原文：" + result.ocrText + "\n译文：" + translation;
        }

        if (prefs.getBoolean("no_wrap", false)) {
            displayText = displayText.replace('\r', ' ').replace('\n', ' ')
                    .replaceAll("\\s+", " ").trim();
        }

        lastSuccessfulTranslation = translation;
        lastDisplayedText = displayText;
        lastDisplayedWasTranslation = true;
        resultView.setText(displayText);
        resultView.setVisibility(View.VISIBLE);
    }

    private void showResultText(String text) {
        if (resultView == null) return;
        applyTranslationStyle();
        applyTranslationPosition();

        String displayText = text == null ? "" : text.trim();
        if (!lastDisplayedWasTranslation
                && displayText.equals(lastDisplayedText)
                && resultView.getVisibility() == View.VISIBLE) {
            return;
        }

        lastDisplayedText = displayText;
        lastDisplayedWasTranslation = false;
        resultView.setText(displayText);
        resultView.setVisibility(View.VISIBLE);
    }

    private void applyTranslationStyle() {
        if (resultView == null) return;

        float size = prefFloat("font_size", 16f, 10f, 40f);
        int foreground = prefColor("text_color", "#FFFFFF", Color.WHITE);
        int background = prefColor("bg_color", "#222222", 0xFF222222);
        int alphaPercent = prefInt("bg_alpha", 90, 0, 100);
        boolean singleLine = prefs.getBoolean("no_wrap", false);

        resultView.setTextSize(size);
        resultView.setTextColor(foreground);
        resultView.setBackground(roundRect(withAlpha(background, alphaPercent), dp(10)));
        resultView.setSingleLine(singleLine);
        resultView.setHorizontallyScrolling(singleLine);
        if (!singleLine) resultView.setMaxLines(10);
    }

    private String processTranslationText(String text) {
        String value = text == null ? "" : text;

        if (prefs.getBoolean("chinese_only", false)) {
            value = keepChineseText(value);
        }

        if (prefs.getBoolean("no_wrap", false)) {
            value = value.replace('\r', ' ').replace('\n', ' ');
            value = value.replaceAll("\\s+", " ").trim();
        } else {
            value = value.trim();
        }

        if (value.isEmpty()) return "（过滤后无中文内容）";
        return value;
    }

    private String keepChineseText(String text) {
        StringBuilder out = new StringBuilder();
        boolean lastSpace = false;

        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (isHan(cp) || Character.isDigit(cp) || isCommonPunctuation(cp)) {
                out.appendCodePoint(cp);
                lastSpace = false;
            } else if (Character.isWhitespace(cp) && !lastSpace && out.length() > 0) {
                out.append(' ');
                lastSpace = true;
            }
        }
        return out.toString().trim();
    }

    private boolean isHan(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private boolean isCommonPunctuation(int cp) {
        final String marks = "，。！？；：、“”‘’（）《》〈〉【】〔〕…—～·,.!?;:()[]{}+-×÷%=％￥¥#@&/|~^_*";
        return marks.indexOf(cp) >= 0;
    }

    private float prefFloat(String key, float fallback, float min, float max) {
        try {
            float v = Float.parseFloat(prefs.getString(key, String.valueOf(fallback)));
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int prefInt(String key, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(prefs.getString(key, String.valueOf(fallback)));
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int prefColor(String key, String fallbackText, int fallbackColor) {
        try {
            return Color.parseColor(prefs.getString(key, fallbackText));
        } catch (Exception e) {
            return fallbackColor;
        }
    }

    private int withAlpha(int rgb, int percent) {
        int alpha = Math.round(255f * Math.max(0, Math.min(100, percent)) / 100f);
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private void applyTranslationPosition() {
        if (wm == null || resultView == null || resultParams == null) return;

        DisplayMetrics dm = currentMetrics();
        String key = orientationPrefix(dm);

        int maxWidth = Math.max(dp(180), dm.widthPixels - dp(24));
        boolean singleLine = prefs.getBoolean("no_wrap", false);
        int desiredWidth = singleLine
                ? maxWidth
                : (isLandscape(dm) ? Math.min(dp(520), maxWidth) : Math.min(dp(360), maxWidth));
        resultParams.width = desiredWidth;

        float defaultX = 0.04f;
        float defaultY = isLandscape(dm) ? 0.08f : 0.62f;
        float fx = prefs.getFloat(key + "trans_x", defaultX);
        float fy = prefs.getFloat(key + "trans_y", defaultY);

        int x = Math.round(fx * dm.widthPixels);
        int y = Math.round(fy * dm.heightPixels);
        moveTranslationBox(x, y, false);
    }

    private void moveTranslationBox(int x, int y, boolean save) {
        if (wm == null || resultView == null || resultParams == null) return;

        DisplayMetrics dm = currentMetrics();
        int width = resultParams.width > 0 ? resultParams.width : dp(360);
        int estimatedHeight = resultView.getHeight() > 0 ? resultView.getHeight() : dp(100);

        int maxX = Math.max(0, dm.widthPixels - width);
        int maxY = Math.max(0, dm.heightPixels - Math.min(estimatedHeight, dm.heightPixels / 2));

        resultParams.x = clamp(x, 0, maxX);
        resultParams.y = clamp(y, 0, maxY);

        try {
            wm.updateViewLayout(resultView, resultParams);
        } catch (Exception ignored) {}

        if (save) saveTranslationPosition();
    }

    private void saveTranslationPosition() {
        if (resultParams == null) return;
        DisplayMetrics dm = currentMetrics();
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return;

        String key = orientationPrefix(dm);
        float fx = Math.max(0f, Math.min(1f, resultParams.x / (float) dm.widthPixels));
        float fy = Math.max(0f, Math.min(1f, resultParams.y / (float) dm.heightPixels));

        prefs.edit()
                .putFloat(key + "trans_x", fx)
                .putFloat(key + "trans_y", fy)
                .apply();
    }

    private void toast(final String text) {
        if (main == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(SakuraOverlayService.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String safe(Exception e) {
        String s = e.getMessage();
        return s == null ? e.getClass().getSimpleName() : s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        busy = false;
        if (main != null && autoRunnable != null) main.removeCallbacks(autoRunnable);

        hideSelectionOverlay();

        if (wm != null) {
            try { if (translateButton != null) wm.removeView(translateButton); } catch (Exception ignored) {}
            try { if (selectButton != null) wm.removeView(selectButton); } catch (Exception ignored) {}
            try { if (retryButton != null) wm.removeView(retryButton); } catch (Exception ignored) {}
            try { if (autoButton != null) wm.removeView(autoButton); } catch (Exception ignored) {}
            try { if (resultView != null) wm.removeView(resultView); } catch (Exception ignored) {}
        }

        if (display != null) {
            try { display.release(); } catch (Exception ignored) {}
            display = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (network != null) {
            network.shutdownNow();
            network = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
