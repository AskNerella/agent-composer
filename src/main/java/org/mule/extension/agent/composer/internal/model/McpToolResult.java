package org.mule.extension.agent.composer.internal.model;

import java.util.Map;

/**
 * Result of a {@code tools/call} MCP JSON-RPC invocation, augmented with
 * optional MCP-UI resource data.
 *
 * <p>The MCP-UI / MCP Apps specification allows tool responses to carry a
 * reference to an interactive HTML UI resource via two patterns:
 *
 * <ul>
 *   <li><b>MCP Apps pattern</b> – the JSON-RPC result contains a
 *       {@code _meta.ui.resourceUri} field. The host must fetch the resource
 *       by calling {@code resources/read} and render the returned HTML.</li>
 *   <li><b>Legacy MCP-UI pattern</b> – a content block of type {@code resource}
 *       with {@code mimeType: "text/html;profile=mcp-app"} is embedded directly
 *       inside the {@code content} array of the tool response.</li>
 * </ul>
 *
 * <p>When neither pattern is detected all UI fields are {@code null}.
 */
public class McpToolResult {

    /** Concatenated text extracted from {@code content[].type == "text"} blocks. */
    private final String textContent;

    /**
     * URI of the UI resource (e.g. {@code ui://my-server/widget}).
     * Set for both MCP Apps and legacy patterns.
     */
    private final String uiResourceUri;

    /**
     * Raw HTML string of the UI resource.
     * Populated after fetching via {@code resources/read} (MCP Apps) or directly
     * from the embedded content block (legacy).
     */
    private final String uiHtml;

    /**
     * MIME type of the UI resource, typically
     * {@code "text/html;profile=mcp-app"} for MCP Apps resources.
     */
    private final String uiMimeType;

    public McpToolResult(String textContent, String uiResourceUri, String uiHtml, String uiMimeType) {
        this.textContent = textContent;
        this.uiResourceUri = uiResourceUri;
        this.uiHtml = uiHtml;
        this.uiMimeType = uiMimeType;
    }

    /** Convenience constructor for plain (non-UI) tool results. */
    public McpToolResult(String textContent) {
        this(textContent, null, null, null);
    }

    public String getTextContent() { return textContent; }
    public String getUiResourceUri() { return uiResourceUri; }
    public String getUiHtml() { return uiHtml; }
    public String getUiMimeType() { return uiMimeType; }

    /** Returns {@code true} when this result carries a rendered MCP-UI resource. */
    public boolean hasUiResource() { return uiHtml != null && !uiHtml.isEmpty(); }

    /**
     * A server-initiated {@code elicitation/create} request received mid-{@code tools/call},
     * asking the client to collect additional structured input from the end user before the
     * MCP server can finish executing the tool.
     *
     * <p>Carries everything needed to resume the paused call later (possibly from a fresh
     * {@code McpClient} instance in a new agent invocation): the elicitation's own JSON-RPC id,
     * the original {@code tools/call} id it is blocking, and the tool/server identity.
     */
    public static final class ElicitationRequest {
        private final String requestId;
        private final long originalRequestId;
        private final String message;
        private final Map<String, Object> requestedSchema;
        private final String serverName;
        private final String serverUrl;
        private final String toolName;
        private final Map<String, Object> toolArguments;
        private final String defUiResourceUri;
        private final String sessionId;
        private final String protocolVersion;

        public ElicitationRequest(String requestId, long originalRequestId, String message,
                                   Map<String, Object> requestedSchema, String serverName, String serverUrl,
                                   String toolName, Map<String, Object> toolArguments, String defUiResourceUri,
                                   String sessionId) {
            this(requestId, originalRequestId, message, requestedSchema, serverName, serverUrl,
                    toolName, toolArguments, defUiResourceUri, sessionId, null);
        }

        public ElicitationRequest(String requestId, long originalRequestId, String message,
                                   Map<String, Object> requestedSchema, String serverName, String serverUrl,
                                   String toolName, Map<String, Object> toolArguments, String defUiResourceUri,
                                   String sessionId, String protocolVersion) {
            this.requestId = requestId;
            this.originalRequestId = originalRequestId;
            this.message = message;
            this.requestedSchema = requestedSchema;
            this.serverName = serverName;
            this.serverUrl = serverUrl;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.defUiResourceUri = defUiResourceUri;
            this.sessionId = sessionId;
            this.protocolVersion = protocolVersion;
        }

        public String getRequestId() { return requestId; }
        public long getOriginalRequestId() { return originalRequestId; }
        public String getMessage() { return message; }
        public Map<String, Object> getRequestedSchema() { return requestedSchema; }
        public String getServerName() { return serverName; }
        public String getServerUrl() { return serverUrl; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getToolArguments() { return toolArguments; }
        public String getDefUiResourceUri() { return defUiResourceUri; }
        public String getSessionId() { return sessionId; }
        public String getProtocolVersion() { return protocolVersion; }
    }

    /**
     * Outcome of a {@code tools/call} invocation: either the tool finished ({@link #getResult()}
     * populated) or the server paused mid-call to ask the user a question via MCP Elicitation
     * ({@link #getElicitation()} populated). Exactly one of the two is non-null.
     */
    public static final class CallOutcome {
        private final McpToolResult result;
        private final ElicitationRequest elicitation;

        private CallOutcome(McpToolResult result, ElicitationRequest elicitation) {
            this.result = result;
            this.elicitation = elicitation;
        }

        public static CallOutcome completed(McpToolResult result) { return new CallOutcome(result, null); }
        public static CallOutcome elicitationRequired(ElicitationRequest elicitation) { return new CallOutcome(null, elicitation); }

        public boolean isElicitationRequired() { return elicitation != null; }
        public McpToolResult getResult() { return result; }
        public ElicitationRequest getElicitation() { return elicitation; }
    }
}
