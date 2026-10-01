package com.khronos.player;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
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
import jcifs.smb.NtlmPasswordAuthenticator;
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
    private volatile Uri contentTarget;
    private volatile String fileTarget;
    private volatile long targetLength = -1;

    public static void start(Context context, String smbUrl, String domain, String user, String password) {
        ready = false;
        lastError = null;
        Intent i = new Intent(context, SmbBridgeService.class);
        i.putExtra("kind", "smb");
        i.putExtra("url", smbUrl);
        i.putExtra("domain", domain == null ? "" : domain);
        i.putExtra("user", user == null ? "" : user);
        i.putExtra("password", password == null ? "" : password);
        context.startService(i);
    }

    public static void startUri(Context context, Uri uri) {
        ready = false;
        lastError = null;
        Intent i = new Intent(context, SmbBridgeService.class);
        i.putExtra("kind", "uri");
        i.putExtra("uri", uri.toString());
        context.startService(i);
    }

    public static boolean isReady() { return ready; }
    public static String getLastError() { return lastError; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final int myGeneration = generation.incrementAndGet();
        final String kind = intent.getStringExtra("kind");
        final String url = intent.getStringExtra("url");
        final String domain = intent.getStringExtra("domain");
        final String user = intent.getStringExtra("user");
        final String password = intent.getStringExtra("password");
        final String uriString = intent.getStringExtra("uri");

        workers.execute(() -> {
            try {
                closeServer();
                clearTargets();

                if ("uri".equals(kind)) {
                    prepareUriTarget(uriString);
                } else {
                    prepareSmbTarget(url, domain, user, password);
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

    private void prepareSmbTarget(String url, String domain, String user, String password) throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.enableSMB2", "true");
        props.setProperty("jcifs.smb.client.disableSMB1", "false");
        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        CIFSContext auth = base.withCredentials(
                new NtlmPasswordAuthenticator(
                        domain == null ? "" : domain,
                        user == null ? "" : user,
                        password == null ? "" : password));

        SmbFile f = new SmbFile(url, auth);
        if (!f.exists() || !f.isFile()) {
            throw new IllegalArgumentException("SMB path is not a file: " + url);
        }

        smbTarget = f;
        targetLength = f.length();
        if (targetLength <= 0) {
            throw new IllegalArgumentException("SMB file size is unavailable");
        }
    }

    private void prepareUriTarget(String uriString) throws Exception {
        if (uriString == null || uriString.isEmpty()) {
            throw new IllegalArgumentException("Missing media URI");
        }

        Uri uri = Uri.parse(uriString);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            contentTarget = uri;
            targetLength = resolveContentLength(uri);
        } else if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Invalid file URI");
            }
            File file = new File(path);
            if (!file.isFile()) {
                throw new IllegalArgumentException("File does not exist: " + path);
            }
            fileTarget = file.getAbsolutePath();
            targetLength = file.length();
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + scheme);
        }

        if (targetLength <= 0) {
            throw new IllegalArgumentException("Media size is unavailable; a seekable file is required");
        }
    }

    private long resolveContentLength(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();

        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long stat = pfd.getStatSize();
                if (stat > 0) return stat;
            }
        }

        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    long size = cursor.getLong(index);
                    if (size > 0) return size;
                }
            }
        }

        return -1;
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
            if (length <= 0) {
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
            writeAscii(out, partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            writeAscii(out, "Content-Type: application/octet-stream\r\n");
            writeAscii(out, "Accept-Ranges: bytes\r\n");
            writeAscii(out, "Content-Length: " + count + "\r\n");
            if (partial) {
                writeAscii(out, "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n");
            }
            writeAscii(out, "Connection: close\r\n\r\n");

            if (!"HEAD".equals(method)) {
                streamSource(out, start, count);
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void streamSource(OutputStream out, long start, long count) throws Exception {
        SmbFile smb = smbTarget;
        if (smb != null) {
            try (SmbRandomAccessFile raf = smb.openRandomAccess("r")) {
                raf.seek(start);
                copyRange(new Reader() {
                    @Override public int read(byte[] buffer, int len) throws Exception {
                        return raf.read(buffer, 0, len);
                    }
                }, out, count);
            }
            return;
        }

        String filePath = fileTarget;
        if (filePath != null) {
            try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
                raf.seek(start);
                copyRange(new Reader() {
                    @Override public int read(byte[] buffer, int len) throws Exception {
                        return raf.read(buffer, 0, len);
                    }
                }, out, count);
            }
            return;
        }

        Uri uri = contentTarget;
        if (uri != null) {
            ContentResolver resolver = getContentResolver();
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
                if (pfd == null) throw new IllegalStateException("Could not open content URI");
                try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
                    try {
                        input.getChannel().position(start);
                    } catch (Exception seekError) {
                        skipFully(input, start);
                    }
                    copyRange(new Reader() {
                        @Override public int read(byte[] buffer, int len) throws Exception {
                            return input.read(buffer, 0, len);
                        }
                    }, out, count);
                }
            }
            return;
        }

        throw new IllegalStateException("No media source");
    }

    private interface Reader {
        int read(byte[] buffer, int len) throws Exception;
    }

    private static void copyRange(Reader reader, OutputStream out, long count) throws Exception {
        byte[] buffer = new byte[256 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int want = (int)Math.min(buffer.length, remaining);
            int n = reader.read(buffer, want);
            if (n <= 0) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static void skipFully(InputStream input, long bytes) throws Exception {
        long remaining = bytes;
        byte[] scratch = new byte[64 * 1024];
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int n = input.read(scratch, 0, (int)Math.min(scratch.length, remaining));
            if (n < 0) throw new IllegalStateException("Could not seek content stream");
            remaining -= n;
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

    private void clearTargets() {
        smbTarget = null;
        contentTarget = null;
        fileTarget = null;
        targetLength = -1;
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
        generation.incrementAndGet();
        closeServer();
        clearTargets();
        workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
