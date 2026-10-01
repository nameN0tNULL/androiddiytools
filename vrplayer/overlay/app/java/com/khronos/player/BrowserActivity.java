package com.khronos.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class BrowserActivity extends Activity {
    private static final int REQUEST_READ_STORAGE = 1001;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<SmbFile> entries = new ArrayList<>();

    private EditText url;
    private EditText domain;
    private EditText user;
    private EditText password;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private String currentUrl;
    private Uri pendingExternalUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 16);

        url = field("smb://192.168.1.10/share/");
        domain = field("");
        user = field("");
        password = field("");
        password.setHint("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        url.setHint("SMB directory, e.g. smb://192.168.1.20/video/");
        domain.setHint("Domain (optional)");
        user.setHint("Username");

        LinearLayout buttons = new LinearLayout(this);
        Button connect = new Button(this);
        connect.setText("Connect / Refresh");
        Button up = new Button(this);
        up.setText("Up");
        buttons.addView(connect, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(up, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        status = new TextView(this);
        status.setText("Choose a local video from a file browser, or browse SMB here.");

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);

        root.addView(url);
        root.addView(domain);
        root.addView(user);
        root.addView(password);
        root.addView(buttons);
        root.addView(status);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        connect.setOnClickListener(v -> {
            history.clear();
            listDirectory(normalizeDirectory(url.getText().toString()));
        });

        up.setOnClickListener(v -> {
            if (!history.isEmpty()) listDirectory(history.pop());
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return;
            SmbFile f = entries.get(position);

            io.execute(() -> {
                try {
                    if (f.isDirectory()) {
                        final String next = normalizeDirectory(f.getPath());
                        runOnUiThread(() -> {
                            if (currentUrl != null) history.push(currentUrl);
                            listDirectory(next);
                        });
                    } else {
                        String name = f.getName().toLowerCase();
                        if (isVideoName(name)) {
                            runOnUiThread(() -> startSmbPlayback(f.getPath()));
                        } else {
                            runOnUiThread(() ->
                                    status.setText("Not a supported video container."));
                        }
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText(e.toString()));
                }
            });
        });

        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    private boolean handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }

        Uri uri = intent.getData();
        if (uri == null) {
            status.setText("The file browser did not provide a media URI.");
            return true;
        }

        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingExternalUri = uri;
            status.setText("Storage permission is required for this file.");
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_READ_STORAGE);
            return true;
        }

        if ("content".equalsIgnoreCase(scheme)) {
            int takeFlags = intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if ((intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                    && takeFlags != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException ignored) {
                    // Temporary URI permission is sufficient for immediate playback.
                }
            }
        }

        startExternalPlayback(uri);
        return true;
    }

    private void startExternalPlayback(Uri uri) {
        pendingExternalUri = null;
        status.setText("Opening selected file...");
        SmbBridgeService.startUri(this, uri);
        waitForBridge(true);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_READ_STORAGE) return;

        Uri uri = pendingExternalUri;
        pendingExternalUri = null;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && uri != null) {
            startExternalPlayback(uri);
        } else {
            status.setText("Storage permission was not granted.");
        }
    }

    private EditText field(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        return e;
    }

    private static boolean isVideoName(String name) {
        return name.endsWith(".mp4")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".mov")
                || name.endsWith(".m4v");
    }

    private String normalizeDirectory(String s) {
        s = s.trim();
        if (!s.startsWith("smb://")) s = "smb://" + s;
        if (!s.endsWith("/")) s += "/";
        return s;
    }

    private CIFSContext context() throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.enableSMB2", "true");
        props.setProperty("jcifs.smb.client.disableSMB1", "false");

        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        return base.withCredentials(new NtlmPasswordAuthenticator(
                domain.getText().toString().trim(),
                user.getText().toString().trim(),
                password.getText().toString()));
    }

    private void listDirectory(String dirUrl) {
        status.setText("Loading " + dirUrl);

        io.execute(() -> {
            try {
                CIFSContext ctx = context();
                SmbFile dir = new SmbFile(dirUrl, ctx);
                SmbFile[] files = dir.listFiles();

                Arrays.sort(files, Comparator
                        .comparing((SmbFile f) -> {
                            try {
                                return !f.isDirectory();
                            } catch (Exception e) {
                                return true;
                            }
                        })
                        .thenComparing(f -> f.getName().toLowerCase()));

                List<String> labels = new ArrayList<>();
                List<SmbFile> accepted = new ArrayList<>();

                for (SmbFile f : files) {
                    boolean isDir;
                    try {
                        isDir = f.isDirectory();
                    } catch (Exception e) {
                        continue;
                    }

                    String name = f.getName();
                    if (!isDir && !isVideoName(name.toLowerCase())) continue;

                    accepted.add(f);
                    labels.add((isDir ? "[DIR] " : "[VIDEO] ") + name);
                }

                runOnUiThread(() -> {
                    currentUrl = dirUrl;
                    url.setText(dirUrl);
                    entries.clear();
                    entries.addAll(accepted);
                    adapter.clear();
                    adapter.addAll(labels);
                    adapter.notifyDataSetChanged();
                    status.setText(accepted.size() + " entries");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("SMB error: " + e.getMessage()));
            }
        });
    }

    private void startSmbPlayback(String smbUrl) {
        status.setText("Opening SMB bridge...");
        SmbBridgeService.start(
                this,
                smbUrl,
                domain.getText().toString().trim(),
                user.getText().toString().trim(),
                password.getText().toString());

        waitForBridge(false);
    }

    private void waitForBridge(boolean finishBrowserAfterLaunch) {
        final long deadline = System.currentTimeMillis() + 15000;

        Runnable poll = new Runnable() {
            @Override
            public void run() {
                String error = SmbBridgeService.getLastError();
                if (error != null) {
                    status.setText("Bridge error: " + error);
                    return;
                }

                if (SmbBridgeService.isReady()) {
                    status.setText("Starting OpenXR...");
                    startActivity(new Intent(BrowserActivity.this, MainActivity.class));
                    if (finishBrowserAfterLaunch) finish();
                    return;
                }

                if (System.currentTimeMillis() > deadline) {
                    status.setText("Bridge timeout");
                    return;
                }

                handler.postDelayed(this, 100);
            }
        };

        handler.post(poll);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
