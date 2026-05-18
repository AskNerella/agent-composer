package org.mule.extension.agent.composer.internal.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.error.AgentComposerErrors;
import org.mule.sdk.api.exception.ModuleException;
import org.mule.extension.agent.composer.internal.model.McpToolResult;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin JSON-RPC 2.0 client for MCP (Model Context Protocol) servers.
 *
 * <p>Supported methods:
 * <ul>
 *   <li>{@code tools/list}  – discover the tools offered by a server</li>
 *   <li>{@code tools/call}  – invoke a specific tool and return the text result</li>
 * </ul>
 *
 * <p>Authentication: if the {@link McpServerConfig} carries an auth token it is
 * forwarded as a {@code Authorization: Bearer <token>} header.
 */
public class McpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpClient.class);
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Monotonically increasing JSON-RPC request id. */
    private final AtomicLong idCounter = new AtomicLong(1);

    /** Session IDs cached per server URL, obtained during MCP initialization handshake. */
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Calls {@code tools/list} on the given MCP server and returns all tool
     * definitions, each stamped with the server URL for later routing.
     */
    public List<ToolDefinition> listTools(McpServerConfig server) throws ModuleException {
        try {
            JsonObject params = new JsonObject();
            String responseBody = sendRpc(server, "tools/list", params);

            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonElement resultEl = root.get("result");
            if (resultEl == null || !resultEl.isJsonObject()) {
                LOGGER.warn("MCP server {} returned no result; response: {}", server.getServerUrl(), responseBody);
                return Collections.emptyList();
            }
            JsonElement toolsEl = resultEl.getAsJsonObject().get("tools");
            if (toolsEl == null || !toolsEl.isJsonArray()) {
                LOGGER.warn("MCP server {} returned no tools array; response: {}", server.getServerUrl(), responseBody);
                return Collections.emptyList();
            }

            List<ToolDefinition> definitions = new ArrayList<>();
            for (JsonElement t : toolsEl.getAsJsonArray()) {
                JsonObject tool = t.getAsJsonObject();
                String name = tool.has("name") ? tool.get("name").getAsString() : null;
                String description = tool.has("description") ? tool.get("description").getAsString() : null;
                Map<String, Object> schema = tool.has("inputSchema")
                        ? GSON.fromJson(tool.get("inputSchema"), MAP_TYPE)
                        : Collections.emptyMap();
                ToolDefinition def = new ToolDefinition(name, description, schema, server.getServerUrl());

                // MCP Apps: extract _meta.ui.resourceUri from the tool definition
                // so the client knows to fetch the interactive HTML widget for this tool.
                if (tool.has("_meta")) {
                    JsonObject meta = tool.getAsJsonObject("_meta");
                    if (meta.has("ui")) {
                        JsonObject ui = meta.getAsJsonObject("ui");
                        if (ui.has("resourceUri")) {
                            String uiUri = ui.get("resourceUri").getAsString();
                            def.setUiResourceUri(uiUri);
                            LOGGER.info("[MCP Apps] Tool '{}' has uiResourceUri: {}", name, uiUri);
                        }
                    }
                }

                definitions.add(def);
            }
            return definitions;

        } catch (ModuleException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to list tools from MCP server {} (client '{}'): {}",
                    server.getServerUrl(), server.getName(), e.getMessage(), e);
            throw new ModuleException(
                    "Unable to fetch tools from MCP server '" + server.getName() + "' at " + server.getServerUrl() + ": " + e.getMessage(),
                    AgentComposerErrors.UNABLE_TO_FETCH_TOOLS,
                    e);
        }
    }

    /**
     * Calls {@code tools/call} on the MCP server that owns the given tool.
     *
     * <p>Supports both MCP-UI patterns:
     * <ul>
     *   <li><b>MCP Apps pattern</b> – the tool definition (from {@code tools/list})
     *       carries {@code _meta.ui.resourceUri}; the client fetches that resource
     *       via {@code resources/read} and returns its HTML.  If the call result
     *       itself also contains {@code _meta.ui.resourceUri} it is used as a
     *       fallback when no definition-level URI was supplied.</li>
     *   <li><b>Legacy MCP-UI pattern</b> – if a content block of type
     *       {@code resource} carries {@code mimeType: "text/html;profile=mcp-app"},
     *       the HTML is extracted directly from the response.</li>
     * </ul>
     *
     * @param server           the MCP server config whose {@code serverUrl} matches the tool's origin
     * @param toolName         name of the tool to invoke
     * @param arguments        deserialized argument map as produced by the LLM
     * @param defUiResourceUri optional {@code _meta.ui.resourceUri} from the tool definition
     *                         (populated by {@link #listTools}); {@code null} for plain tools
     * @return {@link McpToolResult} with text content and optional UI resource fields
     * @throws Exception on HTTP or JSON-RPC errors
     */
    public McpToolResult callTool(McpServerConfig server, String toolName,
                           Map<String, Object> arguments,
                           String defUiResourceUri) throws Exception {

        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", GSON.toJsonTree(arguments));

        String responseBody = sendRpc(server, "tools/call", params);
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        // JSON-RPC error object
        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            throw new RuntimeException(
                    "MCP tool call error (code=" + err.get("code").getAsInt()
                    + "): " + err.get("message").getAsString());
        }

        JsonElement resultEl = root.get("result");
        if (resultEl == null || resultEl.isJsonNull()) {
            return new McpToolResult("");
        }
        JsonObject result = resultEl.getAsJsonObject();

        // ── MCP Apps pattern: _meta.ui.resourceUri ────────────────────────────
        // Primary source: URI from the tool definition (tools/list).
        // Fallback: URI echoed back in the call result (some servers include both).
        String mcpAppsUiResourceUri = defUiResourceUri;
        if (mcpAppsUiResourceUri == null && result.has("_meta")) {
            JsonObject meta = result.getAsJsonObject("_meta");
            if (meta.has("ui")) {
                JsonObject ui = meta.getAsJsonObject("ui");
                if (ui.has("resourceUri")) {
                    mcpAppsUiResourceUri = ui.get("resourceUri").getAsString();
                }
            }
        }

        // ── Parse content blocks ──────────────────────────────────────────────
        StringBuilder textBuilder = new StringBuilder();
        String legacyUiHtml = null;
        String legacyUiResourceUri = null;
        String legacyUiMimeType = null;

        if (result.has("content") && result.get("content").isJsonArray()) {
            JsonArray contentArr = result.getAsJsonArray("content");
            for (JsonElement block : contentArr) {
                JsonObject blockObj = block.getAsJsonObject();
                String type = blockObj.has("type") ? blockObj.get("type").getAsString() : "";

                if ("text".equals(type)) {
                    textBuilder.append(blockObj.get("text").getAsString());

                } else if ("resource".equals(type) && blockObj.has("resource")) {
                    // Legacy MCP-UI: resource block embedded directly in tool response
                    JsonObject res = blockObj.getAsJsonObject("resource");
                    String mimeType = res.has("mimeType") ? res.get("mimeType").getAsString() : "";
                    if (mimeType.startsWith("text/html")) {
                        legacyUiMimeType = mimeType;
                        legacyUiResourceUri = res.has("uri") ? res.get("uri").getAsString() : null;
                        if (res.has("text")) {
                            legacyUiHtml = res.get("text").getAsString();
                        } else if (res.has("blob")) {
                            // Base64-encoded HTML content
                            legacyUiHtml = new String(
                                    java.util.Base64.getDecoder().decode(res.get("blob").getAsString()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } else {
            // Fallback: return the raw result as a string
            textBuilder.append(GSON.toJson(result));
        }

        String textContent = textBuilder.toString();

        // ── Resolve UI resource ───────────────────────────────────────────────
        if (mcpAppsUiResourceUri != null) {
            // MCP Apps pattern: fetch resource via resources/read
            LOGGER.info("[MCP Apps] Fetching UI resource for tool '{}' via resources/read: {}", toolName, mcpAppsUiResourceUri);
            try {
                McpToolResult uiResource = readResource(server, mcpAppsUiResourceUri);
                String fetchedHtml = uiResource.getUiHtml();
                LOGGER.info("[MCP Apps] UI resource fetched for tool '{}': mimeType={}, htmlLength={}",
                        toolName, uiResource.getUiMimeType(), fetchedHtml != null ? fetchedHtml.length() : 0);
                return new McpToolResult(textContent, mcpAppsUiResourceUri,
                        fetchedHtml, uiResource.getUiMimeType());
            } catch (Exception e) {
                LOGGER.warn("[MCP Apps] Failed to fetch UI resource '{}' from '{}': {}",
                        mcpAppsUiResourceUri, server.getName(), e.getMessage());
                return new McpToolResult(textContent, mcpAppsUiResourceUri, null, null);
            }
        }

        if (legacyUiHtml != null) {
            // Legacy MCP-UI pattern: HTML was embedded directly in the response
            return new McpToolResult(textContent, legacyUiResourceUri, legacyUiHtml, legacyUiMimeType);
        }

        return new McpToolResult(textContent);
    }

    /**
     * Calls {@code resources/read} on the MCP server to fetch a UI resource by URI.
     *
     * @param server      the MCP server to query
     * @param resourceUri the {@code ui://} URI of the resource to fetch
     * @return {@link McpToolResult} with {@code uiHtml} and {@code uiMimeType} populated
     * @throws Exception on HTTP or JSON-RPC errors
     */
    public McpToolResult readResource(McpServerConfig server, String resourceUri) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", resourceUri);

        String responseBody = sendRpc(server, "resources/read", params);
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            throw new RuntimeException(
                    "MCP resources/read error (code=" + err.get("code").getAsInt()
                    + "): " + err.get("message").getAsString());
        }

        JsonElement resultEl = root.get("result");
        if (resultEl == null || resultEl.isJsonNull()) {
            throw new RuntimeException("MCP resources/read returned empty result for URI: " + resourceUri);
        }

        JsonObject result = resultEl.getAsJsonObject();
        JsonArray contents = result.has("contents") ? result.getAsJsonArray("contents") : new JsonArray();

        for (JsonElement c : contents) {
            JsonObject content = c.getAsJsonObject();
            String mimeType = content.has("mimeType") ? content.get("mimeType").getAsString() : "";
            if (mimeType.startsWith("text/html")) {
                String html = null;
                if (content.has("text")) {
                    html = content.get("text").getAsString();
                } else if (content.has("blob")) {
                    html = new String(
                            java.util.Base64.getDecoder().decode(content.get("blob").getAsString()),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                if (html != null) {
                    return new McpToolResult(null, resourceUri, html, mimeType);
                }
            }
        }

        throw new RuntimeException("MCP resources/read returned no HTML content for URI: " + resourceUri);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Ensures a session exists for the server (lazy initialization).
     * Sends the MCP {@code initialize} handshake and caches the returned
     * {@code Mcp-Session-Id} response header.
     */
    private String getOrInitSession(McpServerConfig server) throws Exception {
        return sessionIds.computeIfAbsent(server.getServerUrl(), url -> {
            try {
                return initializeSession(server);
            } catch (Exception e) {
                throw new RuntimeException("MCP session initialization failed for '" + server.getName() + "': " + e.getMessage(), e);
            }
        });
    }

    private String initializeSession(McpServerConfig server) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "agent-composer");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);

        JsonObject capabilities = new JsonObject();
        // Declare MCP Apps / MCP-UI extension support (SEP-1724 pattern).
        // This signals to MCP servers that the client can render HTML UI resources
        // with MIME type "text/html;profile=mcp-app" and will call resources/read
        // on the URIs advertised in each tool's _meta.ui.resourceUri field.
        JsonObject extensions = new JsonObject();
        JsonObject uiCapability = new JsonObject();
        uiCapability.addProperty("uiProtocolVersion", "1.0");
        extensions.add("ui", uiCapability);
        capabilities.add("extensions", extensions);
        params.add("capabilities", capabilities);

        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", idCounter.getAndIncrement());
        payload.addProperty("method", "initialize");
        payload.add("params", params);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getServerUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));

        if (server.getAuthToken() != null && !server.getAuthToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + server.getAuthToken());
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("MCP initialize error " + response.statusCode() + ": " + response.body());
        }

        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionId != null) {
            LOGGER.info("MCP session established for '{}': sessionId={}", server.getName(), sessionId);
        } else {
            LOGGER.info("MCP server '{}' did not return a session ID — proceeding without one.", server.getName());
        }
        return sessionId != null ? sessionId : "";
    }

    private String sendRpc(McpServerConfig server, String method, JsonObject params) throws Exception {
        String sessionId = getOrInitSession(server);

        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", idCounter.getAndIncrement());
        payload.addProperty("method", method);
        payload.add("params", params);

        String body = GSON.toJson(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getServerUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (!sessionId.isEmpty()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (server.getAuthToken() != null && !server.getAuthToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + server.getAuthToken());
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        // Session expired or missing — evict and retry once with a fresh session
        if (response.statusCode() == 400) {
            String responseBody = response.body();
            if (responseBody.contains("session")) {
                LOGGER.warn("MCP session invalid for '{}', re-initializing...", server.getName());
                sessionIds.remove(server.getServerUrl());
                sessionId = getOrInitSession(server);

                builder = HttpRequest.newBuilder()
                        .uri(URI.create(server.getServerUrl()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (!sessionId.isEmpty()) {
                    builder.header("Mcp-Session-Id", sessionId);
                }
                if (server.getAuthToken() != null && !server.getAuthToken().isEmpty()) {
                    builder.header("Authorization", "Bearer " + server.getAuthToken());
                }
                response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            }
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "MCP HTTP error " + response.statusCode() + " from " + server.getServerUrl()
                    + ": " + response.body());
        }
        return extractJson(response.body());
    }

    /**
     * MCP Streamable HTTP servers may respond with SSE-formatted bodies:
     * <pre>
     *   event: message
     *   data: {"jsonrpc":"2.0","id":1,"result":{...}}
     * </pre>
     * This method extracts the first {@code data:} payload so the rest of the
     * code always receives plain JSON regardless of transport encoding.
     */
    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        // Fast-path: already plain JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        // SSE format: look for the first "data: " line that carries a JSON object/array
        for (String line : trimmed.split("\n")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("data:")) {
                String json = stripped.substring(5).stripLeading();
                if (json.startsWith("{") || json.startsWith("[")) {
                    return json;
                }
            }
        }
        // Return as-is and let the caller's JsonParser produce a descriptive error
        return raw;
    }
}
