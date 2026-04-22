package org.mule.extension.agent.composer.internal.source;

import java.io.Serializable;
import java.util.Map;

/**
 * Attributes delivered to the Mule flow for every incoming A2A task request.
 * Available in DataWeave as {@code attributes.<field>}.
 */
public class AgentListenerAttributes implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String method;
    private final String path;
    private final String remoteAddress;
    private final Map<String, String> headers;

    /**
     * Unique ID for this request. Pass to {@code agent-listener-respond} so the
     * connector knows which HTTP connection to reply on.
     */
    private final String requestId;

    public AgentListenerAttributes(String method, String path, String remoteAddress,
                                   Map<String, String> headers, String requestId) {
        this.method = method;
        this.path = path;
        this.remoteAddress = remoteAddress;
        this.headers = headers;
        this.requestId = requestId;
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getRemoteAddress() { return remoteAddress; }
    public Map<String, String> getHeaders() { return headers; }
    public String getRequestId() { return requestId; }
}
