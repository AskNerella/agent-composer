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
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.extension.agent.composer.internal.values.ModelNameValueProvider;

/** OpenAI provider settings. */
@Alias("open-ai")
@DisplayName("OpenAI")
public class OpenAiProviderConfig implements LlmProviderConfig {

    @Parameter
    @DisplayName("Model Name")
    @Expression(ExpressionSupport.SUPPORTED)
    @OfValues(value = ModelNameValueProvider.class, open = true)
    @Summary("OpenAI model identifier, for example gpt-4o.")
    @Optional(defaultValue = "gpt-4o")
    @Placement(order = 1)
    private String modelName;

    @Parameter
    @DisplayName("API Key")
    @Password
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("OpenAI API key.")
    @Placement(order = 2)
    private String apiKey;

    @Override public LlmProvider getProvider() { return LlmProvider.OPENAI; }
    @Override public String getModelName() { return modelName == null || modelName.isBlank() ? "gpt-4o" : modelName; }
    @Override public String getApiKey() { return apiKey; }
}
