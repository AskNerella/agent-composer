package org.mule.extension.agent.composer.internal.configs;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.Alias;
import org.mule.sdk.api.annotation.param.Parameter;

/** Anthropic provider settings. Model selection is intentionally connector-managed. */
@Alias("anthropic")
@DisplayName("Anthropic")
public class AnthropicProviderConfig implements LlmProviderConfig {
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    @Parameter
    @DisplayName("API Key")
    @Password
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("Anthropic API key.")
    @Placement(order = 1)
    private String apiKey;

    @Parameter
    @DisplayName("Anthropic API Version")
    @Summary("Value sent in the Anthropic-Version request header.")
    @Optional(defaultValue = "2023-06-01")
    @Placement(order = 2)
    private String anthropicVersion;

    @Override public LlmProvider getProvider() { return LlmProvider.ANTHROPIC; }
    @Override public String getModelName() { return DEFAULT_MODEL; }
    @Override public String getApiKey() { return apiKey; }
    @Override public String getAnthropicVersion() {
        return anthropicVersion == null || anthropicVersion.isBlank() ? "2023-06-01" : anthropicVersion;
    }
}
