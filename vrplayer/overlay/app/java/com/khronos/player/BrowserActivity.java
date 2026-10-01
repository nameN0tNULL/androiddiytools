package com.khronos.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.SmbFile;

public class BrowserActivity extends Activity {
    private static final String ROOT = "smb://";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> discoveredServers = new LinkedHashMap<>();

    private TextView title;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private String currentUrl = ROOT;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private static final class Entry {
        final String label;
        final String smbUrl;
        final boolean directory;

        Entry(String label, String smbUrl, boolean directory) {
            this.label = label;
            this.smbUrl = smbUrl;
            this.directory = directory;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 16);

        title = new TextView(this);
        title.setTextSize(22);
        title.setText("NAS");

        LinearLayout buttons = new LinearLayout(this);
        Button up = new Button(this);
        up.setText("返回上级");
        Button refresh = new Button(this);
        refresh.setText("刷新");
        buttons.addView(up, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(refresh, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        status = new TextView(this);
        status.setText("正在发现局域网 NAS…");

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);

        root.addView(title);
        root.addView(buttons);
        root.addView(status);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        up.setOnClickListener(v -> navigateUp());
        refresh.setOnClickListener(v -> refreshCurrent());

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return;
            Entry entry = entries.get(position);
            if (entry.directory) {
                if (currentUrl != null && !ROOT.equals(currentUrl)) {
                    history.push(currentUrl);
                }
                listDirectory(entry.smbUrl);
            } else {
                startPlayback(entry.smbUrl);
            }
        });

        startNsdDiscovery();
        showRootAndBrowse();
    }

    private void navigateUp() {
        if (!history.isEmpty()) {
            listDirectory(history.pop());
            return;
        }

        if (!ROOT.equals(currentUrl)) {
            showRootAndBrowse();
        }
    }

    private void refreshCurrent() {
        if (ROOT.equals(currentUrl)) {
            discoveredServers.clear();
            stopNsdDiscovery();
            startNsdDiscovery();
            showRootAndBrowse();
        } else {
            listDirectory(currentUrl);
        }
    }

    private void showRootAndBrowse() {
        currentUrl = ROOT;
        history.clear();
        title.setText("NAS");
        status.setText("正在发现局域网 NAS…");
        renderRoot();

        // jcifs root browsing catches environments where SMB/NetBIOS browsing
        // works even if the NAS does not advertise _smb._tcp over mDNS.
        io.execute(() -> {
            try {
                SmbFile root = new SmbFile(ROOT, guestContext());
                SmbFile[] files = root.listFiles();
                for (SmbFile f : files) {
                    try {
                        if (f.isDirectory()) {
                            addDiscoveredServer(cleanName(f.getName()), normalizeDir(f.getPath()));
                        }
                    } catch (Exception ignored) {
                    }
                }
                runOnUiThread(this::renderRoot);
            } catch (Exception ignored) {
                // mDNS discovery may still find the NAS.
            }
        });

        handler.postDelayed(() -> {
            if (ROOT.equals(currentUrl) && discoveredServers.isEmpty()) {
                status.setText("未发现可匿名访问的 NAS。请确认 NAS 已开启 SMB 和 Guest/匿名访问。");
            }
        }, 6000);
    }

    private void startNsdDiscovery() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {}

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!serviceInfo.getServiceType().startsWith("_smb._tcp")) return;
                resolve(serviceInfo);
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}

            @Override public void onDiscoveryStopped(String serviceType) {}

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                stopNsdDiscovery();
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                stopNsdDiscovery();
            }
        };

        try {
            nsdManager.discoverServices(
                    "_smb._tcp.",
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener);
        } catch (Exception ignored) {
        }
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    InetAddress host = info.getHost();
                    if (host == null) return;

                    String address = host.getHostAddress();
                    if (address == null || address.isEmpty()) return;

                    String display = info.getServiceName();
                    if (display == null || display.trim().isEmpty()) display = address;

                    final String label = display + "  (" + address + ")";
                    final String smbUrl = "smb://" + address + "/";
                    runOnUiThread(() -> {
                        addDiscoveredServer(label, smbUrl);
                        if (ROOT.equals(currentUrl)) renderRoot();
                    });
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void stopNsdDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        discoveryListener = null;
    }

    private void addDiscoveredServer(String label, String smbUrl) {
        if (smbUrl == null || smbUrl.isEmpty()) return;
        discoveredServers.put(
                smbUrl,
                new Entry("[NAS] " + label, normalizeDir(smbUrl), true));
    }

    private void renderRoot() {
        if (!ROOT.equals(currentUrl)) return;

        entries.clear();
        entries.addAll(discoveredServers.values());

        List<String> labels = new ArrayList<>();
        for (Entry e : entries) labels.add(e.label);

        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();

        if (!entries.isEmpty()) {
            status.setText("发现 " + entries.size() + " 个 NAS/SMB 服务");
        }
    }

    private void listDirectory(String dirUrl) {
        final String normalized = normalizeDir(dirUrl);
        currentUrl = normalized;
        title.setText(cleanPathForTitle(normalized));
        status.setText("正在读取…");
        entries.clear();
        adapter.clear();
        adapter.notifyDataSetChanged();

        io.execute(() -> {
            try {
                SmbFile dir;
                try {
                    dir = new SmbFile(normalized, guestContext());
                    dir.connect();
                } catch (Exception guestError) {
                    dir = new SmbFile(normalized, anonymousContext());
                    dir.connect();
                }

                SmbFile[] files = dir.listFiles();
                Arrays.sort(files, Comparator
                        .comparing((SmbFile f) -> {
                            try { return !f.isDirectory(); }
                            catch (Exception e) { return true; }
                        })
                        .thenComparing(f -> f.getName().toLowerCase()));

                List<Entry> next = new ArrayList<>();
                for (SmbFile f : files) {
                    boolean isDir;
                    try {
                        isDir = f.isDirectory();
                    } catch (Exception e) {
                        continue;
                    }

                    String name = cleanName(f.getName());
                    if (!isDir && !isVideoName(name.toLowerCase())) continue;

                    next.add(new Entry(
                            (isDir ? "[目录] " : "[视频] ") + name,
                            isDir ? normalizeDir(f.getPath()) : f.getPath(),
                            isDir));
                }

                runOnUiThread(() -> {
                    if (!normalized.equals(currentUrl)) return;

                    entries.clear();
                    entries.addAll(next);

                    List<String> labels = new ArrayList<>();
                    for (Entry e : next) labels.add(e.label);

                    adapter.clear();
                    adapter.addAll(labels);
                    adapter.notifyDataSetChanged();
                    status.setText(next.size() + " 项");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!normalized.equals(currentUrl)) return;
                    status.setText("无法读取。NAS 需要允许 Guest/匿名 SMB 访问。");
                });
            }
        });
    }

    private CIFSContext baseContext() throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.enableSMB2", "true");
        props.setProperty("jcifs.smb.client.disableSMB1", "false");
        props.setProperty("jcifs.resolveOrder", "DNS,BCAST");
        return new BaseContext(new PropertyConfiguration(props));
    }

    private CIFSContext guestContext() throws Exception {
        return baseContext().withGuestCrendentials();
    }

    private CIFSContext anonymousContext() throws Exception {
        return baseContext().withAnonymousCredentials();
    }

    private void startPlayback(String smbUrl) {
        status.setText("正在打开视频…");
        SmbBridgeService.start(this, smbUrl);

        final long deadline = System.currentTimeMillis() + 15000;
        Runnable poll = new Runnable() {
            @Override public void run() {
                String error = SmbBridgeService.getLastError();
                if (error != null) {
                    status.setText("无法打开视频：" + error);
                    return;
                }

                if (SmbBridgeService.isReady()) {
                    startActivity(new Intent(BrowserActivity.this, MainActivity.class));
                    return;
                }

                if (System.currentTimeMillis() > deadline) {
                    status.setText("打开视频超时");
                    return;
                }

                handler.postDelayed(this, 100);
            }
        };
        handler.post(poll);
    }

    private static boolean isVideoName(String name) {
        return name.endsWith(".mp4")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".mov")
                || name.endsWith(".m4v");
    }

    private static String normalizeDir(String value) {
        String s = value == null ? ROOT : value.trim();
        if (!s.startsWith("smb://")) s = "smb://" + s;
        if (!s.endsWith("/")) s += "/";
        return s;
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        String result = name;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String cleanPathForTitle(String path) {
        String s = path == null ? "NAS" : path;
        if (s.startsWith("smb://")) s = s.substring(6);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "NAS" : s;
    }

    @Override
    protected void onDestroy() {
        stopNsdDiscovery();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
