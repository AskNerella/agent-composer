package org.mule.extension.agent.composer.internal.configs;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.Alias;
import org.mule.sdk.api.annotation.param.Parameter;

import java.util.Collections;
import java.util.List;

/** OpenAI Responses-compatible custom endpoint settings. */
@Alias("custom")
@DisplayName("Custom")
public class CustomLlmProviderConfig implements LlmProviderConfig {

    @Parameter
    @DisplayName("Custom LLM Endpoint URL")
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("Full OpenAI Responses-compatible POST endpoint URL.")
    @Placement(order = 1)
    private String endpointUrl;

    @Parameter
    @DisplayName("LLM Headers")
    @Summary("Additional HTTP headers sent to the custom LLM endpoint.")
    @Optional
    @Placement(order = 2)
    private List<CustomHttpHeader> headers;

    @Override public LlmProvider getProvider() { return LlmProvider.CUSTOM; }
    @Override public String getModelName() { return "custom"; }
    @Override public String getCustomLlmEndpointUrl() { return endpointUrl; }
    @Override public List<CustomHttpHeader> getCustomLlmHeaders() {
        return headers == null ? Collections.emptyList() : headers;
    }
}
