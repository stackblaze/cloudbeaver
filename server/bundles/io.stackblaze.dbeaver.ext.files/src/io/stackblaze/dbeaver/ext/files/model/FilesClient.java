package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Tiny HTTP client for the leftover-volume listing sidecar. */
public class FilesClient {

    public record Entry(String name, boolean directory, long size, long mtime) {
    }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final String base;
    private final String token;

    public FilesClient(@NotNull String host, int port, @Nullable String token) {
        this.base = "http://" + host + ":" + port;
        this.token = token == null ? "" : token;
    }

    public void ping() throws DBException {
        request("/health", true);
    }

    @NotNull
    public List<Entry> list(@NotNull String path) throws DBException {
        String body = request("/cgi-bin/api?op=ls&path=" + enc(path) + tokenParam(), false);
        List<Entry> out = new ArrayList<>();
        int idx = 0;
        while (true) {
            int nameAt = body.indexOf("\"name\"", idx);
            if (nameAt < 0) {
                break;
            }
            String name = jsonString(body, nameAt);
            int typeAt = body.indexOf("\"type\"", nameAt);
            String type = typeAt >= 0 ? jsonString(body, typeAt) : "file";
            long size = jsonLong(body, body.indexOf("\"size\"", nameAt));
            long mtime = jsonLong(body, body.indexOf("\"mtime\"", nameAt));
            if (name != null && !name.isEmpty()) {
                out.add(new Entry(name, "dir".equalsIgnoreCase(type), size, mtime));
            }
            idx = nameAt + 6;
        }
        return out;
    }

    /** Small text preview for the grid cell. Server-side cap is chars*4 (UTF-8 worst case); truncated again here. */
    @NotNull
    public String cat(@NotNull String path, int maxChars) throws DBException {
        long byteCap = (long) maxChars * 4;
        String body = request(
            "/cgi-bin/api?op=cat&path=" + enc(path) + "&max=" + byteCap + tokenParam(), false);
        if (body.length() > maxChars) {
            return body.substring(0, maxChars);
        }
        return body;
    }

    /** Full, byte-exact content for view/download — used only once a cell's LOB panel is opened. */
    @NotNull
    public byte[] catBytes(@NotNull String path, long maxBytes) throws DBException {
        return requestBytes("/cgi-bin/api?op=cat&path=" + enc(path) + "&max=" + maxBytes + tokenParam());
    }

    @NotNull
    private String request(@NotNull String pathAndQuery, boolean allowEmpty) throws DBException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + pathAndQuery))
                .timeout(Duration.ofSeconds(20))
                .GET();
            if (!token.isEmpty()) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new DBException("Volume files HTTP " + res.statusCode() + " for " + pathAndQuery);
            }
            String body = res.body() == null ? "" : res.body();
            if (!allowEmpty && body.isEmpty()) {
                throw new DBException("Empty response from volume files " + pathAndQuery);
            }
            return body;
        } catch (DBException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Volume files request interrupted", e);
        } catch (Exception e) {
            throw new DBException("Failed to reach volume files at " + base + ": " + e.getMessage(), e);
        }
    }

    @NotNull
    private byte[] requestBytes(@NotNull String pathAndQuery) throws DBException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .GET();
            if (!token.isEmpty()) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 400) {
                throw new DBException("Volume files HTTP " + res.statusCode() + " for " + pathAndQuery);
            }
            return res.body() == null ? new byte[0] : res.body();
        } catch (DBException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Volume files request interrupted", e);
        } catch (Exception e) {
            throw new DBException("Failed to reach volume files at " + base + ": " + e.getMessage(), e);
        }
    }

    /**
     * Token as a query parameter. busybox httpd reliably passes QUERY_STRING to
     * CGI but consumes the Authorization header for its own Basic auth, so the
     * sidecar reads the token from ?t=. The Bearer header is still sent (below)
     * as a harmless fallback.
     */
    @NotNull
    private String tokenParam() {
        if (token.isEmpty()) {
            return "";
        }
        return "&t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    @NotNull
    private static String enc(@Nullable String path) {
        return URLEncoder.encode(path == null || path.isEmpty() ? "/" : path, StandardCharsets.UTF_8);
    }

    @NotNull
    private static String jsonString(@NotNull String json, int keyAt) {
        if (keyAt < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyAt);
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return "";
        }
        return json.substring(q1 + 1, q2);
    }

    private static long jsonLong(@NotNull String json, int keyAt) {
        if (keyAt < 0) {
            return 0;
        }
        int colon = json.indexOf(':', keyAt);
        if (colon < 0) {
            return 0;
        }
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '\n')) {
            i++;
        }
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) {
            j++;
        }
        if (j == i) {
            return 0;
        }
        try {
            return Long.parseLong(json.substring(i, j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
