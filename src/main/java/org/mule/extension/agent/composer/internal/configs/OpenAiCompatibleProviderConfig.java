package org.mule.extension.agent.composer.internal.configs;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.Alias;
import org.mule.sdk.api.annotation.param.Parameter;

import java.util.Collections;
import java.util.List;

/** Settings for an OpenAI Responses-compatible endpoint. */
@Alias("open-ai-compatible")
@DisplayName("OpenAI Compatible Endpoint")
public class OpenAiCompatibleProviderConfig implements LlmProviderConfig {

    @Parameter
    @DisplayName("URL")
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("OpenAI-compatible base URL ending in /, or the full Responses POST endpoint URL.")
    @Placement(order = 1)
    private String endpointUrl;

    @Parameter
    @DisplayName("Model Name")
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("Model identifier sent in the OpenAI Responses request.")
    @Placement(order = 2)
    private String modelName;

    @Parameter
    @DisplayName("Headers")
    @Summary("Additional HTTP headers sent to the OpenAI-compatible endpoint.")
    @org.mule.runtime.extension.api.annotation.param.Optional
    @Placement(order = 3)
    private List<LlmHttpHeader> headers;

    @Override public LlmProvider getProvider() { return LlmProvider.OPENAI_COMPATIBLE; }
    @Override public String getModelName() { return modelName; }
    @Override public String getEndpointUrl() { return endpointUrl; }
    @Override public List<LlmHttpHeader> getHeaders() {
        return headers == null ? Collections.emptyList() : headers;
    }
}
