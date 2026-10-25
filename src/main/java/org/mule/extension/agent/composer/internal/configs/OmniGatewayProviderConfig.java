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

/** MuleSoft Omni Gateway OpenAI-format Model Proxy settings. */
@Alias("omni-gateway")
@DisplayName("Omni Gateway")
public class OmniGatewayProviderConfig implements LlmProviderConfig {

    @Parameter
    @DisplayName("URL")
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("Public Omni Gateway endpoint plus the Model Proxy base path. The connector appends /responses.")
    @Placement(order = 1)
    private String endpointUrl;

    @Parameter
    @DisplayName("Headers")
    @Summary("Model Proxy headers, typically client_id and client_secret.")
    @Optional
    @Placement(order = 2)
    private List<LlmHttpHeader> headers;

    @Parameter
    @DisplayName("Model Name")
    @Expression(ExpressionSupport.SUPPORTED)
    @Summary("Required provider/model route, for example openai/gpt-5.2.")
    @Placement(order = 3)
    private String modelName;

    @Override public LlmProvider getProvider() { return LlmProvider.OMNI_GATEWAY; }
    @Override public String getModelName() {
        return modelName;
    }
    @Override public String getEndpointUrl() { return endpointUrl; }
    @Override public List<LlmHttpHeader> getHeaders() {
        return headers == null ? Collections.emptyList() : headers;
    }
}
