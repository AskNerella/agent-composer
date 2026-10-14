package org.mule.extension.agent.composer.internal.configs;

import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.param.Parameter;

/** Header sent only to a configured custom LLM endpoint. */
public class CustomHttpHeader {

    @Parameter
    @DisplayName("Header Name")
    @Summary("HTTP header name, for example x-client-id or Authorization.")
    private String name;

    @Parameter
    @Password
    @Expression(ExpressionSupport.SUPPORTED)
    @DisplayName("Header Value")
    @Summary("Header value. Secure property placeholders are recommended for credentials.")
    private String value;

    public String getName() { return name; }
    public String getValue() { return value; }
}
