/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.cloudbeaver.model.fs.FsMultipartPart;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path-style S3 REST client for multipart upload. MinIO 8.5 hides the low-level
 * CreateMultipartUpload / UploadPart APIs, so we sign the four standard calls
 * ourselves. RustFS speaks this API.
 */
final class RustfsS3V4Client {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType XML = MediaType.parse("application/xml");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Pattern UPLOAD_ID = Pattern.compile("<UploadId>([^<]+)</UploadId>");
    private static final HexFormat HEX = HexFormat.of();

    @NotNull
    private final OkHttpClient http;
    @NotNull
    private final String accessKey;
    @NotNull
    private final String secretKey;
    @NotNull
    private final String region;
    @NotNull
    private final String hostHeader;
    @NotNull
    private final String baseUrl;

    private RustfsS3V4Client(
        @NotNull OkHttpClient http,
        @NotNull String accessKey,
        @NotNull String secretKey,
        @NotNull String region,
        @NotNull String hostHeader,
        @NotNull String baseUrl
    ) {
        this.http = http;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.hostHeader = hostHeader;
        this.baseUrl = baseUrl;
    }

    @NotNull
    static RustfsS3V4Client create(@NotNull WebConnectionInfo connection) {
        DBPConnectionConfiguration cfg = connection.getDataSourceContainer().getActualConnectionConfiguration();
        String host = CommonUtils.notEmpty(cfg.getHostName());
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = CommonUtils.toInt(cfg.getHostPort(), RustfsConstants.DEFAULT_PORT);
        boolean ssl = CommonUtils.toBoolean(cfg.getProviderProperty(RustfsConstants.PROP_USE_SSL), false);
        String region = CommonUtils.notEmpty(cfg.getProviderProperty(RustfsConstants.PROP_REGION));
        if (region.isEmpty()) {
            region = "us-east-1";
        }
        String scheme = ssl ? "https" : "http";
        boolean defaultPort = ssl ? port == 443 : port == 80;
        String hostHeader = defaultPort ? host : host + ":" + port;
        String baseUrl = scheme + "://" + hostHeader;
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build();
        return new RustfsS3V4Client(
            http,
            CommonUtils.notEmpty(cfg.getUserName()),
            CommonUtils.notEmpty(cfg.getUserPassword()),
            region,
            hostHeader,
            baseUrl
        );
    }

    @NotNull
    String createMultipart(@NotNull String bucket, @NotNull String key) throws IOException {
        String body = request("POST", bucket, key, Map.of("uploads", ""), RequestBody.create(new byte[0], OCTET));
        Matcher matcher = UPLOAD_ID.matcher(body);
        if (!matcher.find()) {
            throw new IOException("S3 CreateMultipartUpload returned no UploadId");
        }
        return matcher.group(1);
    }

    @NotNull
    String uploadPart(
        @NotNull String bucket,
        @NotNull String key,
        @NotNull String uploadId,
        int partNumber,
        @NotNull InputStream data,
        long size
    ) throws IOException {
        byte[] bytes = data.readNBytes(size > 0 && size <= Integer.MAX_VALUE ? (int) size : Integer.MAX_VALUE);
        RequestBody body = RequestBody.create(bytes, OCTET);
        try (Response response = execute("PUT", bucket, key, Map.of(
            "partNumber", String.valueOf(partNumber),
            "uploadId", uploadId
        ), body)) {
            String etag = response.header("ETag");
            if (CommonUtils.isEmpty(etag)) {
                throw new IOException("S3 UploadPart returned no ETag");
            }
            return etag.replace("\"", "");
        }
    }

    void complete(
        @NotNull String bucket,
        @NotNull String key,
        @NotNull String uploadId,
        @NotNull List<FsMultipartPart> parts
    ) throws IOException {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (FsMultipartPart part : parts) {
            xml.append("<Part><PartNumber>").append(part.partNumber())
                .append("</PartNumber><ETag>\"").append(part.etag()).append("\"</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        request("POST", bucket, key, Map.of("uploadId", uploadId), RequestBody.create(xml.toString(), XML));
    }

    void abort(@NotNull String bucket, @NotNull String key, @NotNull String uploadId) throws IOException {
        request("DELETE", bucket, key, Map.of("uploadId", uploadId), RequestBody.create(new byte[0], OCTET));
    }

    @NotNull
    private String request(
        @NotNull String method,
        @NotNull String bucket,
        @NotNull String key,
        @NotNull Map<String, String> query,
        @NotNull RequestBody body
    ) throws IOException {
        try (Response response = execute(method, bucket, key, query, body)) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("S3 " + method + " failed: HTTP " + response.code() + " " + text);
            }
            return text;
        }
    }

    @NotNull
    private Response execute(
        @NotNull String method,
        @NotNull String bucket,
        @NotNull String key,
        @NotNull Map<String, String> query,
        @NotNull RequestBody body
    ) throws IOException {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String canonicalUri = "/" + encodePath(bucket) + "/" + encodeKey(key);
        String canonicalQuery = canonicalQuery(query);
        String payloadHash = "UNSIGNED-PAYLOAD";
        String canonicalHeaders = "host:" + hostHeader + "\n"
            + "x-amz-content-sha256:" + payloadHash + "\n"
            + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
            + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        String signature = HEX.formatHex(hmac(signingKey(dateStamp), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        String url = baseUrl + canonicalUri + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("Host", hostHeader)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", authorization);
        if ("POST".equals(method)) {
            builder.post(body);
        } else if ("PUT".equals(method)) {
            builder.put(body);
        } else if ("DELETE".equals(method)) {
            builder.delete();
        } else {
            builder.method(method, body);
        }
        Response response = http.newCall(builder.build()).execute();
        if (!response.isSuccessful()) {
            String text = response.body() != null ? response.body().string() : "";
            response.close();
            throw new IOException("S3 " + method + " failed: HTTP " + response.code() + " " + text);
        }
        return response;
    }

    @NotNull
    private byte[] signingKey(@NotNull String dateStamp) throws IOException {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        return hmac(kService, "aws4_request");
    }

    @NotNull
    private static String canonicalQuery(@NotNull Map<String, String> query) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            sorted.put(uriEncode(entry.getKey()), uriEncode(entry.getValue()));
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    @NotNull
    private static String encodeKey(@NotNull String key) {
        String[] parts = key.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(uriEncode(parts[i]));
        }
        return out.toString();
    }

    @NotNull
    private static String encodePath(@NotNull String value) {
        return uriEncode(value);
    }

    @NotNull
    private static String uriEncode(@NotNull String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        return encoded;
    }

    @NotNull
    private static String sha256Hex(@NotNull String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @NotNull
    private static byte[] hmac(@NotNull byte[] key, @NotNull String data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @NotNull
    private static byte[] hmac(@NotNull String key, @NotNull String data) throws IOException {
        return hmac(key.getBytes(StandardCharsets.UTF_8), data);
    }
}
