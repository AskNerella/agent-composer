package org.mule.extension.agent.composer.internal.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.http.api.HttpService;
import org.mule.runtime.http.api.domain.entity.ByteArrayHttpEntity;
import org.mule.runtime.http.api.domain.entity.InputStreamHttpEntity;
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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
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
    private static final String IS_STREAMING_VAR = "isStreaming";

    @Config
    private AgentComposerConfiguration config;

    @Inject
    private HttpService httpService;

    @Inject
    private ObjectStoreManager objectStoreManager;

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

        // Build agent card once at startup — pass httpServer so the URL is absolute
        String agentCardJson = AgentCardBuilder.build(config, httpServer);
        byte[] cardBytes  = agentCardJson.getBytes(StandardCharsets.UTF_8);

        String normalizedPath = config.getAgentPath().startsWith("/")
                ? config.getAgentPath() : "/" + config.getAgentPath();

        // ── GET {agentPath}/.well-known/agent.json ──────────────────────────
        String cardPath = normalizedPath + "/.well-known/agent.json";
        cardHandlerManager = httpServer.addRequestHandler(cardPath, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            if (!requestCtx.getRequest().getPath().equals(cardPath)) {
                sendResponse(responseCallback, 404, "{\"error\":\"Not Found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                sendResponse(responseCallback, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            sendResponseBytes(responseCallback, 200, cardBytes);
        });

        // ── POST {agentPath} ─────────────────────────────────────────────────
        agentHandlerManager = httpServer.addRequestHandler(normalizedPath, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            if (!requestCtx.getRequest().getPath().equals(normalizedPath)) {
                sendResponse(responseCallback, 404, "{\"error\":\"Not Found\"}");
                return;
            }
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

                // ── Streaming: message/stream or tasks/sendSubscribe ─────────
                // These bypass the Mule flow so we can push SSE events per iteration.
                if (isStreamingRequest(body)) {
                    handleStreamingRequest(responseCallback, body, taskId);
                    return;
                }

                // ── Non-streaming: tasks/send ────────────────────────────────
                // Pass raw body to the Mule flow; @OnSuccess sends the final A2A JSON response.
                SourceCallbackContext ctx = sourceCallback.createContext();
                ctx.addVariable(RESPONSE_CALLBACK_VAR, responseCallback);
                ctx.addVariable(REQUEST_VAR, requestId);
                ctx.addVariable(TASK_ID_VAR, taskId);
                ctx.addVariable(IS_STREAMING_VAR, false);

                sourceCallback.handle(
                        Result.<String, AgentListenerAttributes>builder()
                                .output(body)
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
            // The Mule flow may return a JSON AgentResponse — check for input-required.
            try {
                com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                if (parsed.has("requiresInput") && parsed.get("requiresInput").getAsBoolean()) {
                    String question = parsed.has("inputRequest") ? parsed.get("inputRequest").getAsString() : text;
                    String conversationId = parsed.has("sessionId") ? parsed.get("sessionId").getAsString() : taskId;
                    sendResponse(cb, 200, buildA2AResponse(taskId, "input-required", question, conversationId));
                    return;
                }
            } catch (Exception ignored) {}
            sendResponse(cb, 200, buildA2AResponse(taskId, "completed", text, taskId));
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
            sendResponse(cb, 503, buildA2AResponse(taskId, "canceled", "Flow terminated before response was sent", taskId)));
    }

    @OnError
    public void onError(@org.mule.sdk.api.annotation.param.Optional Error error, SourceCallbackContext callbackContext) {
        String requestId = callbackContext.<String>getVariable(REQUEST_VAR).orElse("unknown");
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        String msg = error != null ? error.getDescription() : "unknown";
        LOGGER.error("Flow error for agent request {}: {}", requestId, msg);
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb ->
                sendResponse(cb, 500, buildA2AResponse(taskId, "failed", msg, taskId)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the request body contains {@code "method":"tasks/sendSubscribe"},
     * indicating an A2A streaming request that should respond with SSE.
     */
    /**
     * Handles a streaming A2A request ({@code message/stream} or {@code tasks/sendSubscribe}) by
     * running the ReAct engine directly in a background thread, writing one SSE
     * {@code task-status-update} event after each tool/skill execution, and a final event when
     * the agent produces its answer. This bypasses the Mule flow entirely so that events can
     * be flushed incrementally rather than waiting for flow completion.
     */
    private void handleStreamingRequest(HttpResponseReadyCallback responseCallback, String body, String taskId) {
        String userMessage = extractUserMessage(body);
        try {
            PipedOutputStream pipedOut = new PipedOutputStream();
            PipedInputStream pipedIn = new PipedInputStream(pipedOut, 131072);

            // Start the background thread BEFORE calling responseReady() so the PipedInputStream
            // is never empty when the HTTP runtime tries to read it (avoids a deadlock where the
            // read blocks on the same thread that would otherwise start the writer).
            // Send an initial SSE comment immediately so the HTTP runtime commits the response
            // headers and the client knows the stream is open before the first LLM round-trip.
            try {
                pipedOut.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}

            Thread thread = new Thread(() -> {
                try {
                    ReactEngine engine = new ReactEngine(objectStoreManager);
                    ReactEngine.IterationCallback callback = (iteration, actionName, observation) -> {
                        try {
                            // "calling..." is the sentinel emitted BEFORE tool execution.
                            // Everything else is a post-execution result event.
                            String summary = "calling...".equals(observation)
                                    ? "[Step " + iteration + "] Calling '" + actionName + "'"
                                    : "[Step " + iteration + "] '" + actionName + "' completed";
                            byte[] event = buildSseEvent(taskId, "working", summary, false)
                                    .getBytes(StandardCharsets.UTF_8);
                            pipedOut.write(event);
                            pipedOut.flush();
                        } catch (Exception e) {
                            LOGGER.warn("Could not write SSE event for task {}: {}", taskId, e.getMessage());
                        }
                    };
                    org.mule.extension.agent.composer.internal.model.AgentResponse result =
                            engine.run(config, userMessage, taskId, config.getMaxIterations(), callback);
                    if (result.isRequiresInput()) {
                        // Agent paused — tell the client to supply more info and resume
                        // by sending a new request with the same conversationId.
                        LOGGER.info("[SSE task {}] Agent requires input: {}", taskId, result.getInputRequest());
                        byte[] inputRequiredEvent = buildSseEvent(taskId, "input-required",
                                result.getInputRequest(), true)
                                .getBytes(StandardCharsets.UTF_8);
                        pipedOut.write(inputRequiredEvent);
                        pipedOut.flush();
                    } else {
                        byte[] finalEvent = buildSseEvent(taskId, "completed", result.getResponse(), true)
                                .getBytes(StandardCharsets.UTF_8);
                        pipedOut.write(finalEvent);
                        pipedOut.flush();
                    }
                } catch (Exception e) {
                    LOGGER.error("Streaming agent error for task {}: {}", taskId, e.getMessage(), e);
                    try {
                        byte[] errEvent = buildSseEvent(taskId, "failed",
                                "Agent execution failed: " + e.getMessage(), true)
                                .getBytes(StandardCharsets.UTF_8);
                        pipedOut.write(errEvent);
                        pipedOut.flush();
                    } catch (Exception ignored) {}
                } finally {
                    try { pipedOut.close(); } catch (Exception ignored) {}
                }
            }, "agent-sse-" + taskId);
            thread.setDaemon(true);
            thread.start();

            // Wire the response AFTER the writer thread is already running so the
            // PipedInputStream will have data as soon as the HTTP runtime reads it.
            HttpResponse sseResponse = HttpResponse.builder()
                    .statusCode(200)
                    .addHeader("Content-Type", "text/event-stream; charset=UTF-8")
                    .addHeader("Cache-Control", "no-cache, no-transform")
                    .addHeader("Connection", "keep-alive")
                    .addHeader("X-Accel-Buffering", "no")
                    .entity(new InputStreamHttpEntity(pipedIn))
                    .build();

            responseCallback.responseReady(sseResponse, new org.mule.runtime.http.api.server.async.ResponseStatusCallback() {
                @Override public void responseSendFailure(Throwable t) {
                    LOGGER.warn("SSE send failure for task {}: {}", taskId, t.getMessage());
                    try { pipedOut.close(); } catch (Exception ignored) {}
                }
                @Override public void responseSendSuccessfully() { }
            });

        } catch (Exception e) {
            LOGGER.error("Failed to set up SSE stream for task {}: {}", taskId, e.getMessage(), e);
            sendResponse(responseCallback, 500,
                    buildA2AResponse(taskId, "failed", "Streaming setup failed: " + e.getMessage(), taskId));
        }
    }

    /**
     * Extracts the user message text from a JSON-RPC 2.0 or flat A2A request body.
     * Checks {@code params.message.parts[0].text} first, then {@code message.parts[0].text},
     * and falls back to the raw body string.
     */
    private static String extractUserMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            // JSON-RPC 2.0: params.message.parts[0].text
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    String text = firstTextPart(params.getAsJsonObject("message"));
                    if (text != null) return text;
                }
            }
            // Flat A2A: message.parts[0].text
            if (root.has("message") && root.get("message").isJsonObject()) {
                String text = firstTextPart(root.getAsJsonObject("message"));
                if (text != null) return text;
            }
        } catch (Exception ignored) {}
        return body;
    }

    private static String firstTextPart(JsonObject message) {
        try {
            JsonArray parts = message.getAsJsonArray("parts");
            if (parts != null && parts.size() > 0) {
                JsonObject part = parts.get(0).getAsJsonObject();
                if (part.has("text")) return part.get("text").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isStreamingRequest(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (!obj.has("method")) return false;
            String method = obj.get("method").getAsString();
            return "tasks/sendSubscribe".equals(method) || "message/stream".equals(method);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Builds an A2A-compliant SSE event string.
     * The {@code conversationId} (= taskId for streaming) is included so clients can
     * resume a paused conversation by sending a new request with that id.
     */
    private static String buildSseEvent(String taskId, String state, String text, boolean isFinal) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text != null ? text : "");
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
        task.addProperty("conversationId", taskId);
        task.add("status", status);
        task.addProperty("final", isFinal);
        return "event: task-status-update\ndata: " + task + "\n\n";
    }

    /**
     * Extracts the task id from an A2A task JSON body.
     * Falls back to a new UUID if not present.
     */
    private static String extractTaskId(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            // JSON-RPC top-level id
            if (root.has("id")) return root.get("id").getAsString();
            // A2A flat envelope id
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    JsonObject msg = params.getAsJsonObject("message");
                    if (msg.has("messageId")) return msg.get("messageId").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return UUID.randomUUID().toString();
    }

    /**
     * Builds an A2A-compliant Task response JSON, including the conversationId so
     * clients can use it to resume a paused (input-required) conversation.
     */
    private static String buildA2AResponse(String taskId, String state, String text, String conversationId) {
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
        if (conversationId != null) {
            task.addProperty("conversationId", conversationId);
        }
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
