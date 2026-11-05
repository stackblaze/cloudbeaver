/*
 * Stackblaze: wiring between CloudBeaver's AI chat and Kubero's model proxy.
 */
package io.cloudbeaver.service.ai.stackblaze;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.cloudbeaver.DBWebException;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.service.ai.WebAIFeatureProvider;
import io.cloudbeaver.utils.ServletAppUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.AISettings;
import org.jkiss.dbeaver.model.ai.registry.AIEngineDescriptor;
import org.jkiss.dbeaver.model.ai.registry.AIEngineRegistry;
import org.jkiss.dbeaver.model.ai.registry.AISettingsManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Stackblaze mode: on when {@code STACKBLAZE_LLM_URL} and {@code STACKBLAZE_LLM_SECRET}
 * are set, otherwise every method here is a no-op and CloudBeaver behaves as stock.
 * <ul>
 *     <li>{@code STACKBLAZE_LLM_URL}: Kubero's OpenAI-compatible proxy, e.g.
 *     {@code https://api-dev.stackblaze.cloud/api/llm/v1}</li>
 *     <li>{@code STACKBLAZE_LLM_SECRET}: this CloudBeaver's edge secret; signs the per-turn token</li>
 *     <li>{@code STACKBLAZE_LLM_MODEL} (optional): model id; default is the first one the proxy lists</li>
 *     <li>{@code STACKBLAZE_LLM_CONTEXT_WINDOW} (optional): context window in tokens; default
 *     from the proxy's model list, else 128000</li>
 * </ul>
 */
public final class StackblazeAI {

    private static final Log log = Log.getLog(StackblazeAI.class);

    public static final String ENGINE_ID = "stackblaze";
    public static final String PROFILE_ID = "stackblaze";
    public static final String PROFILE_NAME = "Stackblaze";

    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final long CATALOG_RETRY_MS = 60_000L;

    private static final Object CATALOG_LOCK = new Object();
    private static volatile String catalogModel;
    private static volatile Integer catalogContextWindow;
    private static volatile long catalogRetryAt;

    private StackblazeAI() {
    }

    public static boolean isEnabled() {
        return baseUrl() != null && secret() != null;
    }

    @Nullable
    public static String baseUrl() {
        String url = env("STACKBLAZE_LLM_URL");
        return url == null ? null : url.replaceAll("/+$", "");
    }

    @Nullable
    private static String secret() {
        return env("STACKBLAZE_LLM_SECRET");
    }

    /**
     * Make the Stackblaze profile the default AI configuration (in memory only;
     * nothing is written to ai-configuration.json). Idempotent and cheap, so it
     * is called before every AI use: the settings holder can be reloaded at any time.
     */
    public static void ensureDefaultProfile() {
        if (!isEnabled()) {
            return;
        }
        try {
            AIEngineDescriptor engine = AIEngineRegistry.getInstance().getEngineDescriptor(ENGINE_ID);
            if (engine == null) {
                log.error("Stackblaze AI engine is not registered");
                return;
            }
            AISettings settings = AISettingsManager.getInstance().getSettings();
            synchronized (settings) {
                AIConfigurationProfile profile = settings.getConfigurationOrNull(PROFILE_ID);
                if (profile == null) {
                    profile = settings.createConfiguration(PROFILE_ID, engine);
                    profile.setProfileName(PROFILE_NAME);
                }
                AIConfigurationProfile current = settings.getDefaultConfigurationOrNull();
                if (current == null || !PROFILE_ID.equals(current.getProfileId())) {
                    settings.setDefaultConfiguration(profile);
                }
                // AISettings starts disabled on a server with no ai-configuration.json.
                // The admin switch is the "ai" server feature; follow it.
                if (settings.isAiDisabled() && isAiFeatureEnabled()) {
                    settings.setAiDisabled(false);
                }
            }
        } catch (Exception e) {
            log.error("Error configuring the Stackblaze AI profile", e);
        }
    }

    /**
     * A profile used for one AI turn: the Stackblaze engine with a fresh token
     * naming the user and the connection, so Kubero bills the tenant that owns it.
     */
    @NotNull
    public static AIConfigurationProfile turnProfile(
        @NotNull WebSession webSession,
        @Nullable DBPDataSourceContainer dataSource,
        @NotNull String sessionId
    ) throws DBWebException {
        if (dataSource == null) {
            throw new DBWebException("Open the AI chat from a database connection.");
        }
        String secret = secret();
        if (secret == null) {
            throw new DBWebException("AI is not configured on this server.");
        }
        StackblazeAIProperties properties = new StackblazeAIProperties();
        properties.setToken(StackblazeLlmToken.sign(
            secret,
            StackblazeLlmToken.payload(
                webSession.getUserId(),
                dataSource.getProject() == null ? null : dataSource.getProject().getId(),
                dataSource.getId(),
                dataSource.getName(),
                sessionId,
                System.currentTimeMillis() + StackblazeLlmToken.DEFAULT_TTL_MS
            )
        ));
        AIConfigurationProfile profile = new AIConfigurationProfile();
        profile.setProfileId(PROFILE_ID);
        profile.setProfileName(PROFILE_NAME);
        profile.setEngineId(ENGINE_ID);
        profile.setConfiguration(properties);
        return profile;
    }

    @Nullable
    static String model(@Nullable String token) {
        String fromEnv = env("STACKBLAZE_LLM_MODEL");
        if (fromEnv != null) {
            return fromEnv;
        }
        loadCatalog(token);
        return catalogModel;
    }

    @NotNull
    static Integer contextWindowSize(@Nullable String token) {
        String fromEnv = env("STACKBLAZE_LLM_CONTEXT_WINDOW");
        if (fromEnv != null) {
            try {
                int value = Integer.parseInt(fromEnv);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException e) {
                log.warn("Ignoring STACKBLAZE_LLM_CONTEXT_WINDOW=" + fromEnv);
            }
        }
        if (env("STACKBLAZE_LLM_MODEL") == null) {
            loadCatalog(token);
            Integer fromCatalog = catalogContextWindow;
            if (fromCatalog != null && fromCatalog > 0) {
                return fromCatalog;
            }
        }
        return DEFAULT_CONTEXT_WINDOW;
    }

    /** First model the proxy offers (GET {base}/models), cached for the process. */
    private static void loadCatalog(@Nullable String token) {
        if (catalogModel != null || token == null || baseUrl() == null) {
            return;
        }
        synchronized (CATALOG_LOCK) {
            if (catalogModel != null || System.currentTimeMillis() < catalogRetryAt) {
                return;
            }
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray data = root.getAsJsonArray("data");
                if (data == null || data.isEmpty()) {
                    throw new IllegalStateException("the proxy lists no models");
                }
                JsonObject first = data.get(0).getAsJsonObject();
                JsonElement ctx = first.get("context_length");
                catalogContextWindow = ctx != null && ctx.isJsonPrimitive() ? ctx.getAsInt() : null;
                catalogModel = first.get("id").getAsString();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                catalogRetryAt = System.currentTimeMillis() + CATALOG_RETRY_MS;
                log.warn("Stackblaze AI: cannot read the model list from the proxy: " + e.getMessage());
            }
        }
    }

    private static boolean isAiFeatureEnabled() {
        try {
            return ServletAppUtils.getServletApplication().getAppConfiguration()
                .isFeatureEnabled(WebAIFeatureProvider.AI_FEATURE_ID);
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static String env(@NotNull String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
