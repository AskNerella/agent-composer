package org.mule.extension.agent.composer.internal.llm;

import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;

/**
 * Creates the appropriate {@link LlmClient} based on the configured
 * {@link org.mule.extension.agent.composer.internal.enums.LlmProvider}.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(AgentComposerConfiguration config) {
        if (config == null || config.getProvider() == null) {
            throw new IllegalArgumentException("LLM Provider is required");
        }
        if ((config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.OPENAI
                || config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.ANTHROPIC)
                && (config.getApiKey() == null || config.getApiKey().trim().isEmpty())) {
            throw new IllegalArgumentException("API Key is required for " + config.getProvider() + " LLM Provider");
        }
        switch (config.getProvider()) {
            case ANTHROPIC:
                return new AnthropicLlmClient(config);
            case CUSTOM:
                return new OpenAiLlmClient(config);
            case OPENAI:
            default:
                return new OpenAiLlmClient(config);
        }
    }
}
