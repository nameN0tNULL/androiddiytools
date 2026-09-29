package com.sakuralite.mate8;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class ApiClient {
    static final class TranslationResult {
        final boolean changed;
        final String ocrText;
        final String translation;
        final int retryCount;
        final boolean validationOk;

        TranslationResult(boolean changed, String ocrText, String translation,
                          int retryCount, boolean validationOk) {
            this.changed = changed;
            this.ocrText = ocrText == null ? "" : ocrText;
            this.translation = translation == null ? "" : translation;
            this.retryCount = retryCount;
            this.validationOk = validationOk;
        }
    }

    private ApiClient() {}

    static TranslationResult translateImage(
            String endpoint,
            String secret,
            byte[] jpeg,
            String gameId,
            String sessionId,
            String sceneId,
            boolean contextEnabled,
            boolean autoMode,
            boolean forceRetranslate) throws Exception {

        String url = normalize(endpoint);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "image/jpeg");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SakuraMate8Lite/0.3.7");
        applyCommonHeaders(conn, secret, gameId, sessionId, sceneId, contextEnabled);
        conn.setRequestProperty("X-Auto-Mode", String.valueOf(autoMode));
        conn.setRequestProperty("X-Force-Retranslate", String.valueOf(forceRetranslate));
        conn.setFixedLengthStreamingMode(jpeg.length);

        OutputStream out = conn.getOutputStream();
        out.write(jpeg);
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readUtf8(in);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + body);
        }

        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("ok", true)) {
            throw new Exception(json.optString("error", "Bridge 返回失败"));
        }

        boolean changed = json.optBoolean("changed", true);
        String ocrText = json.optString("ocr_text", "").trim();
        String translation = json.optString("translation", json.optString("text", "")).trim();

        JSONObject meta = json.optJSONObject("meta");
        int retryCount = meta == null ? 0 : meta.optInt("retry_count", 0);
        boolean validationOk = meta == null || meta.optBoolean("validation_ok", true);

        if (changed && translation.isEmpty()) {
            throw new Exception("没有识别到可翻译文本");
        }

        return new TranslationResult(changed, ocrText, translation, retryCount, validationOk);
    }

    static String testConnection(String endpoint, String secret) throws Exception {
        String url = siblingUrl(endpoint, "/health");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SakuraMate8Lite/0.3.7");
        if (secret != null && !secret.isEmpty()) {
            conn.setRequestProperty("X-Bridge-Key", secret);
        }

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readUtf8(in);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + body);
        }

        String summary = body == null ? "" : body.trim();
        if (summary.length() > 220) summary = summary.substring(0, 220) + "…";
        if (summary.isEmpty()) summary = "HTTP " + code;
        return url + "\n" + summary;
    }

    static void resetContext(
            String endpoint,
            String secret,
            String gameId,
            String sessionId,
            String sceneId) throws Exception {
        String url = siblingUrl(endpoint, "/reset-context");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        applyCommonHeaders(conn, secret, gameId, sessionId, sceneId, true);
        conn.setFixedLengthStreamingMode(0);
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readUtf8(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + body);
        }
        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("ok", true)) {
            throw new Exception(json.optString("error", "清空上下文失败"));
        }
    }

    private static void applyCommonHeaders(
            HttpURLConnection conn,
            String secret,
            String gameId,
            String sessionId,
            String sceneId,
            boolean contextEnabled) {
        if (secret != null && !secret.isEmpty()) {
            conn.setRequestProperty("X-Bridge-Key", secret);
        }
        conn.setRequestProperty("X-Game-ID", safeId(gameId, "default"));
        conn.setRequestProperty("X-Session-ID", safeId(sessionId, "default"));
        conn.setRequestProperty("X-Scene-ID", safeId(sceneId, "default"));
        conn.setRequestProperty("X-Context-Enabled", String.valueOf(contextEnabled));
    }

    private static String safeId(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? fallback : v;
    }

    private static String siblingUrl(String endpoint, String path) throws Exception {
        String value = normalize(endpoint);
        URL u = new URL(value);
        int port = u.getPort();
        String authority = u.getHost() + (port >= 0 ? ":" + port : "");
        return u.getProtocol() + "://" + authority + path;
    }

    private static String normalize(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) value = "http://192.168.1.50:8090/translate-image";
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        try {
            URL u = new URL(value);
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                value += value.endsWith("/") ? "translate-image" : "/translate-image";
            }
        } catch (Exception ignored) {}
        return value;
    }

    private static String readUtf8(InputStream input) throws Exception {
        if (input == null) return "";
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = input.read(chunk)) >= 0) buffer.write(chunk, 0, n);
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }
}
