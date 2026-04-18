package org.mule.extension.agent.composer.internal.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.extension.agent.composer.internal.model.ToolCall;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LlmClient implementation for the Anthropic Messages API (POST /v1/messages).
 *
 * Key conventions:
 * - System prompt is a top-level "system" field.
 * - Tool results are wrapped inside a "user" message with a "tool_result" content block.
 * - Assistant turns with tool use carry mixed "text" and "tool_use" content blocks.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final AgentComposerConfiguration config;

    public AnthropicLlmClient(AgentComposerConfiguration config) {
        this.config = config;
    }

    @Override
    public LlmResponse chat(String systemPrompt,
                            List<LlmMessage> messages,
                            List<ToolDefinition> tools) throws Exception {

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModelName());
        body.addProperty("max_tokens", config.getMaxTokens());
        body.addProperty("temperature", config.getTemperature());
        body.addProperty("system", systemPrompt);

        JsonArray msgsArr = new JsonArray();
        for (LlmMessage msg : messages) {
            switch (msg.getRole()) {
                case "user": {
                    JsonObject item = new JsonObject();
                    item.addProperty("role", "user");
                    item.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
                    msgsArr.add(item);
                    break;
                }
                case "assistant": {
                    JsonObject item = new JsonObject();
                    item.addProperty("role", "assistant");
                    JsonArray contentArr = new JsonArray();
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "text");
                        textBlock.addProperty("text", msg.getContent());
                        contentArr.add(textBlock);
                    }
                    if (msg.hasToolCalls()) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            JsonObject tuBlock = new JsonObject();
                            tuBlock.addProperty("type", "tool_use");
                            tuBlock.addProperty("id", tc.getId());
                            tuBlock.addProperty("name", tc.getName());
                            tuBlock.add("input", GSON.toJsonTree(tc.getArguments()));
                            contentArr.add(tuBlock);
                        }
                    }
                    item.add("content", contentArr);
                    msgsArr.add(item);
                    break;
                }
                case "tool_result": {
                    // Anthropic wraps tool results inside a user message
                    JsonObject item = new JsonObject();
                    item.addProperty("role", "user");
                    JsonArray contentArr = new JsonArray();
                    JsonObject trBlock = new JsonObject();
                    trBlock.addProperty("type", "tool_result");
                    trBlock.addProperty("tool_use_id", msg.getToolCallId());
                    trBlock.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
                    contentArr.add(trBlock);
                    item.add("content", contentArr);
                    msgsArr.add(item);
                    break;
                }
                default:
                    break;
            }
        }
        body.add("messages", msgsArr);

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (ToolDefinition tool : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("name", tool.getName());
                t.addProperty("description", tool.getDescription() != null ? tool.getDescription() : "");
                t.add("input_schema", GSON.toJsonTree(tool.getInputSchema()));
                toolsArr.add(t);
            }
            body.add("tools", toolsArr);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MESSAGES_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", config.getAnthropicVersion())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> httpResponse = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new RuntimeException("Anthropic API error " + httpResponse.statusCode() + ": " + httpResponse.body());
        }

        JsonObject root = JsonParser.parseString(httpResponse.body()).getAsJsonObject();
        String stopReason = root.has("stop_reason") ? root.get("stop_reason").getAsString() : "end_turn";

        StringBuilder textContent = new StringBuilder();
        ToolCall toolCall = null;

        for (JsonElement el : root.getAsJsonArray("content")) {
            JsonObject block = el.getAsJsonObject();
            String type = block.get("type").getAsString();
            if ("text".equals(type)) {
                textContent.append(block.get("text").getAsString());
            } else if ("tool_use".equals(type) && toolCall == null) {
                String id   = block.get("id").getAsString();
                String name = block.get("name").getAsString();
                Map<String, Object> input = GSON.fromJson(block.get("input"), MAP_TYPE);
                toolCall = new ToolCall(id, name, input);
            }
        }

        String content = textContent.length() > 0 ? textContent.toString() : null;
        return new LlmResponse(content, toolCall, stopReason);
    }

    public List<String> listModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models"))
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", config.getAnthropicVersion())
                .GET()
                .build();

        HttpResponse<String> httpResponse = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new RuntimeException("Anthropic list models failed [" + httpResponse.statusCode() + "]: " + httpResponse.body());
        }

        JsonObject root = JsonParser.parseString(httpResponse.body()).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (JsonElement el : data) {
                models.add(el.getAsJsonObject().get("id").getAsString());
            }
        }
        return models;
    }
}
