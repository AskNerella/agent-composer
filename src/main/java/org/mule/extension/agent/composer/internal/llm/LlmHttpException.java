package org.mule.extension.agent.composer.internal.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP failure that retains the upstream response for redacted execution tracing. */
public final class LlmHttpException extends RuntimeException {
    private final int statusCode;
    private final String method;
    private final String url;
    private final String responseBody;

    public LlmHttpException(String provider, int statusCode, String method, String url, String responseBody) {
        super(provider + " API error " + statusCode + " for " + method + " " + url + ": " + responseBody);
        this.statusCode = statusCode;
        this.method = method;
        this.url = url;
        this.responseBody = responseBody;
    }

    public Map<String, Object> toTraceOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("statusCode", statusCode);
        output.put("method", method);
        output.put("url", url);
        output.put("body", responseBody);
        return output;
    }
}
