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
import java.util.List;
import java.util.Map;

/**
 * LlmClient implementation for the OpenAI Responses API (POST /v1/responses).
 *
 * stop_reason "tool_calls" → ToolCall populated; "end_turn" → final text ans
 * er.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final String MODELS_URL = "https://api.openai.com/v1/models";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final AgentComposerConfiguration config;

    public OpenAiLlmClient(AgentComposerConfiguration config) {
        this.config = config;
    }

    @Override
    public LlmResponse chat(String systemPrompt,
            List<LlmMessage> messages,
            List<ToolDefinition> tools) throws Exception {

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModelName());
        body.addProperty("max_output_tokens", config.getMaxTokens());
        if (supportsTemperature(config.getModelName())) {
            body.addProperty("temperature", config.getTemperature());
        }
        if (isGpt5Model(config.getModelName())) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "low");
            body.add("reasoning", reasoning);
        }
        body.addProperty("instructions", systemPrompt);

        // Build input array
        JsonArray inputArr = new JsonArray();
        for (LlmMessage msg : messages) {
            switch (msg.getRole()) {
                case "user": {
                    JsonObject item = new JsonObject();
                    item.addProperty("role", "user");
                    item.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
                    inputArr.add(item);
                    break;
                }
                case "assistant": {
                    // Text content block
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("role", "assistant");
                        JsonArray contentArr = new JsonArray();
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "output_text");
                        textBlock.addProperty("text", msg.getContent());
                        contentArr.add(textBlock);
                        item.add("content", contentArr);
                        inputArr.add(item);
                    }
                    // Each tool call becomes a separate function_call item
                    if (msg.hasToolCalls()) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            JsonObject fc = new JsonObject();
                            fc.addProperty("type", "function_call");
                            fc.addProperty("call_id", tc.getId());
                            fc.addProperty("name", tc.getName());
                            fc.addProperty("arguments", GSON.toJson(tc.getArguments()));
                            inputArr.add(fc);
                        }
                    }
                    break;
                }
                case "tool_result": {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "function_call_output");
                    item.addProperty("call_id", msg.getToolCallId());
                    item.addProperty("output", msg.getContent() != null ? msg.getContent() : "");
                    inputArr.add(item);
                    break;
                }
                default:
                    break;
            }
        }
        body.add("input", inputArr);

        // Tools
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (ToolDefinition tool : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                t.addProperty("name", tool.getName());
                t.addProperty("description", tool.getDescription() != null ? tool.getDescription() : "");
                t.add("parameters", GSON.toJsonTree(tool.getInputSchema()));
                toolsArr.add(t);
            }
            body.add("tools", toolsArr);
        }

        String responseBody = post(RESPONSES_URL, GSON.toJson(body));
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        String stopReason = root.has("stop_reason") ? root.get("stop_reason").getAsString() : "end_turn";

        String textContent = null;
        List<ToolCall> toolCalls = new java.util.ArrayList<>();

        // Fallback text collected from reasoning summary items when no message text is found.
        StringBuilder reasoningFallback = new StringBuilder();

        JsonArray output = root.has("output") ? root.getAsJsonArray("output") : new JsonArray();
        for (JsonElement el : output) {
            JsonObject item = el.getAsJsonObject();
            String type = item.has("type") ? item.get("type").getAsString() : "";

            if ("message".equals(type)) {
                // gpt-5.5 and similar models emit two message phases: "commentary" (chain of
                // thought) and "final" (the actual answer).  Prefer the "final" phase if present,
                // otherwise fall back to the first non-empty message content we find.
                String phase = item.has("phase") ? item.get("phase").getAsString() : null;
                JsonArray contentArr = item.has("content") ? item.getAsJsonArray("content") : new JsonArray();
                StringBuilder sb = new StringBuilder();
                for (JsonElement c : contentArr) {
                    JsonObject block = c.getAsJsonObject();
                    String blockType = block.has("type") ? block.get("type").getAsString() : "";
                    // Accept both "output_text" (standard) and "text" (some model variants).
                    if (("output_text".equals(blockType) || "text".equals(blockType))
                            && block.has("text") && !block.get("text").isJsonNull()) {
                        sb.append(block.get("text").getAsString());
                    }
                }
                String candidate = sb.length() > 0 ? sb.toString() : null;
                if (candidate != null) {
                    // Always prefer the "final" phase; keep the first non-null otherwise.
                    if ("final".equals(phase) || textContent == null) {
                        textContent = candidate;
                    }
                }

            } else if ("function_call".equals(type)) {
                String callId = item.has("call_id") ? item.get("call_id").getAsString() : "";
                String name   = item.has("name")    ? item.get("name").getAsString()    : "";
                String argsJson = item.has("arguments") ? item.get("arguments").getAsString() : "{}";
                Map<String, Object> args = GSON.fromJson(argsJson, MAP_TYPE);
                toolCalls.add(new ToolCall(callId, name, args));

            } else if ("reasoning".equals(type)) {
                // Reasoning models (gpt-5-nano, o-series) emit their chain-of-thought here.
                // Extract summary text as a fallback in case no message text is produced.
                if (item.has("summary") && item.get("summary").isJsonArray()) {
                    for (JsonElement s : item.getAsJsonArray("summary")) {
                        JsonObject sBlock = s.getAsJsonObject();
                        String sType = sBlock.has("type") ? sBlock.get("type").getAsString() : "";
                        if (("text".equals(sType) || "output_text".equals(sType))
                                && sBlock.has("text") && !sBlock.get("text").isJsonNull()) {
                            reasoningFallback.append(sBlock.get("text").getAsString());
                        }
                    }
                }
            }
        }

        // If the message produced no visible text but the reasoning summary has content, use that.
        // This prevents the "no tool calls and no final text" loop for reasoning-only models.
        if (textContent == null && reasoningFallback.length() > 0) {
            textContent = reasoningFallback.toString();
        }

        if (textContent == null && toolCalls.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(OpenAiLlmClient.class)
                    .debug("OpenAI response yielded no text and no tool calls. stop_reason='{}' raw={}",
                            stopReason, responseBody);
        }

        LlmResponse llmResponse = new LlmResponse(textContent, null, stopReason);
        llmResponse.setToolCalls(toolCalls);
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            llmResponse.setInputTokens(usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0);
            llmResponse.setOutputTokens(usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0);
        }
        return llmResponse;
    }

    /** Fetches available model IDs from the OpenAI /v1/models endpoint. */
    public List<String> listModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODELS_URL))
                .header("Authorization", "Bearer " + config.getApiKey())
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("OpenAI models error " + response.statusCode() + ": " + response.body());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        List<String> ids = new java.util.ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("data")) {
            ids.add(el.getAsJsonObject().get("id").getAsString());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private static boolean supportsTemperature(String modelName) {
        if (modelName == null)
            return true;

        String m = modelName.trim().toLowerCase();

        if (m.matches("^o\\d.*"))
            return false;

        if (m.startsWith("gpt-5-nano") || m.startsWith("gpt-5-mini")) {
            return false;
        }

        if (m.matches("^gpt-5(-[a-z0-9]+)*-nano.*"))
            return false;
        if (m.matches("^gpt-5(-[a-z0-9]+)*-mini.*"))
            return false;

        return true;
    }

    private static boolean isGpt5Model(String modelName) {
        if (modelName == null)
            return false;
        return modelName.trim().toLowerCase().startsWith("gpt-5");
    }

    private String post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
