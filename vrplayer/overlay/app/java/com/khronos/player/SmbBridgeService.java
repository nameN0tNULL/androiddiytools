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
import java.util.concurrent.atomic.AtomicInteger;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

public class SmbBridgeService extends Service {
    public static final int PORT = 8877;

    private static volatile boolean ready = false;
    private static volatile String lastError = null;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();

    private volatile ServerSocket server;
    private volatile SmbFile smbTarget;
    private volatile long targetLength = -1;

    public static void start(Context context, String smbUrl) {
        ready = false;
        lastError = null;

        Intent i = new Intent(context, SmbBridgeService.class);
        i.putExtra("url", smbUrl);
        context.startService(i);
    }

    public static boolean isReady() { return ready; }
    public static String getLastError() { return lastError; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final int myGeneration = generation.incrementAndGet();
        final String url = intent.getStringExtra("url");

        workers.execute(() -> {
            try {
                closeServer();
                smbTarget = openGuestFile(url);
                targetLength = smbTarget.length();
                if (targetLength <= 0) {
                    throw new IllegalArgumentException("无法获取视频大小");
                }

                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                server = ss;

                if (generation.get() != myGeneration) {
                    ss.close();
                    return;
                }

                ready = true;
                lastError = null;

                while (!ss.isClosed() && generation.get() == myGeneration) {
                    final Socket socket = ss.accept();
                    workers.execute(() -> handle(socket));
                }
            } catch (Exception e) {
                if (generation.get() == myGeneration) {
                    ready = false;
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
        });

        return START_NOT_STICKY;
    }

    private SmbFile openGuestFile(String url) throws Exception {
        Exception guestError = null;

        try {
            SmbFile f = new SmbFile(url, guestContext());
            if (f.exists() && f.isFile()) return f;
        } catch (Exception e) {
            guestError = e;
        }

        try {
            SmbFile f = new SmbFile(url, anonymousContext());
            if (f.exists() && f.isFile()) return f;
        } catch (Exception e) {
            if (guestError == null) guestError = e;
        }

        if (guestError != null) throw guestError;
        throw new IllegalArgumentException("NAS 文件不可读");
    }

    private CIFSContext baseContext() throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.enableSMB2", "true");
        props.setProperty("jcifs.smb.client.disableSMB1", "false");
        props.setProperty("jcifs.resolveOrder", "DNS,RESOLVER,BCAST");
        return new BaseContext(new PropertyConfiguration(props));
    }

    private CIFSContext guestContext() throws Exception {
        return baseContext().withGuestCrendentials();
    }

    private CIFSContext anonymousContext() throws Exception {
        return baseContext().withAnonymousCredentials();
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

            long length = targetLength;
            SmbFile target = smbTarget;
            if (length <= 0 || target == null) {
                sendText(s.getOutputStream(), 503, "bridge not ready");
                return;
            }

            long start = 0;
            long end = length - 1;
            boolean partial = false;

            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');

                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();

                    if (a.isEmpty() && !b.isEmpty()) {
                        long suffix = Long.parseLong(b);
                        start = Math.max(0, length - suffix);
                        end = length - 1;
                    } else {
                        if (!a.isEmpty()) start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(end, Long.parseLong(b));
                    }

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

            writeAscii(out, partial
                    ? "HTTP/1.1 206 Partial Content\r\n"
                    : "HTTP/1.1 200 OK\r\n");
            writeAscii(out, "Content-Type: application/octet-stream\r\n");
            writeAscii(out, "Accept-Ranges: bytes\r\n");
            writeAscii(out, "Content-Length: " + count + "\r\n");

            if (partial) {
                writeAscii(out,
                        "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n");
            }

            writeAscii(out, "Connection: close\r\n\r\n");

            if (!"HEAD".equals(method)) {
                try (SmbRandomAccessFile raf = target.openRandomAccess("r")) {
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
        targetLength = -1;
        smbTarget = null;

        ServerSocket ss = server;
        server = null;

        if (ss != null) {
            try { ss.close(); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        generation.incrementAndGet();
        closeServer();
        workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
