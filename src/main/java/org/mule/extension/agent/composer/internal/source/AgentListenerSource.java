package org.mule.extension.agent.composer.internal.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.store.ObjectStore;
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
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Message source that attaches to an existing {@code <http:listener-config\>\} global element
 * (referenced by name in the connector configuration) and registers two HTTP endpoints:
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} — serves the A2A 0.3.0 agent card.</li>
 *   <li>{@code POST {agentPath}} — receives an A2A task, triggers the Mule flow,
 *       and synchronously returns the flow's output payload to the HTTP caller.</li>
 * </ul>
 *
 * <p>No separate "respond" operation is needed. The flow payload is automatically
 * sent back to the HTTP caller when the flow completes ({@code @OnSuccess}) or fails ({@code @OnError}).
 *
 * <p>Typical developer flow:
 * <pre>
 *   [Agent Listener] → [Execute Agent]
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
    private static final String CONTEXT_ID_VAR = "contextId";
    private static final String RPC_ID_VAR = "rpcId";
    private static final String IS_JSONRPC_VAR = "isJsonRpc";
    private static final String IS_STREAMING_VAR = "isStreaming";
    private static final String TASK_STORE_PREFIX = "a2a:task:";
    private static final String CARD_SUFFIX = "/.well-known/agent-card.json";
    private static final String LEGACY_CARD_SUFFIX = "/.well-known/agent.json";

    @Config
    private AgentComposerConfiguration config;

    @Inject
    private HttpService httpService;

    @Inject
    private ObjectStoreManager objectStoreManager;

    private HttpServer httpServer;
    private RequestHandlerManager agentHandlerManager;
    private RequestHandlerManager cardHandlerManager;
    private RequestHandlerManager scopedCardHandlerManager;
    private RequestHandlerManager legacyCardHandlerManager;

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

        // ── GET {agentPath}/.well-known/agent-card.json  +  {agentPath}/.well-known/agent.json ──
        String scopedCardPath = normalizedPath + CARD_SUFFIX;
        String legacyCardPath = normalizedPath + LEGACY_CARD_SUFFIX;
        cardHandlerManager = addCardHandler(scopedCardPath, cardBytes);
        scopedCardHandlerManager = addCardHandler(legacyCardPath, cardBytes);

        // ── POST {agentPath} ─────────────────────────────────────────────────
        agentHandlerManager = httpServer.addRequestHandler(normalizedPath, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                sendProtocolError(responseCallback, 405, -32005, "Method Not Allowed", false, null);
                return;
            }

            String requestId = UUID.randomUUID().toString();
            String body = "";
            String rpcIdJson = null;
            boolean isJsonRpc = false;

            try {
                // Read request body
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

                rpcIdJson = extractRpcIdJson(body);
                isJsonRpc = rpcIdJson != null;
                String methodName = extractMethodName(body);
                JsonObject requestRoot = parseJsonObject(body);
                if (requestRoot != null && requestRoot.has("jsonrpc")
                    && (methodName == null || methodName.trim().isEmpty())) {
                    sendProtocolError(responseCallback, 400, -32600,
                        "Invalid Request: missing required method.", true, rpcIdJson);
                    return;
                }

                if (isTaskGetRequest(methodName)) {
                    handleTaskGetRequest(responseCallback, body, isJsonRpc, rpcIdJson);
                    return;
                }

                if (isTaskCancelRequest(methodName)) {
                    handleTaskCancelRequest(responseCallback, body, isJsonRpc, rpcIdJson);
                    return;
                }

                if (methodName != null && !isMessageSendRequest(methodName) && !isStreamingRequest(methodName)) {
                    sendProtocolError(responseCallback, 501, -32004,
                            "This operation is not supported by Agent Listener.", isJsonRpc, rpcIdJson);
                    return;
                }

                String requestContextId = extractMessageContextId(body);
                String taskId = extractMessageTaskId(body);
                String contextId = resolveContextId(requestContextId, taskId);

                AgentListenerAttributes attributes = new AgentListenerAttributes(
                        method,
                        requestCtx.getRequest().getPath(),
                        remoteAddr,
                        headers,
                        requestId);

                // Store HTTP callback in context so @OnSuccess / @OnError can send the response
                persistTask(buildTask(taskId, contextId, "submitted", null, null, null));

                // ── Streaming: message/stream or tasks/sendSubscribe ─────────
                // These bypass the Mule flow so we can push SSE events per iteration.
                if (isStreamingRequest(methodName)) {
                    handleStreamingRequest(responseCallback, body, taskId, contextId, rpcIdJson, isJsonRpc);
                    return;
                }

                // ── Non-streaming: message/send ──────────────────────────────
                // Pass raw body to the Mule flow; @OnSuccess sends the final A2A JSON response.
                SourceCallbackContext ctx = sourceCallback.createContext();
                ctx.addVariable(RESPONSE_CALLBACK_VAR, responseCallback);
                ctx.addVariable(REQUEST_VAR, requestId);
                ctx.addVariable(TASK_ID_VAR, taskId);
                ctx.addVariable(CONTEXT_ID_VAR, contextId);
                ctx.addVariable(RPC_ID_VAR, rpcIdJson != null ? rpcIdJson : "null");
                ctx.addVariable(IS_JSONRPC_VAR, isJsonRpc);
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
                sendProtocolError(responseCallback, 500, -32000,
                        "Internal server error", isJsonRpc, rpcIdJson);
            }
        });

        LOGGER.info("Agent Listener attached to HTTP Listener Config '{}' | path={} card={}",
                configName, normalizedPath, scopedCardPath);
    }

    @Override
    public void onStop() {
        if (agentHandlerManager != null) {
            agentHandlerManager.stop();
        }
        if (cardHandlerManager != null) {
            cardHandlerManager.stop();
        }
        if (scopedCardHandlerManager != null) {
            scopedCardHandlerManager.stop();
        }
        if (legacyCardHandlerManager != null) {
            legacyCardHandlerManager.stop();
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
        String contextId = callbackContext.<String>getVariable(CONTEXT_ID_VAR).orElse(taskId);
        String rpcIdJson = callbackContext.<String>getVariable(RPC_ID_VAR).orElse("null");
        boolean isJsonRpc = callbackContext.<Boolean>getVariable(IS_JSONRPC_VAR).orElse(false);
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject canceledTask = getCanceledTask(taskId);
            if (canceledTask != null) {
                sendResponse(cb, 200, wrapTaskResponse(canceledTask, isJsonRpc, rpcIdJson));
                return;
            }

            String rawPayload = stringifyPayload(payload);
            JsonObject agentResponse = parseJsonObject(rawPayload);
            if (isInputRequired(agentResponse)) {
                String question = extractInputRequest(agentResponse, rawPayload);
                JsonObject task = buildTask(taskId, contextId, "input-required", question, null, null);
                persistTask(task);
                sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson));
                return;
            }

            String agentOutput = extractAgentOutput(rawPayload, agentResponse);
            String uiHtml = extractFirstUiHtml(agentResponse);
            JsonObject task = buildTask(taskId, contextId, "completed", null, agentOutput, uiHtml);
            persistTask(task);
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson));
        });
    }

    /**
     * Called when the triggered Mule flow fails.
     * Resolves the pending HTTP response with an error JSON body.
     */
    @OnTerminate
    public void onTerminate(SourceCallbackContext callbackContext) {
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        String contextId = callbackContext.<String>getVariable(CONTEXT_ID_VAR).orElse(taskId);
        String rpcIdJson = callbackContext.<String>getVariable(RPC_ID_VAR).orElse("null");
        boolean isJsonRpc = callbackContext.<Boolean>getVariable(IS_JSONRPC_VAR).orElse(false);
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject task = getCanceledTask(taskId);
            if (task == null) {
                task = buildTask(taskId, contextId, "canceled", "Flow terminated before response was sent", null, null);
                persistTask(task);
            }
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson));
        });
    }

    @OnError
    public void onError(@org.mule.sdk.api.annotation.param.Optional Error error, SourceCallbackContext callbackContext) {
        String requestId = callbackContext.<String>getVariable(REQUEST_VAR).orElse("unknown");
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        String contextId = callbackContext.<String>getVariable(CONTEXT_ID_VAR).orElse(taskId);
        String rpcIdJson = callbackContext.<String>getVariable(RPC_ID_VAR).orElse("null");
        boolean isJsonRpc = callbackContext.<Boolean>getVariable(IS_JSONRPC_VAR).orElse(false);
        String msg = error != null ? error.getDescription() : "unknown";
        LOGGER.error("Flow error for agent request {}: {}", requestId, msg);
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject task = getCanceledTask(taskId);
            if (task == null) {
                task = buildTask(taskId, contextId, "failed", msg, null, null);
                persistTask(task);
            }
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson));
        });
    }

    // ── A2A + card helpers ────────────────────────────────────────────────────

    private RequestHandlerManager addCardHandler(String path, byte[] cardBytes) {
        return httpServer.addRequestHandler(path, (requestCtx, responseCallback) -> {
            if (!"GET".equalsIgnoreCase(requestCtx.getRequest().getMethod())) {
                sendProtocolError(responseCallback, 405, -32005, "Method Not Allowed", false, null);
                return;
            }
            sendResponseBytes(responseCallback, 200, cardBytes);
        });
    }

    private void handleTaskGetRequest(HttpResponseReadyCallback responseCallback, String body, boolean isJsonRpc, String rpcIdJson) {
        String taskId = extractTaskOperationId(body);
        if (taskId == null || taskId.trim().isEmpty()) {
            sendProtocolError(responseCallback, 400, -32602, "Missing required task id.", isJsonRpc, rpcIdJson);
            return;
        }

        JsonObject task = loadTask(taskId);
        if (task == null) {
            sendProtocolError(responseCallback, 404, -32001, "Task not found.", isJsonRpc, rpcIdJson);
            return;
        }

        Integer historyLength = extractHistoryLength(body);
        if (historyLength != null && historyLength >= 0 && task.has("history") && task.get("history").isJsonArray()) {
            JsonArray history = task.getAsJsonArray("history");
            JsonArray trimmedHistory = new JsonArray();
            int from = Math.max(0, history.size() - historyLength);
            for (int i = from; i < history.size(); i++) {
                trimmedHistory.add(history.get(i));
            }
            task.add("history", trimmedHistory);
        }

        sendResponse(responseCallback, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson));
    }

    private void handleTaskCancelRequest(HttpResponseReadyCallback responseCallback, String body, boolean isJsonRpc, String rpcIdJson) {
        String taskId = extractTaskOperationId(body);
        if (taskId == null || taskId.trim().isEmpty()) {
            sendProtocolError(responseCallback, 400, -32602, "Missing required task id.", isJsonRpc, rpcIdJson);
            return;
        }

        JsonObject existingTask = loadTask(taskId);
        if (existingTask == null) {
            sendProtocolError(responseCallback, 404, -32001, "Task not found.", isJsonRpc, rpcIdJson);
            return;
        }

        String currentState = extractTaskState(existingTask);
        if (isTerminalState(currentState)) {
            sendProtocolError(responseCallback, 409, -32002,
                    "Task cannot be canceled from state '" + currentState + "'.", isJsonRpc, rpcIdJson);
            return;
        }

        JsonObject canceledTask = buildTask(taskId, extractTaskContextId(existingTask, taskId),
                "canceled", "Task canceled by client request.", null, null);
        if (existingTask.has("history")) {
            canceledTask.add("history", existingTask.get("history").deepCopy());
        }
        persistTask(canceledTask);
        sendResponse(responseCallback, 200, wrapTaskResponse(canceledTask, isJsonRpc, rpcIdJson));
    }

    /**
     * Handles a streaming A2A request ({@code message/stream} or legacy {@code tasks/sendSubscribe})
     * by running the ReAct engine directly in a background thread and emitting A2A 0.3.0
     * status-update / artifact-update events.
     */
    private void handleStreamingRequest(HttpResponseReadyCallback responseCallback, String body, String taskId, String contextId,
                                        String rpcIdJson, boolean isJsonRpc) {
        String userMessage = extractUserMessage(body);
        try {
            PipedOutputStream pipedOut = new PipedOutputStream();
            PipedInputStream pipedIn = new PipedInputStream(pipedOut, 131072);

            JsonObject submittedTask = buildTask(taskId, contextId, "submitted", null, null, null);
            persistTask(submittedTask);

            try {
                pipedOut.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                pipedOut.write(buildTaskSseEvent(submittedTask, isJsonRpc, rpcIdJson).getBytes(StandardCharsets.UTF_8));
                pipedOut.flush();
            } catch (Exception ignored) {}

            Thread thread = new Thread(() -> {
                try {
                    ReactEngine engine = new ReactEngine(objectStoreManager);
                    ReactEngine.IterationCallback callback = (iteration, actionName, observation) -> {
                        if (isTaskCanceled(taskId)) {
                            return;
                        }
                        String summary = observation != null && !observation.trim().isEmpty()
                                ? observation
                                : actionName;
                        if (summary == null || summary.trim().isEmpty()) {
                            return;
                        }
                        try {
                            JsonObject workingTask = buildTask(taskId, contextId, "working", summary, null, null);
                            persistTask(workingTask);
                            pipedOut.write(buildWorkingSseEvent(taskId, contextId, summary, isJsonRpc, rpcIdJson)
                                    .getBytes(StandardCharsets.UTF_8));
                            pipedOut.flush();
                        } catch (Exception e) {
                            LOGGER.warn("Could not write SSE event for task {}: {}", taskId, e.getMessage());
                        }
                    };

                    org.mule.extension.agent.composer.internal.model.AgentResponse result =
                            engine.run(config, userMessage, contextId, 5, callback);

                    if (isTaskCanceled(taskId)) {
                        JsonObject canceledTask = getCanceledTask(taskId);
                        if (canceledTask == null) {
                            canceledTask = buildTask(taskId, contextId, "canceled", "Task canceled by client request.", null, null);
                            persistTask(canceledTask);
                        }
                        pipedOut.write(buildStatusUpdateSseEvent(taskId, contextId, "canceled",
                                extractStatusMessage(canceledTask), true, isJsonRpc, rpcIdJson).getBytes(StandardCharsets.UTF_8));
                        pipedOut.flush();
                        return;
                    }

                    if (result.isRequiresInput()) {
                        LOGGER.info("[SSE task {}] Agent requires input: {}", taskId, result.getInputRequest());
                        JsonObject inputRequiredTask = buildTask(taskId, contextId, "input-required", result.getInputRequest(), null, null);
                        persistTask(inputRequiredTask);
                        pipedOut.write(buildStatusUpdateSseEvent(taskId, contextId, "input-required",
                                result.getInputRequest(), true, isJsonRpc, rpcIdJson).getBytes(StandardCharsets.UTF_8));
                        pipedOut.flush();
                        return;
                    }

                    String uiHtml = result.getFirstUiHtml();
                    LOGGER.info("[SSE task {}] Agent completed — uiHtml present: {}", taskId, uiHtml != null);
                    JsonObject completedTask = buildTask(taskId, contextId, "completed", null, result.getResponse(), uiHtml);
                    persistTask(completedTask);
                    pipedOut.write(buildArtifactUpdateSseEvent(taskId, contextId, result.getResponse(), uiHtml, isJsonRpc, rpcIdJson)
                            .getBytes(StandardCharsets.UTF_8));
                    pipedOut.write(buildStatusUpdateSseEvent(taskId, contextId, "completed", null, true, isJsonRpc, rpcIdJson)
                            .getBytes(StandardCharsets.UTF_8));
                    pipedOut.flush();
                } catch (Exception e) {
                    LOGGER.error("Streaming agent error for task {}: {}", taskId, e.getMessage(), e);
                    try {
                        JsonObject failedTask = buildTask(taskId, contextId, "failed",
                                "Agent execution failed: " + e.getMessage(), null, null);
                        persistTask(failedTask);
                        pipedOut.write(buildStatusUpdateSseEvent(taskId, contextId, "failed",
                                "Agent execution failed: " + e.getMessage(), true, isJsonRpc, rpcIdJson)
                                .getBytes(StandardCharsets.UTF_8));
                        pipedOut.flush();
                    } catch (Exception ignored) {}
                } finally {
                    try { pipedOut.close(); } catch (Exception ignored) {}
                }
            }, "agent-sse-" + taskId);
            thread.setDaemon(true);
            thread.start();

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
            JsonObject failedTask = buildTask(taskId, contextId, "failed", "Streaming setup failed: " + e.getMessage(), null, null);
            persistTask(failedTask);
            sendResponse(responseCallback, 200, wrapTaskResponse(failedTask, isJsonRpc, rpcIdJson));
        }
    }

    /**
     * Uses the caller-supplied A2A {@code message.contextId} as the conversation key whenever present.
     * For follow-up task operations we fall back to the previously stored task context, and only derive
     * a new context from {@code taskId} when the client omitted a context entirely.
     */
    private String resolveContextId(String requestContextId, String taskId) {
        if (requestContextId != null && !requestContextId.trim().isEmpty()) {
            return requestContextId.trim();
        }
        JsonObject storedTask = loadTask(taskId);
        if (storedTask != null && storedTask.has("contextId") && !storedTask.get("contextId").isJsonNull()) {
            return storedTask.get("contextId").getAsString();
        }
        return taskId;
    }

    private JsonObject getCanceledTask(String taskId) {
        JsonObject storedTask = loadTask(taskId);
        if (storedTask != null && "canceled".equals(extractTaskState(storedTask))) {
            return storedTask;
        }
        return null;
    }

    private boolean isTaskCanceled(String taskId) {
        return getCanceledTask(taskId) != null;
    }

    private void persistTask(JsonObject task) {
        if (task == null || !task.has("id")) {
            return;
        }
        try {
            ObjectStore<Serializable> store = objectStoreManager.getObjectStore(config.getObjectStore());
            String key = taskStoreKey(task.get("id").getAsString());
            if (store.contains(key)) {
                store.remove(key);
            }
            store.store(key, task.toString());
        } catch (Exception e) {
            LOGGER.warn("Could not persist A2A task snapshot: {}", e.getMessage());
        }
    }

    private JsonObject loadTask(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return null;
        }
        try {
            ObjectStore<Serializable> store = objectStoreManager.getObjectStore(config.getObjectStore());
            String key = taskStoreKey(taskId);
            if (!store.contains(key)) {
                return null;
            }
            String payload = (String) store.retrieve(key);
            return JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Could not load A2A task '{}' from store: {}", taskId, e.getMessage());
            return null;
        }
    }

    private static String taskStoreKey(String taskId) {
        return TASK_STORE_PREFIX + taskId;
    }

    private static String stringifyPayload(TypedValue<Object> payload) {
        return (payload != null && payload.getValue() != null) ? payload.getValue().toString() : "";
    }

    private static boolean isInputRequired(JsonObject agentResponse) {
        return agentResponse != null
                && agentResponse.has("requiresInput")
                && !agentResponse.get("requiresInput").isJsonNull()
                && agentResponse.get("requiresInput").getAsBoolean();
    }

    private static String extractInputRequest(JsonObject agentResponse, String rawPayload) {
        if (agentResponse != null && agentResponse.has("inputRequest") && !agentResponse.get("inputRequest").isJsonNull()) {
            return agentResponse.get("inputRequest").getAsString();
        }
        return rawPayload != null ? rawPayload : "";
    }

    private static String extractAgentOutput(String rawPayload, JsonObject agentResponse) {
        if (agentResponse != null && agentResponse.has("response") && !agentResponse.get("response").isJsonNull()) {
            JsonElement response = agentResponse.get("response");
            return response.isJsonPrimitive() && response.getAsJsonPrimitive().isString()
                    ? response.getAsString()
                    : response.toString();
        }
        return rawPayload != null ? rawPayload : "";
    }

    /**
     * Scans {@code agentResponse.toolCalls} for the first non-empty {@code uiHtml} value.
     * Returns {@code null} when no tool in this response produced an MCP-UI HTML widget.
     */
    private static String extractFirstUiHtml(JsonObject agentResponse) {
        if (agentResponse == null || !agentResponse.has("toolCalls")) return null;
        JsonElement toolCallsEl = agentResponse.get("toolCalls");
        if (!toolCallsEl.isJsonArray()) return null;
        for (JsonElement el : toolCallsEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject record = el.getAsJsonObject();
            if (record.has("uiHtml") && !record.get("uiHtml").isJsonNull()) {
                String html = record.get("uiHtml").getAsString();
                if (html != null && !html.isEmpty()) return html;
            }
        }
        return null;
    }

    /**
     * Extracts the user message text from a JSON-RPC 2.0 or flat A2A request body.
     * Checks {@code params.message.parts[0].text} first, then {@code message.parts[0].text},
     * and falls back to the raw body string.
     */
    private static String extractUserMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    String text = firstTextPart(params.getAsJsonObject("message"));
                    if (text != null) {
                        return text;
                    }
                }
            }
            if (root.has("message") && root.get("message").isJsonObject()) {
                String text = firstTextPart(root.getAsJsonObject("message"));
                if (text != null) {
                    return text;
                }
            }
        } catch (Exception ignored) {}
        return body;
    }

    private static String firstTextPart(JsonObject message) {
        try {
            JsonArray parts = message.getAsJsonArray("parts");
            if (parts == null) {
                return null;
            }
            for (JsonElement element : parts) {
                if (element != null && element.isJsonObject()) {
                    JsonObject part = element.getAsJsonObject();
                    if (part.has("text") && !part.get("text").isJsonNull()) {
                        return part.get("text").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractMethodName(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("method") && !root.get("method").isJsonNull()) {
                return root.get("method").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isStreamingRequest(String methodName) {
        return "message/stream".equals(methodName) || "tasks/sendSubscribe".equals(methodName);
    }

    private static boolean isTaskGetRequest(String methodName) {
        return "tasks/get".equals(methodName);
    }

    private static boolean isTaskCancelRequest(String methodName) {
        return "tasks/cancel".equals(methodName);
    }

    private static boolean isMessageSendRequest(String methodName) {
        return methodName == null || "message/send".equals(methodName) || "tasks/send".equals(methodName);
    }

    private static String extractMessageTaskId(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    JsonObject message = params.getAsJsonObject("message");
                    if (message.has("taskId") && !message.get("taskId").isJsonNull()) {
                        return message.get("taskId").getAsString();
                    }
                }
            }
            if (root.has("message") && root.get("message").isJsonObject()) {
                JsonObject message = root.getAsJsonObject("message");
                if (message.has("taskId") && !message.get("taskId").isJsonNull()) {
                    return message.get("taskId").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return UUID.randomUUID().toString();
    }

    private static String extractMessageContextId(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    JsonObject message = params.getAsJsonObject("message");
                    if (message.has("contextId") && !message.get("contextId").isJsonNull()) {
                        return message.get("contextId").getAsString();
                    }
                }
            }
            if (root.has("message") && root.get("message").isJsonObject()) {
                JsonObject message = root.getAsJsonObject("message");
                if (message.has("contextId") && !message.get("contextId").isJsonNull()) {
                    return message.get("contextId").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractTaskOperationId(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("id") && !params.get("id").isJsonNull()) {
                    return params.get("id").getAsString();
                }
            }
            if (!root.has("jsonrpc") && root.has("id") && !root.get("id").isJsonNull()) {
                return root.get("id").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Integer extractHistoryLength(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("historyLength") && !params.get("historyLength").isJsonNull()) {
                    return params.get("historyLength").getAsInt();
                }
            }
            if (!root.has("jsonrpc") && root.has("historyLength") && !root.get("historyLength").isJsonNull()) {
                return root.get("historyLength").getAsInt();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extracts the JSON-RPC {@code id} field from the request body as a raw JSON string,
     * preserving its type (number vs string). Returns {@code null} if this is not a JSON-RPC request.
     */
    private static String extractRpcIdJson(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("jsonrpc") && root.has("id")) {
                return root.get("id").toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static JsonObject parseJsonObject(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static JsonObject buildTask(String taskId, String contextId, String state, String statusText, String artifactText, String artifactHtml) {
        String safeContextId = (contextId == null || contextId.trim().isEmpty()) ? taskId : contextId;
        JsonObject task = new JsonObject();
        task.addProperty("id", taskId);
        task.addProperty("contextId", safeContextId);
        task.add("status", buildStatus(state, statusText, taskId, safeContextId));
        if (artifactText != null || artifactHtml != null) {
            JsonArray artifacts = new JsonArray();
            artifacts.add(buildArtifact(taskId, artifactText, artifactHtml));
            task.add("artifacts", artifacts);
        }
        task.addProperty("kind", "task");
        return task;
    }

    private static JsonObject buildStatus(String state, String text, String taskId, String contextId) {
        JsonObject status = new JsonObject();
        status.addProperty("state", state);
        status.addProperty("timestamp", nowUtc());
        if (text != null && !text.trim().isEmpty()) {
            status.add("message", buildMessage(taskId, contextId, text));
        }
        return status;
    }

    private static JsonObject buildMessage(String taskId, String contextId, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "agent");
        JsonArray parts = new JsonArray();
        parts.add(buildTextPart(text, null));
        message.add("parts", parts);
        message.addProperty("messageId", UUID.randomUUID().toString());
        message.addProperty("taskId", taskId);
        message.addProperty("contextId", contextId);
        message.addProperty("kind", "message");
        return message;
    }

    private static JsonObject buildArtifact(String taskId, String payload, String uiHtml) {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("artifactId", taskId + "-response");
        artifact.addProperty("name", "response");
        JsonArray parts = new JsonArray();
        if (payload != null) {
            parts.add(buildPayloadPart(payload));
        }
        if (uiHtml != null && !uiHtml.isEmpty()) {
            // MCP Apps HTML widget — clients that understand text/html;profile=mcp-app
            // should render this in a sandboxed iframe instead of displaying as text.
            parts.add(buildTextPart(uiHtml, "text/html;profile=mcp-app"));
        }
        artifact.add("parts", parts);
        return artifact;
    }

    private static JsonObject buildPayloadPart(String payload) {
        String safePayload = payload != null ? payload : "";
        try {
            JsonElement parsed = JsonParser.parseString(safePayload);
            if (parsed.isJsonObject()) {
                JsonObject part = new JsonObject();
                part.addProperty("kind", "data");
                part.add("data", parsed.getAsJsonObject());
                JsonObject metadata = new JsonObject();
                metadata.addProperty("mimeType", "application/json");
                part.add("metadata", metadata);
                return part;
            }
            return buildTextPart(safePayload, "application/json");
        } catch (Exception ignored) {
            return buildTextPart(safePayload, null);
        }
    }

    private static JsonObject buildTextPart(String text, String mimeType) {
        JsonObject part = new JsonObject();
        part.addProperty("kind", "text");
        part.addProperty("text", text != null ? text : "");
        if (mimeType != null) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("mimeType", mimeType);
            part.add("metadata", metadata);
        }
        return part;
    }

    private static String buildTaskSseEvent(JsonObject task, boolean isJsonRpc, String rpcIdJson) {
        return wrapSseData(task, isJsonRpc, rpcIdJson);
    }

    private static String buildStatusUpdateSseEvent(String taskId, String contextId, String state, String text,
                                                    boolean isFinal, boolean isJsonRpc, String rpcIdJson) {
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "status-update");
        update.add("status", buildStatus(state, text, taskId, contextId));
        update.addProperty("final", isFinal);
        return wrapSseData(update, isJsonRpc, rpcIdJson);
    }

    private static String buildArtifactUpdateSseEvent(String taskId, String contextId, String payload,
                                                      String uiHtml,
                                                      boolean isJsonRpc, String rpcIdJson) {
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "artifact-update");
        update.add("artifact", buildArtifact(taskId, payload, uiHtml));
        update.addProperty("lastChunk", true);
        return wrapSseData(update, isJsonRpc, rpcIdJson);
    }

    private static String buildWorkingSseEvent(String taskId, String contextId, String summary,
                                               boolean isJsonRpc, String rpcIdJson) {
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "status-update");
        update.addProperty("final", false);
        JsonObject status = new JsonObject();
        status.addProperty("state", "working");
        status.addProperty("timestamp", nowUtc());
        JsonObject message = new JsonObject();
        message.addProperty("role", "agent");
        message.addProperty("messageId", UUID.randomUUID().toString());
        message.addProperty("taskId", taskId);
        message.addProperty("contextId", contextId);
        message.addProperty("kind", "message");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("kind", "text");
        part.addProperty("text", summary);
        parts.add(part);
        message.add("parts", parts);
        status.add("message", message);
        update.add("status", status);
        return wrapSseData(update, isJsonRpc, rpcIdJson);
    }

    private static String wrapSseData(JsonObject result, boolean isJsonRpc, String rpcIdJson) {
        return "data: " + wrapResult(result, isJsonRpc, rpcIdJson) + "\n\n";
    }

    private static String wrapTaskResponse(JsonObject task, boolean isJsonRpc, String rpcIdJson) {
        return wrapResult(task, isJsonRpc, rpcIdJson);
    }

    private static String wrapResult(JsonObject result, boolean isJsonRpc, String rpcIdJson) {
        if (!isJsonRpc) {
            return result.toString();
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", parseRpcId(rpcIdJson));
        response.add("result", result);
        return response.toString();
    }

    private static void sendProtocolError(HttpResponseReadyCallback responseCallback, int httpStatus, int code, String message,
                                          boolean isJsonRpc, String rpcIdJson) {
        JsonObject errorBody = new JsonObject();
        if (isJsonRpc) {
            errorBody.addProperty("jsonrpc", "2.0");
            errorBody.add("id", parseRpcId(rpcIdJson));
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            errorBody.add("error", error);
            sendResponse(responseCallback, 200, errorBody.toString());
            return;
        }

        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        errorBody.add("error", error);
        sendResponse(responseCallback, httpStatus, errorBody.toString());
    }

    private static JsonElement parseRpcId(String rpcIdJson) {
        if (rpcIdJson == null) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(rpcIdJson);
        } catch (Exception ignored) {
            return JsonNull.INSTANCE;
        }
    }

    private static String extractTaskState(JsonObject task) {
        try {
            return task.getAsJsonObject("status").get("state").getAsString();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String extractTaskContextId(JsonObject task, String fallback) {
        if (task != null && task.has("contextId") && !task.get("contextId").isJsonNull()) {
            return task.get("contextId").getAsString();
        }
        return fallback;
    }

    private static String extractStatusMessage(JsonObject task) {
        try {
            JsonObject status = task.getAsJsonObject("status");
            if (status != null && status.has("message") && status.get("message").isJsonObject()) {
                return firstTextPart(status.getAsJsonObject("message"));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isTerminalState(String state) {
        return "completed".equals(state)
                || "failed".equals(state)
                || "canceled".equals(state)
                || "rejected".equals(state);
    }

    private static String nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
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
