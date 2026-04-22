package org.mule.extension.agent.composer.internal.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.http.api.HttpService;
import org.mule.runtime.http.api.domain.entity.ByteArrayHttpEntity;
import org.mule.runtime.http.api.domain.message.response.HttpResponse;
import org.mule.runtime.http.api.server.HttpServer;
import org.mule.runtime.http.api.server.RequestHandlerManager;
import org.mule.runtime.http.api.server.async.HttpResponseReadyCallback;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.extension.api.annotation.param.Content;
import org.mule.sdk.api.annotation.Alias;
import org.mule.sdk.api.annotation.execution.OnError;
import org.mule.sdk.api.annotation.execution.OnSuccess;
import org.mule.sdk.api.annotation.execution.OnTerminate;
import org.mule.sdk.api.annotation.param.MediaType;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.display.DisplayName;
import org.mule.sdk.api.annotation.source.EmitsResponse;
import org.mule.sdk.api.runtime.operation.Result;
import org.mule.sdk.api.runtime.source.Source;
import org.mule.sdk.api.runtime.source.SourceCallback;
import org.mule.sdk.api.runtime.source.SourceCallbackContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Message source that attaches to an existing {@code <http:listener-config\>\} global element
 * (referenced by name in the connector configuration) and registers two HTTP endpoints:
 * <ul>
 *   <li>{@code GET /.well-known/agent.json} — serves the A2A agent card.</li>
 *   <li>{@code POST {agentPath}} — receives an A2A task, triggers the Mule flow,
 *       and synchronously returns the flow's output payload to the HTTP caller.</li>
 * </ul>
 *
 * <p>No separate "respond" operation is needed. The flow payload is automatically
 * sent back to the HTTP caller when the flow completes ({@code @OnSuccess}) or fails ({@code @OnError}).
 *
 * <p>Typical developer flow:
 * <pre>
 *   [Agent Listener] → [Execute Agent (userMessage="#[payload]")]
 * </pre>
 */
@EmitsResponse
@Alias("agent-listener")
@DisplayName("Agent Listener")
@MediaType(value = "application/json", strict = false)
public class AgentListenerSource extends Source<String, AgentListenerAttributes> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentListenerSource.class);
    private static final String RESPONSE_CALLBACK_VAR = "responseCallback";
    private static final String REQUEST_VAR = "requestId";
    private static final String TASK_ID_VAR = "taskId";

    @Config
    private AgentComposerConfiguration config;

    @Inject
    private HttpService httpService;

    private HttpServer httpServer;
    private RequestHandlerManager agentHandlerManager;
    private RequestHandlerManager cardHandlerManager;

    @Override
    public void onStart(SourceCallback<String, AgentListenerAttributes> sourceCallback) throws MuleException {
        String configName = config.getHttpListenerConfig();
        try {
            httpServer = httpService.getServerFactory().lookup(configName);
        } catch (Exception e) {
            throw new DefaultMuleException(
                    new RuntimeException("Agent Listener could not find HTTP Listener Config '"
                            + configName + "'. Ensure this name matches an <http:listener-config> in your Mule app.", e));
        }

        // Build agent card once at startup
        String agentCardJson = AgentCardBuilder.build(config);
        byte[] cardBytes  = agentCardJson.getBytes(StandardCharsets.UTF_8);

        String normalizedPath = config.getAgentPath().startsWith("/")
                ? config.getAgentPath() : "/" + config.getAgentPath();

        // ── GET {agentPath}/.well-known/agent.json ──────────────────────────
        String cardPath = normalizedPath + "/.well-known/agent.json";
        cardHandlerManager = httpServer.addRequestHandler(cardPath, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                sendResponse(responseCallback, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            sendResponseBytes(responseCallback, 200, cardBytes);
        });

        // ── POST {agentPath} ─────────────────────────────────────────────────
        agentHandlerManager = httpServer.addRequestHandler(normalizedPath, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponse(responseCallback, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String requestId = UUID.randomUUID().toString();

            try {
                // Read request body
                String body = "";
                try (InputStream is = requestCtx.getRequest().getEntity().getContent()) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.warn("Could not read request body for {}: {}", requestId, e.getMessage());
                }

                // Collect headers
                Map<String, String> headers = new LinkedHashMap<>();
                requestCtx.getRequest().getHeaderNames().forEach(name ->
                        headers.put(name, requestCtx.getRequest().getHeaderValue(name)));

                String remoteAddr = requestCtx.getClientConnection() != null
                        ? requestCtx.getClientConnection().getRemoteHostAddress().toString() : "unknown";

                AgentListenerAttributes attributes = new AgentListenerAttributes(
                        method,
                        requestCtx.getRequest().getPath(),
                        remoteAddr,
                        headers,
                        requestId);

                // Store HTTP callback in context so @OnSuccess / @OnError can send the response
                String taskId = extractTaskId(body);
                SourceCallbackContext ctx = sourceCallback.createContext();
                ctx.addVariable(RESPONSE_CALLBACK_VAR, responseCallback);
                ctx.addVariable(REQUEST_VAR, requestId);
                ctx.addVariable(TASK_ID_VAR, taskId);

                sourceCallback.handle(
                        Result.<String, AgentListenerAttributes>builder()
                                .output(extractUserMessage(body))
                                .attributes(attributes)
                                .build(),
                        ctx);
                // Async: response will be sent by @OnSuccess or @OnError

            } catch (Exception e) {
                LOGGER.error("Error handling agent task {}: {}", requestId, e.getMessage(), e);
                sendResponse(responseCallback, 500, "{\"error\":\"Internal server error\"}");
            }
        });

        LOGGER.info("Agent Listener attached to HTTP Listener Config '{}' | path={} card={}",
                configName, normalizedPath, cardPath);
    }

    @Override
    public void onStop() {
        if (agentHandlerManager != null) {
            agentHandlerManager.stop();
        }
        if (cardHandlerManager != null) {
            cardHandlerManager.stop();
        }
        LOGGER.info("Agent Listener detached from HTTP Listener Config '{}'.", config.getHttpListenerConfig());
    }

    /**
     * Called when the triggered Mule flow completes successfully.
     * Resolves the pending HTTP response with the flow's output payload.
     */
    @OnSuccess
    public void onSuccess(@Content TypedValue<Object> payload, SourceCallbackContext callbackContext) {
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            String text = (payload != null && payload.getValue() != null) ? payload.getValue().toString() : "";
            sendResponse(cb, 200, buildA2AResponse(taskId, "completed", text));
        });
    }

    /**
     * Called when the triggered Mule flow fails.
     * Resolves the pending HTTP response with an error JSON body.
     */
    @OnTerminate
    public void onTerminate(SourceCallbackContext callbackContext) {
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb ->
            sendResponse(cb, 503, buildA2AResponse(taskId, "canceled", "Flow terminated before response was sent")));
    }

    @OnError
    public void onError(@org.mule.sdk.api.annotation.param.Optional Error error, SourceCallbackContext callbackContext) {
        String requestId = callbackContext.<String>getVariable(REQUEST_VAR).orElse("unknown");
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        String msg = error != null ? error.getDescription() : "unknown";
        LOGGER.error("Flow error for agent request {}: {}", requestId, msg);
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb ->
                sendResponse(cb, 500, buildA2AResponse(taskId, "failed", msg)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the task id from an A2A task JSON body.
     * Falls back to a new UUID if not present.
     */
    private static String extractTaskId(String body) {
        try {
            JsonObject task = JsonParser.parseString(body).getAsJsonObject();
            if (task.has("id")) return task.get("id").getAsString();
        } catch (Exception ignored) {}
        return UUID.randomUUID().toString();
    }

    /**
     * Extracts the user message text from an A2A task JSON body.
     * Falls back to the raw body if the structure doesn't match.
     */
    private static String extractUserMessage(String body) {
        try {
            JsonObject task = JsonParser.parseString(body).getAsJsonObject();
            JsonObject message = task.getAsJsonObject("message");
            if (message != null) {
                JsonArray parts = message.getAsJsonArray("parts");
                if (parts != null && parts.size() > 0) {
                    JsonObject firstPart = parts.get(0).getAsJsonObject();
                    if (firstPart.has("text")) {
                        return firstPart.get("text").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return body; // fallback: pass raw body
    }

    /**
     * Builds an A2A-compliant Task response JSON.
     */
    private static String buildA2AResponse(String taskId, String state, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject message = new JsonObject();
        message.addProperty("role", "agent");
        message.add("parts", parts);
        JsonObject status = new JsonObject();
        status.addProperty("state", state);
        status.add("message", message);
        JsonObject task = new JsonObject();
        task.addProperty("id", taskId);
        task.add("status", status);
        return task.toString();
    }

    private static void sendResponse(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, String body) {
        sendResponseBytes(responseCallback, statusCode, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponseBytes(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, byte[] bytes) {
        HttpResponse response = HttpResponse.builder()
                .statusCode(statusCode)
                .addHeader("Content-Type", "application/json")
                .entity(new ByteArrayHttpEntity(bytes))
                .build();
        responseCallback.responseReady(response, new org.mule.runtime.http.api.server.async.ResponseStatusCallback() {
            @Override public void responseSendFailure(Throwable t) {
                LOGGER.warn("Failed to send HTTP response (status={}): {}", statusCode, t.getMessage());
            }
            @Override public void responseSendSuccessfully() { }
        });
    }
}
