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
        if ((config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.OPENAI_COMPATIBLE
                || config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.OMNI_GATEWAY)
                && (config.getLlmEndpointUrl() == null || config.getLlmEndpointUrl().trim().isEmpty())) {
            throw new IllegalArgumentException("URL is required for " + displayName(config) + " LLM Provider");
        }
        if ((config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.OPENAI_COMPATIBLE
                || config.getProvider() == org.mule.extension.agent.composer.internal.enums.LlmProvider.OMNI_GATEWAY)
                && (config.getModelName() == null || config.getModelName().trim().isEmpty())) {
            throw new IllegalArgumentException("Model Name is required for " + displayName(config) + " LLM Provider");
        }
        switch (config.getProvider()) {
            case ANTHROPIC:
                return new AnthropicLlmClient(config);
            case OPENAI_COMPATIBLE:
            case OMNI_GATEWAY:
                return new OpenAiLlmClient(config);
            case OPENAI:
            default:
                return new OpenAiLlmClient(config);
        }
    }

    private static String displayName(AgentComposerConfiguration config) {
        switch (config.getProvider()) {
            case OPENAI_COMPATIBLE: return "OpenAI Compatible Endpoint";
            case OMNI_GATEWAY: return "Omni Gateway";
            case ANTHROPIC: return "Anthropic";
            case OPENAI:
            default: return "OpenAI";
        }
    }
}
