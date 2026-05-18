package org.mule.extension.agent.composer.internal.model;

import java.io.Serializable;
import java.util.Map;

/**
 * Describes a single tool exposed by an MCP server.
 *
 * The {@code inputSchema} field holds a JSON-Schema compatible map (the same
 * object returned by the MCP {@code tools/list} response) so that it can be
 * forwarded verbatim to both OpenAI ({@code parameters}) and Anthropic
 * ({@code input_schema}).
 */
public class ToolDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String description;

    /** Raw JSON-Schema definition of the tool's inputs, as a deserialized Map. */
    private Map<String, Object> inputSchema;

    /**
     * URL of the MCP server that hosts this tool – used to route the
     * {@code tools/call} JSON-RPC request back to the correct endpoint.
     */
    private String serverUrl;

    /**
     * MCP Apps UI resource URI declared in the tool's {@code _meta.ui.resourceUri}
     * field (returned by {@code tools/list}). When non-null the client should call
     * {@code resources/read} with this URI to obtain the interactive HTML widget
     * for this tool call.
     */
    private String uiResourceUri;

    // ── constructors ──────────────────────────────────────────────────────────

    public ToolDefinition() {}

    public ToolDefinition(String name, String description,
                          Map<String, Object> inputSchema, String serverUrl) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.serverUrl = serverUrl;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUiResourceUri() { return uiResourceUri; }
    public void setUiResourceUri(String uiResourceUri) { this.uiResourceUri = uiResourceUri; }
}
