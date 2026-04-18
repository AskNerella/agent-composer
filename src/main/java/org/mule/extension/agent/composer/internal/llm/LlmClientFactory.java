package org.mule.extension.agent.composer.internal.llm;

import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;

/**
 * Creates the appropriate {@link LlmClient} based on the configured
 * {@link org.mule.extension.agent.composer.internal.enums.LlmProvider}.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(AgentComposerConfiguration config) {
        switch (config.getProvider()) {
            case ANTHROPIC:
                return new AnthropicLlmClient(config);
            case OPENAI:
            default:
                return new OpenAiLlmClient(config);
        }
    }
}
