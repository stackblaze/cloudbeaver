/*
 * Stackblaze: per-turn credential for Kubero's model proxy.
 */
package io.cloudbeaver.service.ai.stackblaze;

import com.google.gson.Gson;
import org.jkiss.code.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token CloudBeaver hands Kubero's model proxy as its "API key", one per AI turn.
 * <p>
 * Format: {@code cbllm1.<payload>.<sig>}, both parts base64url without padding.
 * {@code sig = HMAC-SHA256(secret, "cbllm1." + payload)}, where the secret is
 * this CloudBeaver's edge secret (kubero-server holds the same value per region).
 * <p>
 * Payload (compact JSON, null fields left out):
 * <ul>
 *     <li>{@code u} CloudBeaver user id (kubero-server names it {@code kb-<username>})</li>
 *     <li>{@code p} project id of the connection</li>
 *     <li>{@code c} connection id</li>
 *     <li>{@code n} connection name ({@code <pipeline>/<phase>/<instance>})</li>
 *     <li>{@code s} conversation id, or {@code sql:<connection id>} for SQL editor completion</li>
 *     <li>{@code exp} expiry, epoch millis</li>
 * </ul>
 * Kubero resolves the connection to the tenant that owns the database and bills
 * the turn's tokens to that tenant's AI credit.
 */
public final class StackblazeLlmToken {

    public static final String VERSION = "cbllm1";
    public static final long DEFAULT_TTL_MS = 30L * 60_000L;

    private static final Gson GSON = new Gson();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private StackblazeLlmToken() {
    }

    @NotNull
    public static Map<String, Object> payload(
        String userId,
        String projectId,
        String connectionId,
        String connectionName,
        String sessionId,
        long expiresAtMillis
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "u", userId);
        putIfPresent(payload, "p", projectId);
        putIfPresent(payload, "c", connectionId);
        putIfPresent(payload, "n", connectionName);
        putIfPresent(payload, "s", sessionId);
        payload.put("exp", expiresAtMillis);
        return payload;
    }

    @NotNull
    public static String sign(@NotNull String secret, @NotNull Map<String, Object> payload) {
        String body = B64.encodeToString(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + body + "." + mac(secret, VERSION + "." + body);
    }

    @NotNull
    static String mac(@NotNull String secret, @NotNull String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static void putIfPresent(@NotNull Map<String, Object> payload, @NotNull String key, String value) {
        if (value != null && !value.isEmpty()) {
            payload.put(key, value);
        }
    }
}
