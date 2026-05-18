package org.mule.extension.agent.composer.internal.model;

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
}
