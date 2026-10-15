package org.mule.extension.agent.composer.internal.source;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
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
import org.mule.extension.agent.composer.internal.tracing.ExecutionTrace;
import org.mule.extension.agent.composer.internal.tracing.ExecutionTraceStore;
import org.mule.extension.agent.composer.internal.tracing.ExecutionCancellationRegistry;
import org.mule.extension.agent.composer.internal.tracing.TraceHtmlRenderer;
import org.mule.extension.agent.composer.internal.tracing.TraceRedactor;
import org.mule.extension.agent.composer.internal.model.AgentIterationEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final Gson GSON = new Gson();
    private static final String RESPONSE_CALLBACK_VAR = "responseCallback";
    private static final String REQUEST_VAR = "requestId";
    private static final String TASK_ID_VAR = "taskId";
    private static final String CONTEXT_ID_VAR = "contextId";
    private static final String RPC_ID_VAR = "rpcId";
    private static final String IS_JSONRPC_VAR = "isJsonRpc";
    private static final String IS_STREAMING_VAR = "isStreaming";
    private static final String A2A_VERSION_VAR = "a2aVersion";
    private static final String SSE_OUTPUT_VAR = "sseOutput";
    private static final String SSE_TERMINAL_SENT_VAR = "sseTerminalSent";
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
    private RequestHandlerManager traceReportHandlerManager;
    private RequestHandlerManager brandAssetHandlerManager;

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
        String normalizedTracePath = config.getTracesPath().startsWith("/")
                ? config.getTracesPath() : "/" + config.getTracesPath();

        // ── GET {agentPath}/.well-known/agent-card.json  +  {agentPath}/.well-known/agent.json ──
        String scopedCardPath = normalizedPath + CARD_SUFFIX;
        String legacyCardPath = normalizedPath + LEGACY_CARD_SUFFIX;
        cardHandlerManager = addCardHandler(scopedCardPath, cardBytes);
        scopedCardHandlerManager = addCardHandler(legacyCardPath, cardBytes);
        traceReportHandlerManager = addTraceReportHandler(normalizedTracePath);
        int traceSlash = normalizedTracePath.lastIndexOf('/');
        String traceBasePath = traceSlash > 0 ? normalizedTracePath.substring(0, traceSlash) : "";
        brandAssetHandlerManager = addStaticAssetHandler(
                traceBasePath + "/assets/agent-composer.svg", loadBrandLogo(), "image/svg+xml; charset=UTF-8");

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

                // A2A-Version negotiation (Section 3.6): empty/absent means 0.3; only 0.3 and 1.0 are understood.
                // The wire payload shape (status/artifact event objects) is unchanged across both versions.
                String a2aVersion = resolveA2aVersion(requestCtx.getRequest());
                if (!isSupportedA2aVersion(a2aVersion)) {
                    sendProtocolError(responseCallback, 400, -32009,
                            "A2A protocol version '" + a2aVersion + "' is not supported by this agent.", isJsonRpc, rpcIdJson);
                    return;
                }

                String methodName = extractMethodName(body);
                JsonObject requestRoot = parseJsonObject(body);
                if (requestRoot != null && requestRoot.has("jsonrpc")
                    && (methodName == null || methodName.trim().isEmpty())) {
                    sendProtocolError(responseCallback, 400, -32600,
                        "Invalid Request: missing required method.", true, rpcIdJson);
                    return;
                }

                if (isTaskGetRequest(methodName, a2aVersion)) {
                    handleTaskGetRequest(responseCallback, body, isJsonRpc, rpcIdJson, a2aVersion);
                    return;
                }

                if (isTaskCancelRequest(methodName, a2aVersion)) {
                    handleTaskCancelRequest(responseCallback, body, isJsonRpc, rpcIdJson, a2aVersion);
                    return;
                }

                if (methodName != null && !isMessageSendRequest(methodName, a2aVersion)
                        && !isStreamingRequest(methodName, a2aVersion)) {
                    sendProtocolError(responseCallback, 400, -32601,
                            "Method not found.", isJsonRpc, rpcIdJson);
                    return;
                }

                String requestValidationError = validateMessageRequest(requestRoot, a2aVersion);
                if (requestValidationError != null) {
                    sendProtocolError(responseCallback, 400, -32602,
                            requestValidationError, isJsonRpc, rpcIdJson);
                    return;
                }

                String requestContextId = extractMessageContextId(body);
                String requestedTaskId = extractMessageTaskId(body);
                JsonObject existingTask = requestedTaskId != null ? loadTask(requestedTaskId) : null;
                if (requestedTaskId != null && existingTask == null) {
                    sendProtocolError(responseCallback, 404, -32001,
                            "Task not found.", isJsonRpc, rpcIdJson);
                    return;
                }
                if (existingTask != null) {
                    String existingContextId = extractTaskContextId(existingTask, requestedTaskId);
                    if (requestContextId != null && !requestContextId.equals(existingContextId)) {
                        sendProtocolError(responseCallback, 400, -32602,
                                "message.contextId does not match the referenced task.", isJsonRpc, rpcIdJson);
                        return;
                    }
                    if (isTerminalState(extractTaskState(existingTask))) {
                        sendProtocolError(responseCallback, 400, -32004,
                                "Messages cannot be sent to a task in a terminal state.", isJsonRpc, rpcIdJson);
                        return;
                    }
                }
                String taskId = requestedTaskId != null ? requestedTaskId : UUID.randomUUID().toString();
                String contextId = resolveContextId(requestContextId, taskId);
                String flowBody = normalizeMessageForFlow(body, contextId, taskId);

                AgentListenerAttributes attributes = new AgentListenerAttributes(
                        method,
                        requestCtx.getRequest().getPath(),
                        remoteAddr,
                        headers,
                        requestId);

                // Store HTTP callback in context so @OnSuccess / @OnError can send the response
                persistTask(buildTask(taskId, contextId, "submitted", null, null, null));

                // ── Streaming: message/stream (0.3) or SendStreamingMessage (1.0) ──
                // The Mule flow remains authoritative so Execute Agent's maxIterations
                // and all other operation parameters apply identically to both transports.
                if (isStreamingRequest(methodName, a2aVersion)) {
                    handleStreamingRequest(sourceCallback, responseCallback, body, attributes,
                            requestId, taskId, contextId, rpcIdJson, isJsonRpc, a2aVersion);
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
                ctx.addVariable(A2A_VERSION_VAR, a2aVersion);

                sourceCallback.handle(
                        Result.<String, AgentListenerAttributes>builder()
                                .output(flowBody)
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

        LOGGER.info("Agent Listener attached to HTTP Listener Config '{}' | path={} card={} traces={}",
                configName, normalizedPath, scopedCardPath, normalizedTracePath);
    }

    @Override
    public void onStop() {
        StreamingEventRegistry.clear();
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
        if (traceReportHandlerManager != null) {
            traceReportHandlerManager.stop();
        }
        if (brandAssetHandlerManager != null) {
            brandAssetHandlerManager.stop();
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
        boolean isStreaming = callbackContext.<Boolean>getVariable(IS_STREAMING_VAR).orElse(false);
        String a2aVersion = callbackContext.<String>getVariable(A2A_VERSION_VAR).orElse("0.3");
        if (isStreaming) {
            StreamingEventRegistry.unregister(contextId);
            AtomicBoolean terminalSent = callbackContext.<AtomicBoolean>getVariable(SSE_TERMINAL_SENT_VAR)
                    .orElseGet(AtomicBoolean::new);
            if (terminalSent.compareAndSet(false, true)) {
                callbackContext.<PipedOutputStream>getVariable(SSE_OUTPUT_VAR).ifPresent(out ->
                        completeStreamingSuccess(out, payload, taskId, contextId, rpcIdJson, isJsonRpc, a2aVersion));
            }
            return;
        }
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject canceledTask = getCanceledTask(taskId);
            if (canceledTask != null) {
                sendResponse(cb, 200, wrapTaskResponse(canceledTask, isJsonRpc, rpcIdJson, a2aVersion));
                return;
            }

            String rawPayload = stringifyPayload(payload);
            JsonObject agentResponse = parseJsonObject(rawPayload);
            if (isInputRequired(agentResponse)) {
                String question = extractInputRequest(agentResponse, rawPayload);
                JsonObject task = buildTask(taskId, contextId, "input-required", question, null, null);
                addInputRequiredMetadata(task, agentResponse);
                addTraceMetadata(task, agentResponse);
                persistTask(task);
                sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson, a2aVersion));
                return;
            }

            String agentOutput = extractAgentOutput(rawPayload, agentResponse);
            String uiHtml = extractFirstUiHtml(agentResponse);
            JsonObject task = buildTask(taskId, contextId, "completed", null, agentOutput, uiHtml);
            addTraceMetadata(task, agentResponse);
            persistTask(task);
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson, a2aVersion));
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
        boolean isStreaming = callbackContext.<Boolean>getVariable(IS_STREAMING_VAR).orElse(false);
        String a2aVersion = callbackContext.<String>getVariable(A2A_VERSION_VAR).orElse("0.3");
        if (isStreaming) {
            StreamingEventRegistry.unregister(contextId);
            AtomicBoolean terminalSent = callbackContext.<AtomicBoolean>getVariable(SSE_TERMINAL_SENT_VAR)
                    .orElseGet(AtomicBoolean::new);
            if (!terminalSent.compareAndSet(false, true)) return;
            callbackContext.<PipedOutputStream>getVariable(SSE_OUTPUT_VAR).ifPresent(out -> {
                JsonObject task = getCanceledTask(taskId);
                if (task == null) {
                    task = buildTask(taskId, contextId, "canceled", "Flow terminated before response was sent", null, null);
                    persistTask(task);
                }
                writeAndCloseSse(out, buildStatusUpdateSseEvent(taskId, contextId, "canceled",
                        extractStatusMessage(task), true, isJsonRpc, rpcIdJson, a2aVersion), taskId);
            });
            return;
        }
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject task = getCanceledTask(taskId);
            if (task == null) {
                task = buildTask(taskId, contextId, "canceled", "Flow terminated before response was sent", null, null);
                persistTask(task);
            }
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson, a2aVersion));
        });
    }

    @OnError
    public void onError(@org.mule.sdk.api.annotation.param.Optional Error error, SourceCallbackContext callbackContext) {
        String requestId = callbackContext.<String>getVariable(REQUEST_VAR).orElse("unknown");
        String taskId = callbackContext.<String>getVariable(TASK_ID_VAR).orElse(UUID.randomUUID().toString());
        String contextId = callbackContext.<String>getVariable(CONTEXT_ID_VAR).orElse(taskId);
        String rpcIdJson = callbackContext.<String>getVariable(RPC_ID_VAR).orElse("null");
        boolean isJsonRpc = callbackContext.<Boolean>getVariable(IS_JSONRPC_VAR).orElse(false);
        String a2aVersion = callbackContext.<String>getVariable(A2A_VERSION_VAR).orElse("0.3");
        String msg = error != null ? error.getDescription() : "unknown";
        LOGGER.error("Flow error for agent request {}: {}", requestId, msg);
        boolean isStreaming = callbackContext.<Boolean>getVariable(IS_STREAMING_VAR).orElse(false);
        if (isStreaming) {
            StreamingEventRegistry.unregister(contextId);
            AtomicBoolean terminalSent = callbackContext.<AtomicBoolean>getVariable(SSE_TERMINAL_SENT_VAR)
                    .orElseGet(AtomicBoolean::new);
            if (!terminalSent.compareAndSet(false, true)) return;
            callbackContext.<PipedOutputStream>getVariable(SSE_OUTPUT_VAR).ifPresent(out -> {
                JsonObject task = getCanceledTask(taskId);
                if (task == null) {
                    task = buildTask(taskId, contextId, "failed", msg, null, null);
                    persistTask(task);
                }
                writeAndCloseSse(out, buildStatusUpdateSseEvent(taskId, contextId,
                        extractTaskState(task), extractStatusMessage(task), true,
                        isJsonRpc, rpcIdJson, a2aVersion), taskId);
            });
            return;
        }
        callbackContext.<HttpResponseReadyCallback>getVariable(RESPONSE_CALLBACK_VAR).ifPresent(cb -> {
            JsonObject task = getCanceledTask(taskId);
            if (task == null) {
                task = buildTask(taskId, contextId, "failed", msg, null, null);
                persistTask(task);
            }
            sendResponse(cb, 200, wrapTaskResponse(task, isJsonRpc, rpcIdJson, a2aVersion));
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

    private RequestHandlerManager addStaticAssetHandler(String path, byte[] bytes, String contentType) {
        return httpServer.addRequestHandler(path, (requestCtx, responseCallback) -> {
            if (!"GET".equalsIgnoreCase(requestCtx.getRequest().getMethod())) {
                sendProtocolError(responseCallback, 405, -32005, "Method Not Allowed", false, null);
                return;
            }
            sendResponseBytes(responseCallback, 200, bytes, contentType);
        });
    }

    private static byte[] loadBrandLogo() throws MuleException {
        try (InputStream stream = AgentListenerSource.class.getResourceAsStream("/icon/icon.svg")) {
            if (stream == null) {
                throw new IOException("Packaged Agent Composer logo was not found at /icon/icon.svg");
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new DefaultMuleException(new RuntimeException("Could not load the Agent Composer logo", e));
        }
    }

    private RequestHandlerManager addTraceReportHandler(String path) {
        return httpServer.addRequestHandler(path, (requestCtx, responseCallback) -> {
            String method = requestCtx.getRequest().getMethod();
            String executionId = requestCtx.getRequest().getQueryParams().get("executionId");
            if ("POST".equalsIgnoreCase(method)
                    && "cancel".equalsIgnoreCase(requestCtx.getRequest().getQueryParams().get("action"))) {
                boolean stopped = ExecutionCancellationRegistry.cancel(executionId);
                sendResponseWithContentType(responseCallback, stopped ? 202 : 409,
                        "{\"stopped\":" + stopped + ",\"executionId\":" + GSON.toJson(executionId) + "}",
                        "application/json; charset=UTF-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                sendProtocolError(responseCallback, 405, -32005, "Method Not Allowed", false, null);
                return;
            }
            String format = requestCtx.getRequest().getQueryParams().get("format");
            boolean json = "json".equalsIgnoreCase(format);
            if (executionId == null || executionId.trim().isEmpty()) {
                java.util.List<ExecutionTrace> traces = ExecutionTraceStore.list(
                        objectStoreManager, config.getObjectStore(), 50);
                if (json) {
                    sendResponseWithContentType(responseCallback, 200,
                            TraceRedactor.toRedactedJson(traces), "application/json; charset=UTF-8");
                } else {
                    sendResponseWithContentType(responseCallback, 200,
                            TraceHtmlRenderer.renderIndex(traces, path), "text/html; charset=UTF-8");
                }
                return;
            }
            ExecutionTrace trace = ExecutionTraceStore.get(executionId,
                    objectStoreManager, config.getObjectStore());
            if (trace == null) {
                if (json) {
                    sendResponseWithContentType(responseCallback, 404,
                            "{\"error\":\"Execution trace not found\"}", "application/json; charset=UTF-8");
                } else {
                    sendResponseWithContentType(responseCallback, 404,
                            TraceHtmlRenderer.render(null, path), "text/html; charset=UTF-8");
                }
                return;
            }
            if (json) {
                sendResponseWithContentType(responseCallback, 200,
                        ExecutionTraceStore.toJson(trace), "application/json; charset=UTF-8");
            } else {
                sendResponseWithContentType(responseCallback, 200,
                        TraceHtmlRenderer.render(trace, path), "text/html; charset=UTF-8");
            }
        });
    }

    private void handleTaskGetRequest(HttpResponseReadyCallback responseCallback, String body, boolean isJsonRpc,
                                      String rpcIdJson, String a2aVersion) {
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

        sendResponse(responseCallback, 200, wrapDirectTaskResponse(task, isJsonRpc, rpcIdJson, a2aVersion));
    }

    private void handleTaskCancelRequest(HttpResponseReadyCallback responseCallback, String body, boolean isJsonRpc,
                                         String rpcIdJson, String a2aVersion) {
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
        String contextId = extractTaskContextId(existingTask, taskId);
        persistTask(canceledTask);
        ExecutionCancellationRegistry.cancel(contextId);
        removePendingInputState(contextId);
        sendResponse(responseCallback, 200, wrapDirectTaskResponse(canceledTask, isJsonRpc, rpcIdJson, a2aVersion));
    }

    private void removePendingInputState(String contextId) {
        if (contextId == null || contextId.trim().isEmpty()) return;
        try {
            ObjectStore<Serializable> store = objectStoreManager.getObjectStore(config.getObjectStore());
            for (String prefix : new String[]{"tool:permission:", "mcp:elicitation:"}) {
                String key = prefix + contextId;
                if (store.contains(key)) store.remove(key);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not remove pending input state for canceled task context '{}': {}",
                    contextId, e.getMessage());
        }
    }

    /**
     * Handles a streaming A2A request ({@code message/stream} in 0.3 or
     * {@code SendStreamingMessage} in 1.0)
     * through the configured Mule flow. This keeps Execute Agent as the only owner of
     * the ReAct iteration limit while still returning an SSE transport response.
     */
    private void handleStreamingRequest(SourceCallback<String, AgentListenerAttributes> sourceCallback,
                                        HttpResponseReadyCallback responseCallback,
                                        String body, AgentListenerAttributes attributes, String requestId,
                                        String taskId, String contextId, String rpcIdJson,
                                        boolean isJsonRpc, String a2aVersion) {
        try {
            PipedOutputStream pipedOut = new PipedOutputStream();
            PipedInputStream pipedIn = new PipedInputStream(pipedOut, 131072);
            AtomicBoolean terminalSent = new AtomicBoolean(false);

            JsonObject submittedTask = buildTask(taskId, contextId, "submitted", null, null, null);
            persistTask(submittedTask);

            try {
                pipedOut.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                pipedOut.write(buildTaskSseEvent(submittedTask, isJsonRpc, rpcIdJson, a2aVersion).getBytes(StandardCharsets.UTF_8));
                pipedOut.flush();
            } catch (Exception ignored) {}

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
                    StreamingEventRegistry.unregister(contextId);
                    try { pipedOut.close(); } catch (Exception ignored) {}
                }
                @Override public void responseSendSuccessfully() { }
            });

            SourceCallbackContext ctx = sourceCallback.createContext();
            ctx.addVariable(SSE_OUTPUT_VAR, pipedOut);
            ctx.addVariable(SSE_TERMINAL_SENT_VAR, terminalSent);
            ctx.addVariable(REQUEST_VAR, requestId);
            ctx.addVariable(TASK_ID_VAR, taskId);
            ctx.addVariable(CONTEXT_ID_VAR, contextId);
            ctx.addVariable(RPC_ID_VAR, rpcIdJson != null ? rpcIdJson : "null");
            ctx.addVariable(IS_JSONRPC_VAR, isJsonRpc);
            ctx.addVariable(IS_STREAMING_VAR, true);
            ctx.addVariable(A2A_VERSION_VAR, a2aVersion);
            StreamingEventRegistry.register(contextId, event -> {
                if (!terminalSent.get()) {
                    writeIntermediateSse(pipedOut, buildIterationSseEvent(taskId, contextId,
                            event, isJsonRpc, rpcIdJson, a2aVersion), taskId);
                }
            });
            String flowBody = normalizeMessageForFlow(body, contextId, taskId);
            sourceCallback.handle(
                    Result.<String, AgentListenerAttributes>builder()
                            .output(flowBody)
                            .attributes(attributes)
                            .build(),
                    ctx);

        } catch (Exception e) {
            StreamingEventRegistry.unregister(contextId);
            LOGGER.error("Failed to set up SSE stream for task {}: {}", taskId, e.getMessage(), e);
            JsonObject failedTask = buildTask(taskId, contextId, "failed", "Streaming setup failed: " + e.getMessage(), null, null);
            persistTask(failedTask);
            sendResponse(responseCallback, 200, wrapTaskResponse(failedTask, isJsonRpc, rpcIdJson));
        }
    }

    private void completeStreamingSuccess(PipedOutputStream out, TypedValue<Object> payload,
                                          String taskId, String contextId, String rpcIdJson,
                                          boolean isJsonRpc, String a2aVersion) {
        try {
            JsonObject canceledTask = getCanceledTask(taskId);
            if (canceledTask != null) {
                writeAndCloseSse(out, buildStatusUpdateSseEvent(taskId, contextId, "canceled",
                        extractStatusMessage(canceledTask), true, isJsonRpc, rpcIdJson, a2aVersion), taskId);
                return;
            }

            String rawPayload = stringifyPayload(payload);
            JsonObject agentResponse = parseJsonObject(rawPayload);
            if (isInputRequired(agentResponse)) {
                String question = extractInputRequest(agentResponse, rawPayload);
                JsonObject task = buildTask(taskId, contextId, "input-required", question, null, null);
                addInputRequiredMetadata(task, agentResponse);
                addTraceMetadata(task, agentResponse);
                persistTask(task);
                writeAndCloseSse(out, buildInputRequiredStatusUpdateSseEvent(taskId, contextId,
                        question, agentResponse, isJsonRpc, rpcIdJson, a2aVersion), taskId);
                return;
            }

            String agentOutput = extractAgentOutput(rawPayload, agentResponse);
            String uiHtml = extractFirstUiHtml(agentResponse);
            JsonObject task = buildTask(taskId, contextId, "completed", null, agentOutput, uiHtml);
            addTraceMetadata(task, agentResponse);
            persistTask(task);
            out.write(buildArtifactUpdateSseEvent(taskId, contextId, agentOutput, uiHtml,
                    isJsonRpc, rpcIdJson, a2aVersion).getBytes(StandardCharsets.UTF_8));
            writeAndCloseSse(out, buildStatusUpdateSseEvent(taskId, contextId, "completed",
                    null, true, isJsonRpc, rpcIdJson, a2aVersion), taskId);
        } catch (Exception e) {
            LOGGER.warn("Could not complete SSE response for task {}: {}", taskId, e.getMessage(), e);
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeAndCloseSse(PipedOutputStream out, String event, String taskId) {
        try {
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            LOGGER.warn("Could not write final SSE event for task {}: {}", taskId, e.getMessage());
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeIntermediateSse(PipedOutputStream out, String event, String taskId) {
        try {
            synchronized (out) {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not write intermediate SSE event for task {}: {}", taskId, e.getMessage());
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

    private static boolean isStreamingRequest(String methodName, String a2aVersion) {
        return isA2aV1(a2aVersion)
                ? "SendStreamingMessage".equals(methodName)
                : "message/stream".equals(methodName);
    }

    private static boolean isTaskGetRequest(String methodName, String a2aVersion) {
        return isA2aV1(a2aVersion) ? "GetTask".equals(methodName) : "tasks/get".equals(methodName);
    }

    private static boolean isTaskCancelRequest(String methodName, String a2aVersion) {
        return isA2aV1(a2aVersion) ? "CancelTask".equals(methodName) : "tasks/cancel".equals(methodName);
    }

    private static boolean isMessageSendRequest(String methodName, String a2aVersion) {
        if (methodName == null) return true;
        return isA2aV1(a2aVersion) ? "SendMessage".equals(methodName) : "message/send".equals(methodName);
    }

    private static String validateMessageRequest(JsonObject root, String a2aVersion) {
        if (root == null) return "Invalid parameters: request body must be a JSON object.";
        JsonObject message = extractMessageObject(root);
        if (message == null) return "Invalid parameters: params.message is required.";
        if (!message.has("role") || message.get("role").isJsonNull()) {
            return "Invalid parameters: message.role is required.";
        }
        String role = message.get("role").getAsString();
        String expectedRole = isA2aV1(a2aVersion) ? "ROLE_USER" : "user";
        if (!expectedRole.equals(role)) {
            return "Invalid parameters: message.role must be " + expectedRole + ".";
        }
        if (!message.has("messageId") || message.get("messageId").isJsonNull()
                || message.get("messageId").getAsString().trim().isEmpty()) {
            return "Invalid parameters: message.messageId is required.";
        }
        if (!message.has("parts") || !message.get("parts").isJsonArray()
                || message.getAsJsonArray("parts").size() == 0) {
            return "Invalid parameters: message.parts must contain at least one part.";
        }
        return null;
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
        return null;
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

    private static String ensureMessageContextId(String body, String contextId) {
        if (body == null || contextId == null || contextId.trim().isEmpty()) return body;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject message = null;
            if (root.has("params") && root.get("params").isJsonObject()) {
                JsonObject params = root.getAsJsonObject("params");
                if (params.has("message") && params.get("message").isJsonObject()) {
                    message = params.getAsJsonObject("message");
                }
            }
            if (message == null && root.has("message") && root.get("message").isJsonObject()) {
                message = root.getAsJsonObject("message");
            }
            if (message != null && (!message.has("contextId") || message.get("contextId").isJsonNull()
                    || message.get("contextId").getAsString().trim().isEmpty())) {
                message.addProperty("contextId", contextId);
                return root.toString();
            }
        } catch (Exception ignored) {}
        return body;
    }

    /**
     * Keeps the public request as a standard A2A Message while adapting structured
     * DataParts to Execute Agent's string input contract. Permission decisions are
     * therefore sent as ordinary follow-up messages, not through a private RPC method.
     */
    private static String normalizeMessageForFlow(String body, String contextId, String taskId) {
        String withContext = ensureMessageContextId(body, contextId);
        try {
            JsonObject root = JsonParser.parseString(withContext).getAsJsonObject();
            JsonObject message = extractMessageObject(root);
            if (message == null) return withContext;
            if (taskId != null && (!message.has("taskId") || message.get("taskId").isJsonNull())) {
                message.addProperty("taskId", taskId);
            }
            JsonArray parts = message.has("parts") && message.get("parts").isJsonArray()
                    ? message.getAsJsonArray("parts") : null;
            if (parts == null) return root.toString();

            JsonObject structuredDecision = null;
            boolean hasText = false;
            for (JsonElement element : parts) {
                if (!element.isJsonObject()) continue;
                JsonObject part = element.getAsJsonObject();
                if (part.has("text") && !part.get("text").isJsonNull()) hasText = true;
                if (part.has("data") && part.get("data").isJsonObject()) {
                    JsonObject data = part.getAsJsonObject("data");
                    if (data.has("action") && data.has("permissionRequestId")) {
                        structuredDecision = data;
                        break;
                    }
                    if (structuredDecision == null) structuredDecision = data;
                }
            }
            if (structuredDecision != null && (!hasText
                    || (structuredDecision.has("action") && structuredDecision.has("permissionRequestId")))) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("kind", "text");
                textPart.addProperty("text", structuredDecision.toString());
                JsonArray reordered = new JsonArray();
                reordered.add(textPart);
                for (JsonElement part : parts) reordered.add(part);
                message.add("parts", reordered);
            }
            return root.toString();
        } catch (Exception ignored) {
            return withContext;
        }
    }

    private static JsonObject extractMessageObject(JsonObject root) {
        if (root == null) return null;
        if (root.has("params") && root.get("params").isJsonObject()) {
            JsonObject params = root.getAsJsonObject("params");
            if (params.has("message") && params.get("message").isJsonObject()) {
                return params.getAsJsonObject("message");
            }
        }
        return root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : null;
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

    private static void addInputRequiredMetadata(JsonObject task, JsonObject agentResponse) {
        if (task == null || agentResponse == null || !task.has("status")) return;
        JsonObject status = task.getAsJsonObject("status");
        if (!status.has("message")) return;
        JsonObject message = status.getAsJsonObject("message");
        JsonArray parts = message.getAsJsonArray("parts");
        if (parts == null) {
            parts = new JsonArray();
            message.add("parts", parts);
        }
        JsonObject data = new JsonObject();
        copyIfPresent(agentResponse, data, "inputKind");
        copyIfPresent(agentResponse, data, "elicitationServerName");
        copyIfPresent(agentResponse, data, "elicitationToolName");
        copyIfPresent(agentResponse, data, "elicitationSchema");
        copyIfPresent(agentResponse, data, "permissionRequestId");
        copyIfPresent(agentResponse, data, "permissionServerName");
        copyIfPresent(agentResponse, data, "permissionToolName");
        copyIfPresent(agentResponse, data, "permissionArguments");
        copyIfPresent(agentResponse, data, "responseSchema");
        copyIfPresent(agentResponse, data, "executionId");
        copyIfPresent(agentResponse, data, "traceReportPath");
        JsonObject part = new JsonObject();
        part.addProperty("kind", "data");
        part.add("data", data);
        parts.add(part);
    }

    private static void addTraceMetadata(JsonObject task, JsonObject agentResponse) {
        if (task == null || agentResponse == null) return;
        JsonObject metadata = task.has("metadata") && task.get("metadata").isJsonObject()
                ? task.getAsJsonObject("metadata") : new JsonObject();
        copyIfPresent(agentResponse, metadata, "executionId");
        copyIfPresent(agentResponse, metadata, "traceReportPath");
        if (metadata.size() > 0) task.add("metadata", metadata);
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String field) {
        if (source.has(field) && !source.get(field).isJsonNull()) {
            target.add(field, source.get(field).deepCopy());
        }
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
        return buildTaskSseEvent(task, isJsonRpc, rpcIdJson, "0.3");
    }

    private static String buildTaskSseEvent(JsonObject task, boolean isJsonRpc, String rpcIdJson,
                                            String a2aVersion) {
        return wrapSseData(task, "task", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String buildStatusUpdateSseEvent(String taskId, String contextId, String state, String text,
                                                    boolean isFinal, boolean isJsonRpc, String rpcIdJson) {
        return buildStatusUpdateSseEvent(taskId, contextId, state, text, isFinal,
                isJsonRpc, rpcIdJson, "0.3");
    }

    private static String buildStatusUpdateSseEvent(String taskId, String contextId, String state, String text,
                                                    boolean isFinal, boolean isJsonRpc, String rpcIdJson,
                                                    String a2aVersion) {
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "status-update");
        update.add("status", buildStatus(state, text, taskId, contextId));
        update.addProperty("final", isFinal);
        return wrapSseData(update, "statusUpdate", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String buildIterationSseEvent(String taskId, String contextId,
                                                 AgentIterationEvent event,
                                                 boolean isJsonRpc, String rpcIdJson) {
        return buildIterationSseEvent(taskId, contextId, event, isJsonRpc, rpcIdJson, "0.3");
    }

    private static String buildIterationSseEvent(String taskId, String contextId,
                                                 AgentIterationEvent event,
                                                 boolean isJsonRpc, String rpcIdJson,
                                                 String a2aVersion) {
        String text = iterationEventText(event);
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "status-update");
        JsonObject status = buildStatus("working", text, taskId, contextId);
        JsonObject data = new JsonObject();
        data.addProperty("iteration", event.getIteration());
        data.addProperty("eventKind", event.getKind().name().toLowerCase(java.util.Locale.ROOT));
        if (event.getName() != null) data.addProperty("name", event.getName());
        if (event.getServerName() != null) data.addProperty("serverName", event.getServerName());
        if (event.getArguments() != null) data.add("arguments", GSON.toJsonTree(event.getArguments()));
        if (event.getResult() != null) data.addProperty("result", event.getResult());
        JsonObject part = new JsonObject();
        part.addProperty("kind", "data");
        part.add("data", data);
        status.getAsJsonObject("message").getAsJsonArray("parts").add(part);
        update.add("status", status);
        update.addProperty("final", false);
        return wrapSseData(update, "statusUpdate", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String iterationEventText(AgentIterationEvent event) {
        switch (event.getKind()) {
            case TOOL_CALL: return "Iteration " + event.getIteration() + ": calling tool " + event.getName();
            case TOOL_RESULT: return "Iteration " + event.getIteration() + ": tool " + event.getName() + " completed";
            case SKILL_CALL: return "Iteration " + event.getIteration() + ": calling skill " + event.getName();
            case SKILL_RESULT: return "Iteration " + event.getIteration() + ": skill " + event.getName() + " completed";
            case PARALLEL_BATCH: return "Iteration " + event.getIteration() + ": running tools in parallel";
            case COMPRESSION: return "Iteration " + event.getIteration() + ": compacting context";
            case ITERATION_COMPLETE: return "Iteration " + event.getIteration() + " completed";
            case CLARIFICATION: return "Iteration " + event.getIteration() + ": clarification required";
            default: return "Iteration " + event.getIteration() + " update";
        }
    }

    private static String buildInputRequiredStatusUpdateSseEvent(
            String taskId, String contextId, String text, JsonObject agentResponse,
            boolean isJsonRpc, String rpcIdJson) {
        return buildInputRequiredStatusUpdateSseEvent(taskId, contextId, text, agentResponse,
                isJsonRpc, rpcIdJson, "0.3");
    }

    private static String buildInputRequiredStatusUpdateSseEvent(
            String taskId, String contextId, String text, JsonObject agentResponse,
            boolean isJsonRpc, String rpcIdJson, String a2aVersion) {
        JsonObject task = buildTask(taskId, contextId, "input-required", text, null, null);
        addInputRequiredMetadata(task, agentResponse);
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "status-update");
        update.add("status", task.getAsJsonObject("status"));
        update.addProperty("final", true);
        return wrapSseData(update, "statusUpdate", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String buildArtifactUpdateSseEvent(String taskId, String contextId, String payload,
                                                      String uiHtml,
                                                      boolean isJsonRpc, String rpcIdJson) {
        return buildArtifactUpdateSseEvent(taskId, contextId, payload, uiHtml,
                isJsonRpc, rpcIdJson, "0.3");
    }

    private static String buildArtifactUpdateSseEvent(String taskId, String contextId, String payload,
                                                      String uiHtml,
                                                      boolean isJsonRpc, String rpcIdJson,
                                                      String a2aVersion) {
        JsonObject update = new JsonObject();
        update.addProperty("taskId", taskId);
        update.addProperty("contextId", contextId);
        update.addProperty("kind", "artifact-update");
        update.add("artifact", buildArtifact(taskId, payload, uiHtml));
        update.addProperty("lastChunk", true);
        return wrapSseData(update, "artifactUpdate", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String wrapSseData(JsonObject result, boolean isJsonRpc, String rpcIdJson) {
        return wrapSseData(result, null, isJsonRpc, rpcIdJson, "0.3");
    }

    private static String wrapSseData(JsonObject result, String resultType, boolean isJsonRpc,
                                      String rpcIdJson, String a2aVersion) {
        return "data: " + wrapResult(result, resultType, isJsonRpc, rpcIdJson, a2aVersion) + "\n\n";
    }

    private static String wrapTaskResponse(JsonObject task, boolean isJsonRpc, String rpcIdJson) {
        return wrapTaskResponse(task, isJsonRpc, rpcIdJson, "0.3");
    }

    private static String wrapTaskResponse(JsonObject task, boolean isJsonRpc, String rpcIdJson,
                                           String a2aVersion) {
        return wrapResult(task, "task", isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String wrapDirectTaskResponse(JsonObject task, boolean isJsonRpc, String rpcIdJson,
                                                 String a2aVersion) {
        return wrapResult(task, null, isJsonRpc, rpcIdJson, a2aVersion);
    }

    private static String wrapResult(JsonObject result, boolean isJsonRpc, String rpcIdJson) {
        return wrapResult(result, null, isJsonRpc, rpcIdJson, "0.3");
    }

    private static String wrapResult(JsonObject result, String resultType, boolean isJsonRpc,
                                     String rpcIdJson, String a2aVersion) {
        JsonObject wireResult = toWireResult(result, resultType, a2aVersion);
        if (!isJsonRpc) {
            return wireResult.toString();
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", parseRpcId(rpcIdJson));
        response.add("result", wireResult);
        return response.toString();
    }

    private static JsonObject toWireResult(JsonObject result, String resultType, String a2aVersion) {
        JsonObject wire = result.deepCopy();
        if (!isA2aV1(a2aVersion)) return wire;
        normalizeV1Object(wire);
        if (resultType == null) return wire;
        JsonObject envelope = new JsonObject();
        envelope.add(resultType, wire);
        return envelope;
    }

    private static void normalizeV1Object(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) normalizeV1Object(child);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        object.remove("kind");
        object.remove("final");
        if (object.has("state") && object.get("state").isJsonPrimitive()) {
            object.addProperty("state", toV1TaskState(object.get("state").getAsString()));
        }
        if (object.has("role") && object.get("role").isJsonPrimitive()) {
            String role = object.get("role").getAsString();
            if ("user".equalsIgnoreCase(role)) object.addProperty("role", "ROLE_USER");
            if ("agent".equalsIgnoreCase(role)) object.addProperty("role", "ROLE_AGENT");
        }
        for (Map.Entry<String, JsonElement> entry : new java.util.ArrayList<>(object.entrySet())) {
            normalizeV1Object(entry.getValue());
        }
    }

    private static String toV1TaskState(String state) {
        if (state == null || state.startsWith("TASK_STATE_")) return state;
        return "TASK_STATE_" + state.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static boolean isA2aV1(String version) {
        return version != null && ("1.0".equals(version) || version.startsWith("1.0."));
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

    /** Reads the {@code A2A-Version} header, falling back to the query parameter; empty/absent means 0.3. */
    private static String resolveA2aVersion(org.mule.runtime.http.api.domain.message.request.HttpRequest request) {
        String header = request.getHeaderValue("A2A-Version");
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        String queryParam = request.getQueryParams().get("A2A-Version");
        return (queryParam != null && !queryParam.trim().isEmpty()) ? queryParam.trim() : "0.3";
    }

    /** Only Major.Minor is compared per spec \u00a73.6; patch versions (e.g. "1.0.0") are ignored. */
    private static boolean isSupportedA2aVersion(String version) {
        String majorMinor = version.length() > 3 ? version.substring(0, 3) : version;
        return "0.3".equals(majorMinor) || "1.0".equals(majorMinor);
    }

    private static String nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private static void sendResponse(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, String body) {
        sendResponseBytes(responseCallback, statusCode, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponseWithContentType(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, String body, String contentType) {
        HttpResponse response = HttpResponse.builder()
                .statusCode(statusCode)
                .addHeader("Content-Type", contentType)
                .entity(new ByteArrayHttpEntity(body.getBytes(StandardCharsets.UTF_8)))
                .build();
        responseCallback.responseReady(response, new org.mule.runtime.http.api.server.async.ResponseStatusCallback() {
            @Override public void responseSendSuccessfully() {}
            @Override public void responseSendFailure(Throwable throwable) {
                LOGGER.warn("Could not send HTTP response: {}", throwable.getMessage());
            }
        });
    }

    private static void sendResponseBytes(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, byte[] bytes) {
        sendResponseBytes(responseCallback, statusCode, bytes, "application/json");
    }

    private static void sendResponseBytes(
            org.mule.runtime.http.api.server.async.HttpResponseReadyCallback responseCallback,
            int statusCode, byte[] bytes, String contentType) {
        HttpResponse response = HttpResponse.builder()
                .statusCode(statusCode)
                .addHeader("Content-Type", contentType)
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
