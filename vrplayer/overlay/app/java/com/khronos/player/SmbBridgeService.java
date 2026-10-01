package com.khronos.player;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

public class SmbBridgeService extends Service {
    public static final int PORT = 8877;
    private static volatile boolean ready = false;
    private static volatile String lastError = null;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile SmbFile target;

    public static void start(Context context, String smbUrl, String domain, String user, String password) {
        ready = false;
        lastError = null;
        Intent i = new Intent(context, SmbBridgeService.class);
        i.putExtra("url", smbUrl);
        i.putExtra("domain", domain == null ? "" : domain);
        i.putExtra("user", user == null ? "" : user);
        i.putExtra("password", password == null ? "" : password);
        context.startService(i);
    }

    public static boolean isReady() { return ready; }
    public static String getLastError() { return lastError; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        final String url = intent.getStringExtra("url");
        final String domain = intent.getStringExtra("domain");
        final String user = intent.getStringExtra("user");
        final String password = intent.getStringExtra("password");

        workers.execute(() -> {
            try {
                closeServer();
                Properties props = new Properties();
                props.setProperty("jcifs.smb.client.enableSMB2", "true");
                props.setProperty("jcifs.smb.client.disableSMB1", "false");
                CIFSContext base = new BaseContext(new PropertyConfiguration(props));
                CIFSContext auth = base.withCredentials(
                        new NtlmPasswordAuthenticator(domain == null ? "" : domain,
                                user == null ? "" : user,
                                password == null ? "" : password));
                SmbFile f = new SmbFile(url, auth);
                if (!f.exists() || !f.isFile()) {
                    throw new IllegalArgumentException("SMB path is not a file: " + url);
                }
                target = f;
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                server = ss;
                ready = true;
                lastError = null;

                while (!ss.isClosed()) {
                    final Socket socket = ss.accept();
                    workers.execute(() -> handle(socket));
                }
            } catch (Exception e) {
                ready = false;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        });
        return START_NOT_STICKY;
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.ISO_8859_1));
            String request = reader.readLine();
            if (request == null) return;
            String[] first = request.split(" ");
            if (first.length < 2) return;
            String method = first[0].toUpperCase(Locale.US);

            String range = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Range")) {
                    range = line.substring(colon + 1).trim();
                }
            }

            SmbFile f = target;
            if (f == null) {
                sendText(s.getOutputStream(), 503, "bridge not ready");
                return;
            }

            long length = f.length();
            long start = 0;
            long end = length - 1;
            boolean partial = false;

            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(end, Long.parseLong(b));
                    partial = true;
                }
            }

            if (start < 0 || start >= length || end < start) {
                OutputStream out = s.getOutputStream();
                writeAscii(out, "HTTP/1.1 416 Range Not Satisfiable\r\n");
                writeAscii(out, "Content-Range: bytes */" + length + "\r\n");
                writeAscii(out, "Connection: close\r\n\r\n");
                out.flush();
                return;
            }

            long count = end - start + 1;
            OutputStream out = s.getOutputStream();
            writeAscii(out, partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            writeAscii(out, "Content-Type: application/octet-stream\r\n");
            writeAscii(out, "Accept-Ranges: bytes\r\n");
            writeAscii(out, "Content-Length: " + count + "\r\n");
            if (partial) writeAscii(out, "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n");
            writeAscii(out, "Connection: close\r\n\r\n");

            if (!"HEAD".equals(method)) {
                try (SmbRandomAccessFile raf = f.openRandomAccess("r")) {
                    raf.seek(start);
                    byte[] buffer = new byte[256 * 1024];
                    long remaining = count;
                    while (remaining > 0) {
                        int want = (int)Math.min(buffer.length, remaining);
                        int n = raf.read(buffer, 0, want);
                        if (n <= 0) break;
                        out.write(buffer, 0, n);
                        remaining -= n;
                    }
                }
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeAscii(out, "HTTP/1.1 " + code + " Error\r\n");
        writeAscii(out, "Content-Type: text/plain; charset=utf-8\r\n");
        writeAscii(out, "Content-Length: " + body.length + "\r\n");
        writeAscii(out, "Connection: close\r\n\r\n");
        out.write(body);
        out.flush();
    }

    private static void writeAscii(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void closeServer() {
        ready = false;
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        closeServer();
        workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
