/*
 * Stackblaze: AI engine that talks to Kubero's model proxy.
 */
package io.cloudbeaver.service.ai.stackblaze;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.ai.engine.openai.OpenAIEngine;

/**
 * Upstream OpenAI engine (Responses API, streaming, function calls) pointed at
 * Kubero's OpenAI-compatible proxy. Kubero holds the provider key, applies the
 * tenant's plan and billing gates and bills the tokens; CloudBeaver only holds a
 * per-turn token ({@link StackblazeLlmToken}).
 */
public class StackblazeAIEngine extends OpenAIEngine<StackblazeAIProperties> {

    public StackblazeAIEngine(@NotNull StackblazeAIProperties properties) {
        super(properties);
    }
}
