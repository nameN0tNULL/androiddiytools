package com.sakuralite.mate8;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_CAPTURE = 1002;

    private EditText endpoint;
    private EditText secret;
    private EditText crop;
    private EditText gameId;
    private EditText sceneId;
    private EditText fontSize;
    private EditText textColor;
    private EditText bgColor;
    private EditText bgAlpha;
    private CheckBox contextEnabled;
    private CheckBox showOcr;
    private CheckBox noWrap;
    private CheckBox chineseOnly;
    private TextView sessionView;
    private TextView preview;
    private TextView status;
    private SharedPreferences prefs;
    private String sessionId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("sakura_lite", MODE_PRIVATE);
        sessionId = prefs.getString("session_id", "");
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            prefs.edit().putString("session_id", sessionId).apply();
        }
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        root.addView(text("Sakura Mate8 Lite", 24, Color.BLACK));

        TextView intro = text(
                "Android 7 / 无 Google 服务。Mate 8 负责框选、截图和悬浮显示；Mac Bridge 负责 Vision OCR、Sakura v3.7 上下文翻译和质量校验。",
                15, Color.DKGRAY);
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        root.addView(label("Mac Bridge 地址"));
        endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("http://192.168.1.50:8090/translate-image");
        endpoint.setText(prefs.getString("endpoint", "http://192.168.1.50:8090/translate-image"));
        root.addView(endpoint, full());

        root.addView(label("Bridge Key"));
        secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(prefs.getString("secret", ""));
        root.addView(secret, full());

        Button test = new Button(this);
        test.setText("测试后端服务");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                testBackend();
            }
        });
        root.addView(test, full());

        TextView qualityTitle = text("翻译质量 / 上下文", 18, Color.BLACK);
        qualityTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(qualityTitle);

        root.addView(label("游戏 ID（不同游戏建议不同名称）"));
        gameId = new EditText(this);
        gameId.setSingleLine(true);
        gameId.setText(prefs.getString("game_id", "default"));
        root.addView(gameId, full());

        root.addView(label("场景 ID（可用章节/路线名，默认 default）"));
        sceneId = new EditText(this);
        sceneId.setSingleLine(true);
        sceneId.setText(prefs.getString("scene_id", "default"));
        root.addView(sceneId, full());

        sessionView = text("会话 ID：" + sessionId, 12, Color.GRAY);
        sessionView.setPadding(0, dp(6), 0, dp(4));
        root.addView(sessionView);

        contextEnabled = new CheckBox(this);
        contextEnabled.setText("启用连续上下文（使用最近译文 History）");
        contextEnabled.setChecked(prefs.getBoolean("context_enabled", true));
        root.addView(contextEnabled, full());

        showOcr = new CheckBox(this);
        showOcr.setText("悬浮框同时显示 OCR 日文原文（调试用）");
        showOcr.setChecked(prefs.getBoolean("show_ocr", false));
        root.addView(showOcr, full());

        Button clearContext = new Button(this);
        clearContext.setText("清空当前后端上下文");
        clearContext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                resetBackendContext(false);
            }
        });
        root.addView(clearContext, full());

        Button newSession = new Button(this);
        newSession.setText("新建会话并清空上下文");
        newSession.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                resetBackendContext(true);
            }
        });
        root.addView(newSession, full());

        root.addView(label("屏幕底部识别比例（0.35 - 1.0；未框选时使用）"));
        crop = new EditText(this);
        crop.setSingleLine(true);
        crop.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        crop.setText(prefs.getString("crop", "0.55"));
        root.addView(crop, full());

        TextView styleTitle = text("译文显示设置", 18, Color.BLACK);
        styleTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(styleTitle);

        root.addView(label("字体大小（sp，10 - 40）"));
        fontSize = new EditText(this);
        fontSize.setSingleLine(true);
        fontSize.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fontSize.setText(prefs.getString("font_size", "16"));
        root.addView(fontSize, full());

        root.addView(label("字体颜色（#RRGGBB）"));
        textColor = new EditText(this);
        textColor.setSingleLine(true);
        textColor.setText(prefs.getString("text_color", "#FFFFFF"));
        root.addView(textColor, full());

        Button textColorPicker = colorPickerButton(
                textColor, "字体颜色", Color.WHITE, "#FFFFFF");
        root.addView(textColorPicker, full());

        root.addView(label("背景颜色（#RRGGBB）"));
        bgColor = new EditText(this);
        bgColor.setSingleLine(true);
        bgColor.setText(prefs.getString("bg_color", "#222222"));
        root.addView(bgColor, full());

        Button bgColorPicker = colorPickerButton(
                bgColor, "背景颜色", 0xFF222222, "#222222");
        root.addView(bgColorPicker, full());

        root.addView(label("背景不透明度（0 - 100）"));
        bgAlpha = new EditText(this);
        bgAlpha.setSingleLine(true);
        bgAlpha.setInputType(InputType.TYPE_CLASS_NUMBER);
        bgAlpha.setText(prefs.getString("bg_alpha", "90"));
        root.addView(bgAlpha, full());

        noWrap = new CheckBox(this);
        noWrap.setText("内容单行显示，不换行");
        noWrap.setChecked(prefs.getBoolean("no_wrap", false));
        root.addView(noWrap, full());

        chineseOnly = new CheckBox(this);
        chineseOnly.setText("仅保留中文、数字和常用标点（移除英文/日文等）");
        chineseOnly.setChecked(prefs.getBoolean("chinese_only", false));
        root.addView(chineseOnly, full());

        preview = new TextView(this);
        preview.setText("译文样式预览：今天只有我们两个人呢……");
        preview.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams previewParams = full();
        previewParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(preview, previewParams);

        Button previewButton = new Button(this);
        previewButton.setText("预览显示样式");
        previewButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                applyPreview();
            }
        });
        root.addView(previewButton, full());

        Button save = new Button(this);
        save.setText("保存配置");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                applyPreview();
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(save, full());

        TextView startTitle = text("开始翻译", 18, Color.BLACK);
        startTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(startTitle);

        Button start = new Button(this);
        start.setText("启动悬浮翻译");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                startFlow();
            }
        });
        root.addView(start, full());

        Button stop = new Button(this);
        stop.setText("停止悬浮翻译");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, SakuraOverlayService.class));
                setStatus("已停止");
            }
        });
        root.addView(stop, full());

        status = text("状态：未启动", 14, Color.DKGRAY);
        status.setPadding(0, dp(14), 0, dp(8));
        root.addView(status);

        root.addView(text(
                "启动悬浮翻译后，点“自”开启/关闭自动翻译。开启后每 2 秒先在手机本地做低功耗 dHash 检测；疑似变化时才做 32×12 灰度差分，并在约 250ms 后确认稳定。静止画面不会上传。",
                13, Color.GRAY));

        setContentView(scroll);
        applyPreview();
    }

    private void testBackend() {
        final String e = endpoint.getText().toString().trim();
        final String s = secret.getText().toString();
        setStatus("正在测试后端 /health…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String response = ApiClient.testConnection(e, s);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("后端连接正常");
                            Toast.makeText(MainActivity.this,
                                    "后端连接正常\n" + response,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception ex) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("后端测试失败：" + safe(ex));
                            Toast.makeText(MainActivity.this,
                                    "测试失败：" + safe(ex),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "BridgeHealthTest").start();
    }

    private void resetBackendContext(final boolean createNewSession) {
        final String oldSession = sessionId;
        final String e = endpoint.getText().toString().trim();
        final String s = secret.getText().toString();
        final String g = normalizedId(gameId.getText().toString(), "default");
        final String scene = normalizedId(sceneId.getText().toString(), "default");
        setStatus("正在清空后端上下文…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ApiClient.resetContext(e, s, g, oldSession, scene);
                    if (createNewSession) {
                        sessionId = UUID.randomUUID().toString();
                        prefs.edit().putString("session_id", sessionId).apply();
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            sessionView.setText("会话 ID：" + sessionId);
                            setStatus(createNewSession ? "已新建会话" : "当前上下文已清空");
                            Toast.makeText(MainActivity.this,
                                    createNewSession ? "已新建会话" : "上下文已清空",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception ex) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("清空上下文失败：" + safe(ex));
                        }
                    });
                }
            }
        }, "ResetContext").start();
    }

    private void savePrefs() {
        String e = endpoint.getText().toString().trim();
        if (e.isEmpty()) e = "http://192.168.1.50:8090/translate-image";
        float c = parseCrop(crop.getText().toString());
        float fs = parseFontSize(fontSize.getText().toString());
        String tc = normalizeColor(textColor.getText().toString(), "#FFFFFF");
        String bc = normalizeColor(bgColor.getText().toString(), "#222222");
        int alpha = parseAlpha(bgAlpha.getText().toString());
        String g = normalizedId(gameId.getText().toString(), "default");
        String scene = normalizedId(sceneId.getText().toString(), "default");

        endpoint.setText(e);
        crop.setText(String.valueOf(c));
        fontSize.setText(trimFloat(fs));
        textColor.setText(tc);
        bgColor.setText(bc);
        bgAlpha.setText(String.valueOf(alpha));
        gameId.setText(g);
        sceneId.setText(scene);

        prefs.edit()
                .putString("endpoint", e)
                .putString("secret", secret.getText().toString())
                .putString("crop", String.valueOf(c))
                .putString("game_id", g)
                .putString("scene_id", scene)
                .putString("session_id", sessionId)
                .putBoolean("context_enabled", contextEnabled.isChecked())
                .putBoolean("show_ocr", showOcr.isChecked())
                .putString("font_size", trimFloat(fs))
                .putString("text_color", tc)
                .putString("bg_color", bc)
                .putString("bg_alpha", String.valueOf(alpha))
                .putBoolean("no_wrap", noWrap.isChecked())
                .putBoolean("chinese_only", chineseOnly.isChecked())
                .apply();
    }

    private Button colorPickerButton(
            final EditText target,
            final String title,
            final int fallbackColor,
            final String fallbackText) {

        final Button button = new Button(this);
        int current = parseAndroidColor(
                normalizeColor(target.getText().toString(), fallbackText),
                fallbackColor);
        updateColorPickerButton(button, title, current);

        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showColorPicker(title, target, button, fallbackColor, fallbackText);
            }
        });
        return button;
    }

    private void showColorPicker(
            final String title,
            final EditText target,
            final Button swatchButton,
            final int fallbackColor,
            final String fallbackText) {

        int current = parseAndroidColor(
                normalizeColor(target.getText().toString(), fallbackText),
                fallbackColor);

        final int[] rgb = new int[] {
                Color.red(current),
                Color.green(current),
                Color.blue(current)
        };

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(8));

        final TextView sample = new TextView(this);
        sample.setTextSize(18);
        sample.setGravity(android.view.Gravity.CENTER);
        sample.setPadding(dp(12), dp(18), dp(12), dp(18));
        panel.addView(sample, full());

        final TextView hex = text("", 16, Color.DKGRAY);
        hex.setGravity(android.view.Gravity.CENTER);
        hex.setPadding(0, dp(8), 0, dp(8));
        panel.addView(hex, full());

        final TextView rLabel = text("", 14, Color.BLACK);
        final SeekBar rBar = colorSeekBar(rgb[0]);
        panel.addView(rLabel, full());
        panel.addView(rBar, full());

        final TextView gLabel = text("", 14, Color.BLACK);
        final SeekBar gBar = colorSeekBar(rgb[1]);
        panel.addView(gLabel, full());
        panel.addView(gBar, full());

        final TextView bLabel = text("", 14, Color.BLACK);
        final SeekBar bBar = colorSeekBar(rgb[2]);
        panel.addView(bLabel, full());
        panel.addView(bBar, full());

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                String value = colorHex(color);
                sample.setText(title + "预览\n" + value);
                sample.setTextColor(contrastColor(color));
                sample.setBackground(roundRect(color, dp(8)));
                hex.setText(value);
                rLabel.setText("R  " + rgb[0]);
                gLabel.setText("G  " + rgb[1]);
                bLabel.setText("B  " + rgb[2]);
            }
        };

        rBar.setOnSeekBarChangeListener(colorSeekListener(rgb, 0, refresh));
        gBar.setOnSeekBarChangeListener(colorSeekListener(rgb, 1, refresh));
        bBar.setOnSeekBarChangeListener(colorSeekListener(rgb, 2, refresh));
        refresh.run();

        new AlertDialog.Builder(this)
                .setTitle("选择" + title)
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                    target.setText(colorHex(color));
                    updateColorPickerButton(swatchButton, title, color);
                    applyPreview();
                })
                .show();
    }

    private SeekBar colorSeekBar(int value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setProgress(Math.max(0, Math.min(255, value)));
        return bar;
    }

    private SeekBar.OnSeekBarChangeListener colorSeekListener(
            final int[] rgb,
            final int index,
            final Runnable refresh) {

        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rgb[index] = progress;
                refresh.run();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private void updateColorPickerButton(Button button, String title, int color) {
        button.setText("取色：" + title + "  " + colorHex(color));
        button.setTextColor(contrastColor(color));
        button.setBackground(roundRect(color, dp(8)));
    }

    private int contrastColor(int color) {
        int luminance = (Color.red(color) * 299
                + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
        return luminance >= 150 ? Color.BLACK : Color.WHITE;
    }

    private String colorHex(int color) {
        return String.format("#%02X%02X%02X",
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void applyPreview() {
        if (preview == null) return;
        float fs = parseFontSize(fontSize.getText().toString());
        int fg = parseAndroidColor(normalizeColor(textColor.getText().toString(), "#FFFFFF"), Color.WHITE);
        int bg = parseAndroidColor(normalizeColor(bgColor.getText().toString(), "#222222"), 0xFF222222);
        int alpha = parseAlpha(bgAlpha.getText().toString());

        preview.setTextSize(fs);
        preview.setTextColor(fg);
        GradientDrawable d = new GradientDrawable();
        d.setColor(withAlpha(bg, alpha));
        d.setCornerRadius(dp(10));
        preview.setBackground(d);
        preview.setSingleLine(noWrap != null && noWrap.isChecked());
        preview.setHorizontallyScrolling(noWrap != null && noWrap.isChecked());
    }

    private void startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("请允许悬浮窗权限");
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager m = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (m == null) {
            setStatus("系统不支持屏幕捕获");
            return;
        }
        setStatus("请允许屏幕捕获");
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestCapture();
            else setStatus("未获得悬浮窗权限");
            return;
        }

        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                setStatus("屏幕捕获授权取消");
                return;
            }

            Intent service = new Intent(this, SakuraOverlayService.class);
            service.putExtra("endpoint", endpoint.getText().toString().trim());
            service.putExtra("secret", secret.getText().toString());
            service.putExtra("crop", parseCrop(crop.getText().toString()));
            service.putExtra("result_code", resultCode);
            service.putExtra("result_data", data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);

            setStatus("已启动；“译”翻译，“框”选区，“重”强制重译，“自”切换自动翻译");
        }
    }

    private String normalizedId(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? fallback : v;
    }

    private float parseCrop(String s) {
        try {
            float v = Float.parseFloat(s.trim());
            if (v < 0.35f) return 0.35f;
            if (v > 1.0f) return 1.0f;
            return v;
        } catch (Exception e) {
            return 0.55f;
        }
    }

    private float parseFontSize(String s) {
        try {
            float v = Float.parseFloat(s.trim());
            if (v < 10f) return 10f;
            if (v > 40f) return 40f;
            return v;
        } catch (Exception e) {
            return 16f;
        }
    }

    private int parseAlpha(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(0, Math.min(100, v));
        } catch (Exception e) {
            return 90;
        }
    }

    private String normalizeColor(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        if (!v.startsWith("#")) v = "#" + v;
        if (!v.matches("#[0-9a-fA-F]{6}")) return fallback;
        return v.toUpperCase();
    }

    private int parseAndroidColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int withAlpha(int rgb, int percent) {
        int a = Math.round(255f * Math.max(0, Math.min(100, percent)) / 100f);
        return Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private String trimFloat(float v) {
        if (Math.abs(v - Math.round(v)) < 0.001f) return String.valueOf(Math.round(v));
        return String.valueOf(v);
    }

    private String safe(Exception e) {
        String s = e.getMessage();
        return s == null ? e.getClass().getSimpleName() : s;
    }

    private TextView label(String s) {
        TextView v = text(s, 14, Color.BLACK);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void setStatus(String s) {
        if (status != null) status.setText("状态：" + s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
