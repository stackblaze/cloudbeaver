/*
 * Stackblaze: engine properties for Kubero's model proxy.
 */
package io.cloudbeaver.service.ai.stackblaze;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.engine.BaseAIEngineProperties;
import org.jkiss.dbeaver.model.ai.engine.openai.OpenAIBaseProperties;

/**
 * Everything comes from the environment (see {@link StackblazeAI}) except the
 * token, which is minted per turn and never saved: it is {@code transient}, so
 * it stays out of ai-configuration.json, and the secret hooks are no-ops.
 */
public class StackblazeAIProperties extends BaseAIEngineProperties implements OpenAIBaseProperties {

    @Nullable
    private transient String token;

    public StackblazeAIProperties() {
    }

    @Nullable
    @Override
    public String getBaseUrl() {
        return StackblazeAI.baseUrl();
    }

    @Nullable
    @Override
    public String getToken() {
        return token;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    @Nullable
    @Override
    public String getModel() {
        return StackblazeAI.model(token);
    }

    @Nullable
    @Override
    public Integer getContextWindowSize() {
        return StackblazeAI.contextWindowSize(token);
    }

    @Override
    public double getTemperature() {
        return temperature;
    }

    /**
     * Valid when the proxy is configured. The token is added per turn; a profile
     * without one fails in OpenAIEngine.createClient ("token is not set").
     */
    @Override
    public boolean isValidConfiguration() {
        return StackblazeAI.isEnabled();
    }

    @Override
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) {
        // Nothing stored.
    }

    @Override
    public void saveSecrets(@NotNull AIConfigurationProfile profile) {
        // Never persist the per-turn token.
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) {
        // Nothing stored.
    }
}
