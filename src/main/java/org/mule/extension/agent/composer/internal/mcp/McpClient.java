package org.mule.extension.agent.composer.internal.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
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

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Calls {@code tools/list} on the given MCP server and returns all tool
     * definitions, each stamped with the server URL for later routing.
     */
    public List<ToolDefinition> listTools(McpServerConfig server) {
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
                definitions.add(new ToolDefinition(name, description, schema, server.getServerUrl()));
            }
            return definitions;

        } catch (Exception e) {
            LOGGER.error("Failed to list tools from MCP server {}: {}", server.getServerUrl(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Calls {@code tools/call} on the MCP server that owns the given tool.
     *
     * @param server    the MCP server config whose {@code serverUrl} matches the tool's origin
     * @param toolName  name of the tool to invoke
     * @param arguments deserialized argument map as produced by the LLM
     * @return textual observation returned by the tool
     * @throws Exception on HTTP or JSON-RPC errors
     */
    public String callTool(McpServerConfig server, String toolName,
                           Map<String, Object> arguments) throws Exception {

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
            return "";
        }
        JsonObject result = resultEl.getAsJsonObject();

        // MCP tools/call result: { content: [ { type: "text", text: "..." } ], isError: false }
        if (result.has("content") && result.get("content").isJsonArray()) {
            JsonArray contentArr = result.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (JsonElement block : contentArr) {
                JsonObject blockObj = block.getAsJsonObject();
                if (blockObj.has("type") && "text".equals(blockObj.get("type").getAsString())) {
                    sb.append(blockObj.get("text").getAsString());
                }
            }
            return sb.toString();
        }

        // Fallback: return the raw result as a string
        return GSON.toJson(result);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String sendRpc(McpServerConfig server, String method, JsonObject params) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", idCounter.getAndIncrement());
        payload.addProperty("method", method);
        payload.add("params", params);

        String body = GSON.toJson(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getServerUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (server.getAuthToken() != null && !server.getAuthToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + server.getAuthToken());
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "MCP HTTP error " + response.statusCode() + " from " + server.getServerUrl()
                    + ": " + response.body());
        }
        return response.body();
    }
}
