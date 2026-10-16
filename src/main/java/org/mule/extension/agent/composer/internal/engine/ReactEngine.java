package org.mule.extension.agent.composer.internal.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.llm.LlmClient;
import org.mule.extension.agent.composer.internal.llm.LlmClientFactory;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.AgentIterationEvent;
import org.mule.extension.agent.composer.internal.model.AgentResponse;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.extension.agent.composer.internal.model.McpToolResult;
import org.mule.extension.agent.composer.internal.model.PendingElicitationState;
import org.mule.extension.agent.composer.internal.model.PendingToolPermissionState;
import org.mule.extension.agent.composer.internal.model.ToolCall;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.mule.extension.agent.composer.internal.tracing.ExecutionTraceContext;
import org.mule.extension.agent.composer.internal.tracing.ExecutionTraceContext.SpanScope;
import org.mule.extension.agent.composer.internal.tracing.ExecutionCancellationRegistry;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/**
 * Implements the <strong>ReAct (Reason + Act)</strong> agent loop.
 *
 * <p>Each iteration follows this sequence:
 * <ol>
 *   <li><b>Inference</b> – call the LLM with the system prompt, conversation memory,
 *       and the (filtered) set of tool definitions.</li>
 *   <li><b>Action</b> – if the model returns a {@code tool_use} response, locate the
 *       owning MCP server, execute the tool, and capture the result as the
 *       "Observation".</li>
 *   <li><b>Memory Update</b> – append the Thought, Action, and Observation to the
 *       Object Store keyed by {@code conversationId}.</li>
 *   <li><b>Termination</b> – exit the loop when the LLM produces a final text answer
 *       (no tool call) or {@code maxIterations} is reached.</li>
 * </ol>
 */
public class ReactEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactEngine.class);
    private static final Gson GSON = new Gson();
    private static final Type MSG_LIST_TYPE = new TypeToken<List<LlmMessage>>() {}.getType();
    private static final int COMPRESSION_TOKEN_THRESHOLD = 300000;
    private static final int TOOL_RESULT_CONTEXT_MAX_CHARS = 30000;
    private static final int TOOL_RESULT_CONTEXT_HEAD_CHARS = 20000;
    private static final int TOOL_RESULT_CONTEXT_TAIL_CHARS = 8000;

    /**
     * Shared pool for I/O-bound MCP calls (tool discovery, parallel tool/skill execution).
     * A dedicated pool is used instead of {@code ForkJoinPool.commonPool()} (the default for
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier)}) so blocking HTTP calls
     * never starve common-pool work elsewhere in the Mule runtime. Daemon threads avoid blocking JVM shutdown.
     */
    private static final ExecutorService MCP_IO_EXECUTOR = Executors.newCachedThreadPool(daemonThreadFactory("agent-composer-mcp-io"));
    private static final ConcurrentHashMap<String, ReentrantLock> CONVERSATION_LOCKS = new ConcurrentHashMap<>();

    /** Verbs that indicate a create/update/delete intent — responses to these are not cached. */
    private static final List<String> MUTATION_VERBS = Arrays.asList(
            "create", "add", "insert", "make", "build", "generate", "upload", "register", "new",
            "update", "edit", "modify", "change", "set", "rename", "patch", "replace", "move",
            "delete", "remove", "drop", "clear", "reset", "destroy", "wipe", "purge",
            "disable", "enable", "toggle", "send", "submit", "publish", "deploy", "push");

    /**
     * Built-in tool always added to every LLM call.
     * When the LLM calls this, the engine pauses and returns an {@code input-required}
     * state so the client can supply the missing information, then resume with the
     * same {@code conversationId}.
     */
    private static final ToolDefinition REQUEST_CLARIFICATION_TOOL;
    static {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        Map<String, Object> questionProp = new java.util.LinkedHashMap<>();
        questionProp.put("type", "string");
        questionProp.put("description", "The clarifying question to ask the user.");
        props.put("question", questionProp);
        schema.put("properties", props);
        schema.put("required", Collections.singletonList("question"));
        REQUEST_CLARIFICATION_TOOL = new ToolDefinition(
                "request_clarification",
                "Use this tool ONLY when a required tool parameter is completely unknown and cannot be inferred — "
                + "for example, a missing account ID that has no default. "
                + "Do NOT use this to ask what format to return results in, offer options, or confirm next steps. "
                + "Ask exactly one focused question. The user will reply to resume.",
                schema, null);
    }

    /** Callback invoked before and after each tool or skill execution in the ReAct loop. */
    @FunctionalInterface
    public interface IterationCallback {
        /** Receives a structured event for every loop step (tool/skill call, result, compression, etc). */
        void onEvent(AgentIterationEvent event);
    }

    private final ObjectStoreManager objectStoreManager;
    private final McpClient mcpClient;
    private final List<AgentResponse.ToolCallRecord> toolCallRecords = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<ExecutionTraceContext> traceContext = new ThreadLocal<>();

    public ReactEngine(ObjectStoreManager objectStoreManager) {
        this(objectStoreManager, new McpClient());
    }

    ReactEngine(ObjectStoreManager objectStoreManager, McpClient mcpClient) {
        this.objectStoreManager = objectStoreManager;
        this.mcpClient = mcpClient;
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ── main entry point ──────────────────────────────────────────────────────

    /** Convenience overload without a streaming callback. */
    public AgentResponse run(AgentComposerConfiguration config,
                             String userMessage,
                             String conversationId,
                             int maxIterations) throws Exception {
        return run(config, userMessage, conversationId, maxIterations, null);
    }

    /**
     * Runs the ReAct loop and returns structured response with metrics.
     * Instructions, MCP servers, and object store are sourced from {@code config}.
     *
     * @param config          LLM + agent configuration (provider, model, key, instructions, mcpServers, objectStore)
     * @param userMessage     the user's input for this turn
     * @param conversationId  key scoping this conversation in the Object Store
     * @param maxIterations   maximum Reason-Act cycles before forced exit
     * @param callback        optional callback invoked after each tool/skill execution (may be null)
     * @return AgentResponse containing final answer, metrics, and tool call history
     */
    public AgentResponse run(AgentComposerConfiguration config,
                             String userMessage,
                             String conversationId,
                             int maxIterations,
                             IterationCallback callback) throws Exception {

        ExecutionTraceContext invocationTrace = ExecutionTraceContext.start(
                objectStoreManager, config, userMessage, conversationId, maxIterations);
        ExecutionCancellationRegistry.register(invocationTrace.getExecutionId());
        ExecutionTraceContext previousTrace = traceContext.get();
        traceContext.set(invocationTrace);
        String lockKey = conversationId != null ? conversationId : "<no-conversation>";
        ReentrantLock conversationLock = CONVERSATION_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        conversationLock.lock();
        // A2A knows the task/context identifiers before the trace execution ID is
        // returned. Register the conversation key after acquiring the per-context
        // lock so a queued duplicate invocation cannot replace the active alias.
        ExecutionCancellationRegistry.register(conversationId);
        try {
            AgentResponse response = runInternal(config, userMessage, conversationId, maxIterations, callback);
            response.setExecutionId(invocationTrace.getExecutionId());
            response.setTraceReportPath(traceReportPath(config, invocationTrace.getExecutionId()));
            if (response.isRequiresInput()) {
                invocationTrace.finish(ExecutionTraceContext.STATUS_INPUT_REQUIRED, response);
            } else if (response.isComplete()) {
                invocationTrace.finish(ExecutionTraceContext.STATUS_COMPLETED, response);
            } else {
                invocationTrace.finish(ExecutionTraceContext.STATUS_FAILED, response);
            }
            if (!config.isRememberConversation() && !response.isRequiresInput()) {
                removeTransientConversation(config.getObjectStore(), conversationId);
            }
            return response;
        } catch (java.util.concurrent.CancellationException e) {
            Thread.interrupted(); // clear the flag before Mule reuses this worker thread
            invocationTrace.finish(ExecutionTraceContext.STATUS_CANCELED,
                    Collections.singletonMap("reason", e.getMessage()));
            throw e;
        } catch (Exception | Error e) {
            if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                Thread.interrupted();
                invocationTrace.finish(ExecutionTraceContext.STATUS_CANCELED,
                        Collections.singletonMap("reason", "Agent execution was stopped from the trace report"));
                throw new java.util.concurrent.CancellationException(
                        "Agent execution was stopped from the trace report");
            }
            invocationTrace.fail(e);
            throw e;
        } finally {
            conversationLock.unlock();
            if (!conversationLock.hasQueuedThreads()) CONVERSATION_LOCKS.remove(lockKey, conversationLock);
            ExecutionCancellationRegistry.unregister(invocationTrace.getExecutionId());
            ExecutionCancellationRegistry.unregister(conversationId);
            if (previousTrace != null) traceContext.set(previousTrace);
            else traceContext.remove();
        }
    }

    private AgentResponse runInternal(AgentComposerConfiguration config,
                                      String userMessage,
                                      String conversationId,
                                      int maxIterations,
                                      IterationCallback callback) throws Exception {

        String instructions = config.getInstructions();
        List<McpServerConfig> mcpServers = config.getMcpServers();
        String objectStoreName = config.getObjectStore();

        LlmClient llmClient = LlmClientFactory.create(config);

        List<ToolDefinition> discoveredMcpTools = Collections.emptyList();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            boolean requiresDiscovery = mcpServers.stream()
                    .anyMatch(server -> !McpClient.hasCachedTools(server));
            SpanScope discoverySpan = requiresDiscovery
                    ? startTraceSpan("MCP_HEALTH", "MCP health", null, null,
                            mcpServers.stream().map(McpServerConfig::getName).collect(Collectors.toList()))
                    : null;
            try {
                discoveredMcpTools = discoverMcpTools(mcpServers);
                traceSuccess(discoverySpan, discoveredMcpTools.stream().map(ToolDefinition::getName).collect(Collectors.toList()));
            } catch (Exception e) {
                traceError(discoverySpan, e);
                throw e;
            }
        }
        final List<ToolDefinition> mcpTools = discoveredMcpTools;
        traceMcpInventory(mcpServers, mcpTools);
        List<AgentSkillConfig> skills = config.getSkills() != null ? config.getSkills() : Collections.emptyList();
        List<ToolDefinition> skillTools = buildSkillTools(skills);
        boolean includeMcpToolsAsSkills = config.isIncludeMcpToolsAsSkills();

        // Build the tool list offered to the top-level LLM.
        // • Skills defined + includeMcpToolsAsSkills=true  → skill tools + raw MCP tools
        // • Skills defined + includeMcpToolsAsSkills=false → skill tools only (hides raw MCP)
        // • No skills + includeMcpToolsAsSkills=true       → raw MCP tools only
        // • No skills + includeMcpToolsAsSkills=false      → no tools (LLM reasons with text only)
        // In all cases the built-in request_clarification tool is always appended.
        List<ToolDefinition> allTools = new ArrayList<>();
        if (!skills.isEmpty()) {
            allTools.addAll(skillTools);
        }
        if (includeMcpToolsAsSkills) {
            allTools.addAll(mcpTools);
        }
        allTools.add(REQUEST_CLARIFICATION_TOOL);
        LOGGER.info("MCP tools available ({}) | includeMcpToolsAsSkills={}: {}", mcpTools.size(),
                includeMcpToolsAsSkills,
                mcpTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
        if (!skillTools.isEmpty()) {
            LOGGER.info("Skills available ({}): {}", skillTools.size(),
                    skillTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
        }

        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        toolCallRecords.clear();

        boolean rememberConversation = config.isRememberConversation();
        SpanScope conversationSpan = null;
        boolean resumedSession = false;
        if (rememberConversation) {
            Map<String, Object> conversationLookup = new LinkedHashMap<>();
            conversationLookup.put("conversationId", conversationId);
            conversationSpan = startTraceSpan("OBJECT_STORE", "Load conversation",
                    null, null, conversationLookup);
            resumedSession = hasConversationHistory(store, conversationId);
        }
        PendingToolPermissionState pendingPermission = loadPendingPermission(store, conversationId);
        PendingElicitationState pendingState = loadPendingElicitation(store, conversationId);
        if (pendingPermission == null && looksLikePermissionResponse(userMessage)) {
            throw new IllegalArgumentException("No active tool permission request matches this response");
        }
        List<LlmMessage> messages;
        int startIteration = 0;
        int toolFailureCount = 0;

        if (pendingPermission != null) {
            resumedSession = true;
            pendingPermission.setResumedSession(true);
            String approvalInput = userMessage;
            userMessage = pendingPermission.getRootUserTask();
            maxIterations = Math.max(maxIterations, pendingPermission.getMaxIterations());
            startIteration = pendingPermission.getActualIterations();
            toolFailureCount = pendingPermission.getFailureCount();
            toolCallRecords.addAll(pendingPermission.getToolCallRecords());
            Map<String, Object> resumeInput = new LinkedHashMap<>();
            resumeInput.put("inputKind", "tool_permission");
            resumeInput.put("originExecutionId", pendingPermission.getOriginExecutionId());
            resumeInput.put("queuedPermissions", pendingPermission.getQueue().size());
            SpanScope resumeSpan = startTraceSpan("TOOL_PERMISSION", "Resume tool permission",
                    null, startIteration, resumeInput);
            ExecutionTraceContext currentTrace = traceContext.get();
            if (currentTrace != null) {
                currentTrace.attribute("originExecutionId", pendingPermission.getOriginExecutionId());
                currentTrace.attribute("permissionRequestId",
                        pendingPermission.getQueue().get(0).getPermissionRequestId());
            }
            ResumeResult resume = resumePendingPermission(store, conversationId, pendingPermission,
                    approvalInput, config, skills, mcpTools, mcpServers, callback, spanId(resumeSpan));
            if (resume.response != null) {
                traceInputRequired(resumeSpan, resume.response);
                return resume.response;
            }
            traceSuccess(resumeSpan, Collections.singletonMap("status", "resumed"));
            messages = resume.messages;
            toolFailureCount = pendingPermission.getFailureCount();
        } else if (pendingState != null) {
            Map<String, Object> pendingOutput = new LinkedHashMap<>();
            pendingOutput.put("source", "pending-elicitation");
            pendingOutput.put("found", true);
            pendingOutput.put("queuedElicitations",
                    pendingState.getQueue() != null ? pendingState.getQueue().size() : 0);
            pendingOutput.put("state", pendingState);
            traceSuccess(conversationSpan, pendingOutput);
            String elicitationAnswer = userMessage;
            resumedSession = true;
            pendingState.setResumedSession(true);
            userMessage = pendingState.getRootUserTask();
            int configuredMaxIterations = maxIterations;
            int persistedMaxIterations = pendingState.getMaxIterations();
            maxIterations = reconcilePendingIterationBudget(pendingState, configuredMaxIterations);
            LOGGER.info("Resuming MCP elicitation | conversationId='{}' configuredMaxIterations={} "
                            + "persistedMaxIterations={} effectiveMaxIterations={}",
                    conversationId, configuredMaxIterations, persistedMaxIterations, maxIterations);
            startIteration = pendingState.getActualIterations();
            toolFailureCount = pendingState.getFailureCount();
            toolCallRecords.addAll(pendingState.getToolCallRecords());
            Map<String, Object> resumeInput = new LinkedHashMap<>();
            resumeInput.put("queuedElicitations", pendingState.getQueue() != null ? pendingState.getQueue().size() : 0);
            resumeInput.put("persistedIteration", pendingState.getActualIterations());
            SpanScope resumeSpan = startTraceSpan("ELICITATION_RESUME", "Resume MCP elicitation",
                    null, startIteration, resumeInput);
            ResumeResult resume;
            try {
                resume = resumePendingElicitation(store, conversationId, pendingState,
                        elicitationAnswer, config, skills, mcpTools, mcpServers, callback,
                        spanId(resumeSpan));
            } catch (RuntimeException e) {
                traceError(resumeSpan, e);
                throw e;
            }
            if (resume.response != null) {
                traceInputRequired(resumeSpan, resume.response);
                return resume.response;
            }
            traceSuccess(resumeSpan, Collections.singletonMap("status", "resumed"));
            messages = resume.messages;
            toolFailureCount = pendingState.getFailureCount();
        } else {
            String requestSignatureForCache = buildRequestSignature(config, userMessage, mcpServers, maxIterations);
            String scopedCache = buildSolvedCacheKey(conversationId, requestSignatureForCache);
            String globalCache = buildSolvedCacheKey(null, requestSignatureForCache);
            if (rememberConversation && !resumedSession) {
                AgentResponse cached = loadSolvedResponse(store, scopedCache);
                String cacheSource = "conversation-cache";
                if (cached == null) {
                    cached = loadSolvedResponse(store, globalCache);
                    cacheSource = "global-cache";
                }
                if (cached != null) {
                    cached.setCacheHit(true);
                    cached.setResumedSession(false);
                    if (cached.getSessionId() == null || cached.getSessionId().trim().isEmpty()) {
                        cached.setSessionId(conversationId);
                    }
                    if (cached.getReturnReason() == null || cached.getReturnReason().trim().isEmpty()) {
                        cached.setReturnReason(cached.isComplete() ? "completed successfully" : "incomplete cached response");
                    }
                    Map<String, Object> cacheOutput = new LinkedHashMap<>();
                    cacheOutput.put("source", cacheSource);
                    cacheOutput.put("found", true);
                    cacheOutput.put("response", cached);
                    traceSuccess(conversationSpan, cacheOutput);
                    return cached;
                }
            }
            messages = rememberConversation ? loadHistory(store, conversationId) : new ArrayList<>();
            Map<String, Object> historyOutput = new LinkedHashMap<>();
            historyOutput.put("source", rememberConversation ? "conversation-history" : "memory-disabled");
            historyOutput.put("found", !messages.isEmpty());
            historyOutput.put("messageCount", messages.size());
            historyOutput.put("messages", messages);
            traceSuccess(conversationSpan, historyOutput);
            messages.add(new LlmMessage("user", userMessage));
        }

        String requestSignature = buildRequestSignature(config, userMessage, mcpServers, maxIterations);
        String solvedCacheKeyScoped = buildSolvedCacheKey(conversationId, requestSignature);
        String solvedCacheKeyGlobal = buildSolvedCacheKey(null, requestSignature);

        String currentThought = userMessage;
        String finalAnswer = null;
        String bestFinalAnswer = null;
        int actualIterations = startIteration;
        int tokensSinceCompression = 0; // compress when accumulated LLM token usage crosses threshold

        for (int iteration = startIteration; iteration < maxIterations; iteration++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Agent execution was stopped from the trace report");
            }
            actualIterations = iteration + 1;
            SpanScope iterationSpan = startTraceSpan("ITERATION", "Iteration " + (iteration + 1),
                    null, iteration + 1, Collections.singletonMap("currentThought", currentThought));
            LOGGER.info("--- ReAct Iteration {}/{} | conversationId='{}' ---", iteration + 1, maxIterations, conversationId);
            LOGGER.debug("[Iteration {}] What am I trying to do? {}", iteration + 1, currentThought);

            LOGGER.debug("[Iteration {}] Sending request to LLM | provider={} model={}", iteration + 1, config.getProvider(), config.getModelName());
            LOGGER.debug("[Iteration {}] Instructions: {}", iteration + 1, instructions);
            LOGGER.debug("[Iteration {}] User prompt: {}", iteration + 1, userMessage);
            LOGGER.debug("[Iteration {}] Tools in use ({}): {}", iteration + 1, allTools.size(),
                    allTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
            LOGGER.info("[Iteration {}] Sending {} message(s) to LLM", iteration + 1, messages.size());

            LlmResponse response = callLlmWithSingleRetry(llmClient, instructions, messages, allTools,
                    iteration + 1, spanId(iterationSpan), "Agent reasoning");
            currentThought = response.getContent() != null ? response.getContent() : "";
            tokensSinceCompression += getTotalTokens(response);

            LOGGER.debug("[Iteration {}] Tokens used | input={} output={} total={}",
                    iteration + 1, response.getInputTokens(), response.getOutputTokens(),
                    response.getInputTokens() + response.getOutputTokens());
            LOGGER.debug("[Iteration {}] LLM response | stop_reason='{}' hasToolCall={}", iteration + 1, response.getStopReason(), response.hasToolCall());
            if (!currentThought.isEmpty()) {
                LOGGER.debug("[Iteration {}] LLM content: {}", iteration + 1, currentThought);
            }

            if (response.hasFinalAnswer()) {
                if (hasText(currentThought)) {
                    if (isBetterFinalAnswer(currentThought, bestFinalAnswer)) {
                        bestFinalAnswer = currentThought;
                    }
                    finalAnswer = bestFinalAnswer;
                    LOGGER.debug("[Iteration {}] Final answer received.", iteration + 1);
                    messages.add(new LlmMessage("assistant", currentThought));
                    saveHistory(store, conversationId, messages);

                    LOGGER.info("[Iteration {}] Stopping early: grounded final answer is available.", iteration + 1);
                    traceSuccess(iterationSpan, Collections.singletonMap("finalAnswer", currentThought));
                    emitIterationComplete(callback, iteration + 1, currentThought);
                    break;
                }

                LOGGER.warn("[Iteration {}] LLM returned no tool calls and no final text; asking for a textual answer.", iteration + 1);
                messages.add(new LlmMessage("user",
                        "Provide the final answer to the user in text using the available tool results. "
                                + "Do not call additional tools unless more information is truly required."));
                saveHistory(store, conversationId, messages);
                emitIterationComplete(callback, iteration + 1, currentThought);
                traceSuccess(iterationSpan, Collections.singletonMap("action", "request_text_answer"));
                continue;
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            LOGGER.debug("[Iteration {}] Tool call(s) requested: {}", iteration + 1,
                    toolCalls.stream().map(ToolCall::getName).collect(Collectors.joining(", ")));

            // ── Built-in: request_clarification (checked before adding to history) ─
            ToolCall clarificationCall = toolCalls.stream()
                    .filter(tc -> "request_clarification".equals(tc.getName()))
                    .findFirst().orElse(null);
            if (clarificationCall != null) {
                String question = clarificationCall.getArguments() != null
                        ? (String) clarificationCall.getArguments().getOrDefault("question", "Please provide more details.")
                        : "Please provide more details.";
                LOGGER.info("[Iteration {}] Agent requested clarification: {}", iteration + 1, question);
                // Persist conversation so user can resume with the same conversationId
                messages.add(new LlmMessage("assistant", currentThought, Collections.singletonList(clarificationCall)));
                messages.add(new LlmMessage("tool_result", "Waiting for user input.", clarificationCall.getId(), clarificationCall.getName()));
                saveHistory(store, conversationId, messages);

                AgentResponse inputRequired = new AgentResponse(
                        userMessage, question, maxIterations, actualIterations, false, conversationId);
                inputRequired.setRequiresInput(true);
                inputRequired.setInputRequest(question);
                inputRequired.setInputKind("clarification");
                inputRequired.setResumedSession(resumedSession);
                inputRequired.setReturnReason("input required");
                inputRequired.setToolCalls(toolCallRecords);
                traceInputRequired(iterationSpan, Collections.singletonMap("question", question));
                emitIterationComplete(callback, iteration + 1, question);
                return inputRequired;
            }

            // Append one assistant message carrying all tool calls for this step
            messages.add(new LlmMessage("assistant", currentThought, toolCalls));

            // ── Execute tools: parallel when > 1, sequential when == 1 ──────────
            if (toolCalls.size() > 1) {
                LOGGER.info("[Iteration {}] Executing {} tools in parallel: {}",
                        iteration + 1, toolCalls.size(),
                        toolCalls.stream().map(ToolCall::getName).collect(Collectors.joining(", ")));

                // Emit a single batch event for all tools in this parallel group.
                if (callback != null) {
                    callback.onEvent(AgentIterationEvent.parallelBatch(iteration + 1,
                            toolCalls.stream().map(ToolCall::getName).collect(Collectors.toList())));
                }

                final int iterNum = iteration + 1;
                final String capturedThought = currentThought;
                final int remainingIter = maxIterations - iteration;
                // Pass null callback: the skill sub-loop must not emit concurrently from multiple threads.
                List<CompletableFuture<ExecutionOutcome>> futures = toolCalls.stream()
                        .map(tc -> CompletableFuture.supplyAsync(withTraceContext(
                                () -> executeToolOrSkill(tc, mcpTools, mcpServers, config, skills,
                                        capturedThought, iterNum, remainingIter, null, spanId(iterationSpan)),
                                traceContext.get()), MCP_IO_EXECUTOR))
                        .collect(Collectors.toList());

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Collect results, fire response callbacks, record tool calls, append tool_result messages
                List<PendingElicitationState.Continuation> pausedContinuations = new ArrayList<>();
                List<PendingToolPermissionState.Continuation> permissionContinuations = new ArrayList<>();
                for (int t = 0; t < toolCalls.size(); t++) {
                    ToolCall tc = toolCalls.get(t);
                    ExecutionOutcome outcome;
                    try {
                        outcome = futures.get(t).get();
                    } catch (Exception e) {
                        outcome = new ExecutionOutcome("Error: " + e.getMessage(), null, true, null, null);
                    }
                    toolCallRecords.addAll(outcome.nestedRecords);

                    if (outcome.permission != null) {
                        permissionContinuations.add(outcome.permission);
                        messages.add(new LlmMessage("tool_result", "Waiting for tool permission.", tc.getId(), tc.getName()));
                        continue;
                    }

                    if (outcome.elicitation != null) {
                        pausedContinuations.add(buildContinuation(
                                outcome.elicitation, tc, iteration + 1, outcome.skillState));
                        messages.add(new LlmMessage("tool_result", "Waiting for user input (elicitation).", tc.getId(), tc.getName()));
                        continue;
                    }

                    if (outcome.failed) toolFailureCount++;
                    saveFullToolOutput(store, conversationId, tc, outcome.observation);
                    String obsForContext = compactToolObservation(tc.getName(), outcome.observation);
                    boolean isSkill = findSkillByName(tc.getName(), skills) != null;
                    if (callback != null) {
                        callback.onEvent(isSkill
                                ? AgentIterationEvent.skillResult(iteration + 1, tc.getName(), tc.getArguments(), outcome.observation)
                                : AgentIterationEvent.toolResult(iteration + 1, tc.getName(), tc.getArguments(), outcome.observation, outcome.serverName));
                    }
                    AgentResponse.ToolCallRecord parallelRecord = new AgentResponse.ToolCallRecord(
                            outcome.serverName != null ? outcome.serverName : "unknown",
                            tc.getName(), tc.getArguments(), outcome.observation, iteration + 1);
                    if (outcome.uiHtml != null) {
                        parallelRecord.setUiResourceUri(outcome.uiResourceUri);
                        parallelRecord.setUiHtml(outcome.uiHtml);
                    }
                    toolCallRecords.add(parallelRecord);
                    messages.add(new LlmMessage("tool_result", obsForContext, tc.getId(), tc.getName()));
                }

                if (!permissionContinuations.isEmpty()) {
                    saveHistory(store, conversationId, messages);
                    if (!pausedContinuations.isEmpty()) {
                        savePendingElicitation(store, conversationId, buildPendingState(userMessage,
                                maxIterations, actualIterations, toolFailureCount, resumedSession,
                                pausedContinuations));
                    }
                    PendingToolPermissionState state = buildPendingPermissionState(userMessage,
                            maxIterations, actualIterations, toolFailureCount, resumedSession,
                            messages, permissionContinuations, config.getToolPermissionWaitTimeSeconds());
                    savePendingPermission(store, conversationId, state);
                    traceInputRequired(iterationSpan, permissionTraceData(permissionContinuations.get(0)));
                    emitIterationComplete(callback, iteration + 1,
                            "Waiting for permission to execute " + permissionContinuations.get(0).getToolCall().getName());
                    return buildPermissionResponse(state, conversationId, null);
                }

                if (!pausedContinuations.isEmpty()) {
                    saveHistory(store, conversationId, messages);
                    PendingElicitationState state = buildPendingState(userMessage, maxIterations,
                            actualIterations, toolFailureCount, resumedSession, pausedContinuations);
                    savePendingElicitation(store, conversationId, state);
                    traceInputRequired(iterationSpan, Collections.singletonMap("elicitationCount", pausedContinuations.size()));
                    emitIterationComplete(callback, iteration + 1, "Waiting for user input");
                    return buildElicitationResponse(state, conversationId, null);
                }

            } else {
                // ── Single tool/skill (sequential) ────────────────────────────────
                ToolCall toolCall = toolCalls.get(0);
                boolean isSkill = findSkillByName(toolCall.getName(), skills) != null;

                if (callback != null) {
                    callback.onEvent(isSkill
                            ? AgentIterationEvent.skillCall(iteration + 1, toolCall.getName(), toolCall.getArguments())
                            : AgentIterationEvent.toolCall(iteration + 1, toolCall.getName(), toolCall.getArguments(),
                                    findMcpServerName(toolCall, mcpTools, mcpServers)));
                }

                ExecutionOutcome outcome = executeToolOrSkill(toolCall, mcpTools, mcpServers, config, skills,
                        currentThought, iteration + 1, maxIterations - iteration, callback, spanId(iterationSpan));
                toolCallRecords.addAll(outcome.nestedRecords);

                if (outcome.permission != null) {
                    messages.add(new LlmMessage("tool_result", "Waiting for tool permission.",
                            toolCall.getId(), toolCall.getName()));
                    saveHistory(store, conversationId, messages);
                    PendingToolPermissionState state = buildPendingPermissionState(userMessage,
                            maxIterations, actualIterations, toolFailureCount, resumedSession,
                            messages, Collections.singletonList(outcome.permission),
                            config.getToolPermissionWaitTimeSeconds());
                    savePendingPermission(store, conversationId, state);
                    traceInputRequired(iterationSpan, permissionTraceData(outcome.permission));
                    emitIterationComplete(callback, iteration + 1,
                            "Waiting for permission to execute " + outcome.permission.getToolCall().getName());
                    return buildPermissionResponse(state, conversationId, null);
                }

                if (outcome.elicitation != null) {
                    messages.add(new LlmMessage("tool_result", "Waiting for user input (elicitation).", toolCall.getId(), toolCall.getName()));
                    saveHistory(store, conversationId, messages);
                    PendingElicitationState.Continuation continuation = buildContinuation(
                            outcome.elicitation, toolCall, iteration + 1, outcome.skillState);
                    PendingElicitationState state = buildPendingState(userMessage, maxIterations,
                            actualIterations, toolFailureCount, resumedSession,
                            Collections.singletonList(continuation));
                    savePendingElicitation(store, conversationId, state);
                    traceInputRequired(iterationSpan, Collections.singletonMap("tool", toolCall.getName()));
                    emitIterationComplete(callback, iteration + 1, "Waiting for user input");
                    return buildElicitationResponse(state, conversationId, null);
                }

                if (outcome.failed) toolFailureCount++;

                if (callback != null) {
                    callback.onEvent(isSkill
                            ? AgentIterationEvent.skillResult(iteration + 1, toolCall.getName(), toolCall.getArguments(), outcome.observation)
                            : AgentIterationEvent.toolResult(iteration + 1, toolCall.getName(), toolCall.getArguments(), outcome.observation, outcome.serverName));
                }

                AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                        outcome.serverName != null ? outcome.serverName : "unknown",
                        toolCall.getName(), toolCall.getArguments(), outcome.observation, iteration + 1);
                if (outcome.uiHtml != null) {
                    record.setUiResourceUri(outcome.uiResourceUri);
                    record.setUiHtml(outcome.uiHtml);
                }
                toolCallRecords.add(record);

                saveFullToolOutput(store, conversationId, toolCall, outcome.observation);
                String observationForContext = compactToolObservation(toolCall.getName(), outcome.observation);
                messages.add(new LlmMessage("tool_result", observationForContext, toolCall.getId(), toolCall.getName()));
            }

            // ── Context compression ──────────────────────────────────────────────
            // When accumulated token usage crosses the threshold, summarise older messages to keep the
            // context window manageable and avoid token-limit errors.
            if (tokensSinceCompression >= COMPRESSION_TOKEN_THRESHOLD) {
                if (callback != null) {
                    callback.onEvent(AgentIterationEvent.compression(iteration + 1));
                }
                messages = compressHistory(llmClient, messages, spanId(iterationSpan));
                tokensSinceCompression = 0;
            }

            saveHistory(store, conversationId, messages);
            traceSuccess(iterationSpan, Collections.singletonMap("toolCalls", toolCalls.size()));
            emitIterationComplete(callback, iteration + 1, currentThought);
        }

        if (finalAnswer == null) {
            finalAnswer = "Maximum iterations (" + maxIterations + ") reached without a final answer.";
            LOGGER.warn("ReAct loop hit maxIterations={} for conversationId={}", maxIterations, conversationId);
            saveHistory(store, conversationId, messages);
        }

        boolean reachedMaxIterations = finalAnswer != null && finalAnswer.contains("Maximum iterations");
        boolean hasFinalAnswer = finalAnswer != null && !finalAnswer.trim().isEmpty();
        boolean completed = hasFinalAnswer && !reachedMaxIterations && toolFailureCount == 0;

        String returnReason;
        if (toolFailureCount > 0) {
            returnReason = "tool execution failures encountered";
        } else if (reachedMaxIterations) {
            returnReason = "maximum iterations reached without a final answer";
        } else if (!hasFinalAnswer) {
            returnReason = "no final answer returned";
        } else {
            returnReason = "completed successfully";
        }

        AgentResponse result = new AgentResponse(
                userMessage,
                finalAnswer,
                maxIterations,
                actualIterations,
                completed,
                conversationId
        );
        result.setToolCalls(toolCallRecords);
        result.setResumedSession(resumedSession);
        result.setFailureCount(toolFailureCount);
        result.setReturnReason(returnReason);
        result.setCacheHit(false);

        if (rememberConversation && result.isComplete() && !isMutationRequest(userMessage)) {
            String summary = summarizeSolvedConversation(userMessage, finalAnswer, actualIterations, toolCallRecords.size());
            result.setConversationSummary(summary);
            saveSolvedResponse(store, solvedCacheKeyScoped, result);
            saveSolvedResponse(store, solvedCacheKeyGlobal, result);
        } else if (result.isComplete()) {
            LOGGER.debug("Skipping solved-response cache for mutation request: '{}'", userMessage);
        }

        return result;
    }

    private void removeTransientConversation(String objectStoreName, String conversationId) {
        if (objectStoreManager == null || conversationId == null || conversationId.isBlank()) return;
        try {
            ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
            if (store.contains(conversationId)) store.remove(conversationId);
        } catch (Exception e) {
            LOGGER.warn("Could not remove transient conversation '{}': {}", conversationId, e.getMessage());
        }
    }

    private String traceReportPath(AgentComposerConfiguration config, String executionId) {
        String tracesPath = config.getTracesPath();
        if (!tracesPath.startsWith("/")) tracesPath = "/" + tracesPath;
        return tracesPath + "?executionId=" + executionId;
    }

    private SpanScope startTraceSpan(String type, String name, String parentSpanId,
                                     Integer iteration, Object input) {
        ExecutionTraceContext context = traceContext.get();
        return context != null
                ? context.startSpan(type, name, parentSpanId, iteration, input)
                : null;
    }

    private void traceMcpInventory(List<McpServerConfig> servers, List<ToolDefinition> tools) {
        ExecutionTraceContext context = traceContext.get();
        if (context == null) return;
        List<Map<String, Object>> inventory = new ArrayList<>();
        if (servers != null) {
            for (McpServerConfig server : servers) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", server.getName());
                item.put("url", server.getServerUrl());
                item.put("tools", tools.stream()
                        .filter(tool -> String.valueOf(server.getServerUrl()).equals(tool.getServerUrl()))
                        .map(tool -> {
                            Map<String, Object> toolInfo = new LinkedHashMap<>();
                            toolInfo.put("name", tool.getName());
                            toolInfo.put("description", tool.getDescription());
                            return toolInfo;
                        })
                        .collect(Collectors.toList()));
                inventory.add(item);
            }
        }
        context.attribute("mcpServers", inventory);
    }

    private <T> java.util.function.Supplier<T> withTraceContext(
            java.util.function.Supplier<T> delegate, ExecutionTraceContext captured) {
        return () -> {
            ExecutionTraceContext previous = traceContext.get();
            if (captured != null) traceContext.set(captured);
            try {
                return delegate.get();
            } finally {
                if (previous != null) traceContext.set(previous);
                else traceContext.remove();
            }
        };
    }

    private static String spanId(SpanScope scope) {
        return scope != null ? scope.getSpanId() : null;
    }

    private static void traceSuccess(SpanScope scope, Object output) {
        if (scope != null) scope.success(output);
    }

    private static void traceInputRequired(SpanScope scope, Object output) {
        if (scope != null) scope.inputRequired(output);
    }

    private static void traceError(SpanScope scope, Throwable error) {
        if (scope != null) scope.error(error);
    }

    // ── tool discovery ────────────────────────────────────────────────────────

    private List<ToolDefinition> discoverMcpTools(List<McpServerConfig> mcpServers) throws Exception {
        if (mcpServers == null || mcpServers.isEmpty()) return Collections.emptyList();

        // Independent per-server discovery calls — fan out instead of querying servers one at a time.
        List<CompletableFuture<List<ToolDefinition>>> futures = mcpServers.stream()
                .map(server -> CompletableFuture.supplyAsync(withTraceContext(() -> {
                    LOGGER.debug("Fetching MCP tools for client '{}' from {}", server.getName(), server.getServerUrl());
                    List<ToolDefinition> serverTools = mcpClient.listTools(server);
                    List<String> filter = server.getToolFilterList();
                    if (!filter.isEmpty()) {
                        serverTools = serverTools.stream()
                                .filter(t -> filter.contains(t.getName()))
                                .collect(Collectors.toList());
                    }
                    LOGGER.debug("Fetched {} tool(s) for client '{}': {}", serverTools.size(), server.getName(),
                            serverTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
                    return serverTools;
                }, traceContext.get()), MCP_IO_EXECUTOR))
                .collect(Collectors.toList());

        List<ToolDefinition> all = new ArrayList<>();
        for (CompletableFuture<List<ToolDefinition>> future : futures) {
            all.addAll(future.join());
        }
        return all;
    }

    // ── tool execution ────────────────────────────────────────────────────────

    /** Outcome of a single tool or skill invocation, shared by the sequential and parallel execution paths. */
    private static final class ExecutionOutcome {
        final String observation;
        final String serverName;
        final boolean failed;
        final String uiResourceUri;
        final String uiHtml;
        final List<AgentResponse.ToolCallRecord> nestedRecords;
        /** Non-null when the MCP server paused this call with an elicitation/create request. */
        final McpToolResult.ElicitationRequest elicitation;
        final PendingElicitationState.SkillState skillState;
        final PendingToolPermissionState.Continuation permission;

        ExecutionOutcome(String observation, String serverName, boolean failed, String uiResourceUri, String uiHtml) {
            this.observation = observation;
            this.serverName = serverName;
            this.failed = failed;
            this.uiResourceUri = uiResourceUri;
            this.uiHtml = uiHtml;
            this.elicitation = null;
            this.skillState = null;
            this.permission = null;
            this.nestedRecords = Collections.emptyList();
        }

        private ExecutionOutcome(McpToolResult.ElicitationRequest elicitation,
                                 PendingElicitationState.SkillState skillState,
                                 List<AgentResponse.ToolCallRecord> nestedRecords) {
            this.observation = null;
            this.serverName = elicitation.getServerName();
            this.failed = false;
            this.uiResourceUri = null;
            this.uiHtml = null;
            this.elicitation = elicitation;
            this.skillState = skillState;
            this.permission = null;
            this.nestedRecords = nestedRecords != null ? nestedRecords : Collections.emptyList();
        }

        private ExecutionOutcome(String observation, String serverName, boolean failed,
                                 String uiResourceUri, String uiHtml,
                                 List<AgentResponse.ToolCallRecord> nestedRecords) {
            this.observation = observation;
            this.serverName = serverName;
            this.failed = failed;
            this.uiResourceUri = uiResourceUri;
            this.uiHtml = uiHtml;
            this.elicitation = null;
            this.skillState = null;
            this.permission = null;
            this.nestedRecords = nestedRecords != null ? nestedRecords : Collections.emptyList();
        }

        static ExecutionOutcome paused(McpToolResult.ElicitationRequest elicitation,
                                       PendingElicitationState.SkillState skillState,
                                       List<AgentResponse.ToolCallRecord> nestedRecords) {
            return new ExecutionOutcome(elicitation, skillState, nestedRecords);
        }

        private ExecutionOutcome(PendingToolPermissionState.Continuation permission,
                                 List<AgentResponse.ToolCallRecord> nestedRecords) {
            this.observation = null;
            this.serverName = permission.getServerName();
            this.failed = false;
            this.uiResourceUri = null;
            this.uiHtml = null;
            this.elicitation = null;
            this.skillState = null;
            this.permission = permission;
            this.nestedRecords = nestedRecords != null ? nestedRecords : Collections.emptyList();
        }

        static ExecutionOutcome permission(PendingToolPermissionState.Continuation permission,
                                           List<AgentResponse.ToolCallRecord> nestedRecords) {
            return new ExecutionOutcome(permission, nestedRecords);
        }
    }

    /** Internal signal used to bubble an MCP elicitation pause up through a skill's sub-loop without being mistaken for a tool failure. */
    private static final class ElicitationPauseSignal extends RuntimeException {
        final McpToolResult.ElicitationRequest request;
        final PendingElicitationState.SkillState skillState;
        ElicitationPauseSignal(McpToolResult.ElicitationRequest request,
                               PendingElicitationState.SkillState skillState) {
            this.request = request;
            this.skillState = skillState;
        }
    }

    private static final class PermissionPauseSignal extends RuntimeException {
        final PendingToolPermissionState.Continuation continuation;
        PermissionPauseSignal(PendingToolPermissionState.Continuation continuation) {
            this.continuation = continuation;
        }
    }

    /**
     * Routes a tool call to either a configured skill's sub-loop or a direct MCP tool invocation.
     * Used by both the sequential (single tool call) and parallel (multiple tool calls) branches
     * of the main loop so the routing/error-handling logic exists in exactly one place.
     *
     * @param subLoopCallback callback for nested skill tool-call events; pass {@code null} when invoked
     *                        concurrently (parallel branch) to avoid concurrent callback invocations
     */
    private ExecutionOutcome executeToolOrSkill(ToolCall toolCall,
                                                List<ToolDefinition> mcpTools,
                                                List<McpServerConfig> mcpServers,
                                                AgentComposerConfiguration config,
                                                List<AgentSkillConfig> skills,
                                                String currentThought,
                                                int iteration,
                                                int remainingIterations,
                                                IterationCallback subLoopCallback,
                                                String parentSpanId) {
        AgentSkillConfig matchedSkill = findSkillByName(toolCall.getName(), skills);
        SpanScope toolSpan = startTraceSpan(matchedSkill != null ? "SKILL" : "MCP_TOOL",
                toolCall.getName(), parentSpanId, iteration, toolCall.getArguments());
        if (matchedSkill != null) {
            List<AgentResponse.ToolCallRecord> nestedRecords = new ArrayList<>();
            String taskArg = toolCall.getArguments() != null
                    ? (String) toolCall.getArguments().getOrDefault("task", currentThought)
                    : currentThought;
            String serverName = "skill:" + matchedSkill.getName();
            LOGGER.info("[Iteration {}] >>> Using skill '{}' | task: {}", iteration, matchedSkill.getName(), taskArg);
            try {
                String obs = executeSkill(matchedSkill, mcpTools, mcpServers, config, taskArg,
                        iteration, remainingIterations, subLoopCallback, nestedRecords, spanId(toolSpan));
                LOGGER.info("[Iteration {}] Skill response | name='{}' result={}", iteration, matchedSkill.getName(), obs);
                traceSuccess(toolSpan, obs);
                return new ExecutionOutcome(obs, serverName, false, null, null, nestedRecords);
            } catch (ElicitationPauseSignal pause) {
                LOGGER.info("[Iteration {}] Skill '{}' paused for user input (MCP elicitation) | requestId={}",
                        iteration, matchedSkill.getName(), pause.request.getRequestId());
                traceInputRequired(toolSpan, pause.request);
                return ExecutionOutcome.paused(pause.request, pause.skillState, nestedRecords);
            } catch (PermissionPauseSignal pause) {
                pause.continuation.setOuterToolCall(toolCall);
                LOGGER.info("[Iteration {}] Skill '{}' paused for tool permission | requestId={}",
                        iteration, matchedSkill.getName(), pause.continuation.getPermissionRequestId());
                traceInputRequired(toolSpan, permissionTraceData(pause.continuation));
                return ExecutionOutcome.permission(pause.continuation, nestedRecords);
            } catch (Exception e) {
                LOGGER.warn("[Iteration {}] Skill '{}' failed: {}", iteration, matchedSkill.getName(), e.getMessage(), e);
                traceError(toolSpan, e);
                return new ExecutionOutcome("Skill execution failed: " + e.getMessage(), serverName, true, null, null, nestedRecords);
            }
        }

        String serverName = findMcpServerName(toolCall, mcpTools, mcpServers);
        LOGGER.info("[Iteration {}] >>> Using tool '{}' (server: {}) | args: {}",
                iteration, toolCall.getName(), serverName != null ? serverName : "unknown", toolCall.getArguments());
        try {
            PendingToolPermissionState.Continuation permission = permissionContinuation(
                    toolCall, mcpTools, mcpServers, iteration, null);
            if (permission != null) {
                traceInputRequired(toolSpan, permissionTraceData(permission));
                return ExecutionOutcome.permission(permission, Collections.emptyList());
            }
            McpToolResult.CallOutcome outcome = executeTool(toolCall, mcpTools, mcpServers);
            if (outcome.isElicitationRequired()) {
                LOGGER.info("[Iteration {}] Tool '{}' paused for user input (MCP elicitation) | requestId={}",
                        iteration, toolCall.getName(), outcome.getElicitation().getRequestId());
                traceInputRequired(toolSpan, outcome.getElicitation());
                return ExecutionOutcome.paused(outcome.getElicitation(), null, Collections.emptyList());
            }
            McpToolResult result = outcome.getResult();
            LOGGER.info("[Iteration {}] Tool response | name='{}' result={}", iteration, toolCall.getName(), result.getTextContent());
            traceSuccess(toolSpan, result);
            return new ExecutionOutcome(result.getTextContent(), serverName, false,
                    result.getUiResourceUri(), result.getUiHtml());
        } catch (Exception e) {
            LOGGER.warn("[Iteration {}] Tool '{}' failed: {}", iteration, toolCall.getName(), e.getMessage(), e);
            traceError(toolSpan, e);
            return new ExecutionOutcome("Tool execution failed: " + e.getMessage(), serverName, true, null, null);
        }
    }

    private McpToolResult.CallOutcome executeTool(ToolCall toolCall, List<ToolDefinition> tools,
                               List<McpServerConfig> mcpServers) throws Exception {
        ToolDefinition toolDef = tools.stream()
                .filter(t -> t.getName().equals(toolCall.getName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool '" + toolCall.getName() + "' not found"));

        McpServerConfig server = mcpServers.stream()
                .filter(s -> s.getServerUrl().equals(toolDef.getServerUrl()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No MCP server for URL: " + toolDef.getServerUrl()));

        return mcpClient.callTool(server, toolCall.getName(), toolCall.getArguments(),
                toolDef.getUiResourceUri());
    }

    private PendingToolPermissionState.Continuation permissionContinuation(
            ToolCall call, List<ToolDefinition> tools, List<McpServerConfig> servers,
            int parentIteration, PendingToolPermissionState.SkillState skillState) {
        ToolDefinition definition = tools.stream()
                .filter(tool -> tool.getName().equals(call.getName()))
                .findFirst().orElse(null);
        if (definition == null) return null;
        McpServerConfig server = servers.stream()
                .filter(candidate -> candidate.getServerUrl().equals(definition.getServerUrl()))
                .findFirst().orElse(null);
        if (server == null || !server.requiresPermission(call.getName())) return null;
        PendingToolPermissionState.Continuation continuation = new PendingToolPermissionState.Continuation();
        continuation.setPermissionRequestId(UUID.randomUUID().toString());
        continuation.setServerName(server.getName());
        continuation.setServerUrl(server.getServerUrl());
        continuation.setToolCall(call);
        continuation.setOuterToolCall(call);
        continuation.setParentIteration(parentIteration);
        continuation.setSkillState(skillState);
        return continuation;
    }

    private Map<String, Object> permissionTraceData(PendingToolPermissionState.Continuation permission) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputKind", "tool_permission");
        data.put("permissionRequestId", permission.getPermissionRequestId());
        data.put("server", permission.getServerName());
        data.put("tool", permission.getToolCall().getName());
        data.put("arguments", permission.getToolCall().getArguments());
        return data;
    }

    private McpToolResult.CallOutcome executeApprovedTool(
            PendingToolPermissionState.Continuation permission,
            List<ToolDefinition> tools, List<McpServerConfig> servers) throws Exception {
        ToolCall call = permission.getToolCall();
        ToolDefinition definition = tools.stream()
                .filter(tool -> tool.getName().equals(call.getName())
                        && permission.getServerUrl().equals(tool.getServerUrl()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Permission-gated tool is no longer available: " + call.getName()));
        McpServerConfig server = servers.stream()
                .filter(candidate -> permission.getServerUrl().equals(candidate.getServerUrl()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Permission-gated MCP server is no longer configured: " + permission.getServerName()));
        return mcpClient.callTool(server, call.getName(), call.getArguments(), definition.getUiResourceUri());
    }

    private LlmResponse callLlmWithSingleRetry(LlmClient llmClient,
                                               String instructions,
                                               List<LlmMessage> messages,
                                               List<ToolDefinition> tools,
                                               int iterationNumber,
                                               String parentSpanId,
                                               String spanName) throws Exception {
        SpanScope llmSpan = startTraceSpan("LLM", spanName, parentSpanId, iterationNumber,
                ExecutionTraceContext.llmInput(instructions, messages, tools));
        try {
            LlmResponse response = llmClient.chat(instructions, messages, tools);
            if (llmSpan != null) llmSpan.tokens(response);
            traceSuccess(llmSpan, response);
            return response;
        } catch (Exception firstError) {
            LOGGER.warn("[Iteration {}] LLM call failed on first attempt, retrying once: {}",
                    iterationNumber, firstError.getMessage());
            if (llmSpan != null) {
                llmSpan.incrementRetry("First LLM attempt failed: " + firstError.getMessage());
            }
            try {
                LlmResponse response = llmClient.chat(instructions, messages, tools);
                if (llmSpan != null) llmSpan.tokens(response);
                traceSuccess(llmSpan, response);
                return response;
            } catch (Exception secondError) {
                LOGGER.error("[Iteration {}] LLM call failed after one retry: {}",
                        iterationNumber, secondError.getMessage(), secondError);
                traceError(llmSpan, secondError);
                throw secondError;
            }
        }
    }

    private String findMcpServerName(ToolCall toolCall, List<ToolDefinition> tools,
                                     List<McpServerConfig> mcpServers) {
        try {
            ToolDefinition toolDef = tools.stream()
                    .filter(t -> t.getName().equals(toolCall.getName()))
                    .findFirst()
                    .orElse(null);
            if (toolDef == null) return null;

            McpServerConfig server = mcpServers.stream()
                    .filter(s -> s.getServerUrl().equals(toolDef.getServerUrl()))
                    .findFirst()
                    .orElse(null);
            return server != null ? server.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── skill helpers ─────────────────────────────────────────────────────────

    /**
     * Builds synthetic {@link ToolDefinition} entries for configured skills so the LLM
     * can call them like ordinary tools. The sentinel {@code serverUrl = "skill://<name>"}
     * distinguishes them from real MCP tools.
     */
    private List<ToolDefinition> buildSkillTools(List<AgentSkillConfig> skills) {
        if (skills == null || skills.isEmpty()) return Collections.emptyList();
        Map<String, Object> schema = buildSkillInputSchema();
        return skills.stream()
                .map(s -> new ToolDefinition(s.getName(), s.getDescription(), schema, "skill://" + s.getName()))
                .collect(Collectors.toList());
    }

    /** JSON-Schema for the single {@code task} argument accepted by every skill tool. */
    private static Map<String, Object> buildSkillInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> taskProp = new LinkedHashMap<>();
        taskProp.put("type", "string");
        taskProp.put("description", "Describe what you want this skill to accomplish.");
        properties.put("task", taskProp);
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("task"));
        return schema;
    }

    /** Returns the matching {@link AgentSkillConfig} for a tool name, or {@code null}. */
    private AgentSkillConfig findSkillByName(String toolName, List<AgentSkillConfig> skills) {
        if (skills == null || skills.isEmpty() || toolName == null) return null;
        return skills.stream().filter(s -> toolName.equals(s.getName())).findFirst().orElse(null);
    }

    /**
     * Runs a focused sub-loop for a skill invocation.
     *
     * <p>The skill's {@code instructions} are used as the system prompt.
     * Only the MCP tools listed in {@link AgentSkillConfig#getToolList()} are offered
     * (empty list = all MCP tools). Tool call records are accumulated into the parent
     * {@link #toolCallRecords} list for full observability.
     */
    private String executeSkill(AgentSkillConfig skill,
                                List<ToolDefinition> allMcpTools,
                                List<McpServerConfig> mcpServers,
                                AgentComposerConfiguration config,
                                String task,
                                int parentIteration,
                                int remainingIterations,
                                IterationCallback callback,
                                List<AgentResponse.ToolCallRecord> recordSink,
                                String parentSpanId) throws Exception {
        // Skill gets MCP tools only when the config flag says so.
        List<ToolDefinition> skillMcpTools = config.isIncludeMcpToolsAsSkills() ? allMcpTools : Collections.emptyList();

        String skillInstructions = skill.getInstructions();
        LOGGER.info("[Skill '{}'] Sub-loop starting | includeMcpToolsAsSkills={} | allMcpTools={} | skillMcpTools=({}): {}",
                skill.getName(),
                config.isIncludeMcpToolsAsSkills(),
                allMcpTools.size(),
                skillMcpTools.size(),
                skillMcpTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

        LlmClient llmClient = LlmClientFactory.create(config);
        List<LlmMessage> skillMessages = new ArrayList<>();
        skillMessages.add(new LlmMessage("user", task));

        String skillAnswer = null;
        // Use remaining outer-loop budget so total iterations (outer + skill) never exceed maxIterations.
        int maxSkillIterations = Math.max(1, remainingIterations);
        // Number of consecutive text-only responses (no tool call) since the last tool call.
        // Resets to 0 whenever the LLM actually calls a tool.
        // We re-prompt up to MAX_TEXT_REPROMPTS times before accepting the text as final.
        int consecutiveTextResponses = 0;
        final int MAX_TEXT_REPROMPTS = 3;
        boolean hasTools = !skillMcpTools.isEmpty();
        int skillTokensSinceCompression = 0; // compress when accumulated LLM token usage crosses threshold

        for (int i = 0; i < maxSkillIterations; i++) {
            LOGGER.info("[Skill '{}' | Step {}/{}] Sending {} message(s) to LLM",
                    skill.getName(), i + 1, maxSkillIterations, skillMessages.size());
            LlmResponse response = callLlmOnceTraced(llmClient, skillInstructions, skillMessages,
                    skillMcpTools, parentIteration, parentSpanId,
                    "Skill " + skill.getName() + " step " + (i + 1));
                skillTokensSinceCompression += getTotalTokens(response);

            LOGGER.debug("[Skill '{}' | Step {}/{}] Tokens used | input={} output={} total={}",
                    skill.getName(), i + 1, maxSkillIterations,
                    response.getInputTokens(), response.getOutputTokens(),
                    response.getInputTokens() + response.getOutputTokens());

            if (response.hasFinalAnswer()) {
                if (hasTools && consecutiveTextResponses < MAX_TEXT_REPROMPTS) {
                    // The LLM returned text without calling a tool.
                    // It may still have more tool steps to execute (e.g. the skill
                    // instructions say "first draft the ticket, then call create_issue").
                    // Re-prompt it explicitly to invoke the tools rather than describing them.
                    consecutiveTextResponses++;
                    LOGGER.info("[Skill '{}' | Step {}/{}] LLM responded with text but no tool call " +
                            "— re-prompting to execute tools (attempt {}/{}).",
                            skill.getName(), i + 1, maxSkillIterations,
                            consecutiveTextResponses, MAX_TEXT_REPROMPTS);
                    // Keep the LLM's text so it retains context (e.g. the generated ticket
                    // title/description), but add an explicit directive to call tools next.
                    skillMessages.add(new LlmMessage("assistant",
                            response.getContent() != null ? response.getContent() : "Understood."));
                    skillMessages.add(new LlmMessage("user",
                            "You MUST now execute the next required step by calling the appropriate tool directly. "
                            + "Do not produce more text — invoke the tool now."));
                    continue;
                }
                // Exceeded re-prompt budget (or no tools) — accept text as the final answer.
                skillAnswer = response.getContent();
                LOGGER.info("[Skill '{}'] Accepted final answer at step {} "
                        + "(consecutiveTextResponses={}, hasTools={}).",
                        skill.getName(), i + 1, consecutiveTextResponses, hasTools);
                break;
            }

            // LLM requested a tool call — reset the consecutive-text counter.
            consecutiveTextResponses = 0;

            ToolCall toolCall = response.getToolCall();
            LOGGER.info("[Skill '{}' | Step {}/{}] >>> Using tool '{}' | args: {}",
                    skill.getName(), i + 1, maxSkillIterations, toolCall.getName(), toolCall.getArguments());
            skillMessages.add(new LlmMessage("assistant", response.getContent(), Collections.singletonList(toolCall)));

            // Notify before execution so SSE clients see the tool being invoked
            if (callback != null) {
                callback.onEvent(AgentIterationEvent.toolCall(parentIteration, toolCall.getName(), toolCall.getArguments(), "skill:" + skill.getName()));
            }

            String obs;
            SpanScope nestedToolSpan = startTraceSpan("MCP_TOOL", toolCall.getName(), parentSpanId,
                    parentIteration, toolCall.getArguments());
            try {
                PendingToolPermissionState.SkillState permissionSkillState = new PendingToolPermissionState.SkillState();
                permissionSkillState.setSkillName(skill.getName());
                permissionSkillState.setTask(task);
                permissionSkillState.setMessages(new ArrayList<>(skillMessages));
                permissionSkillState.setNextStep(i + 1);
                permissionSkillState.setMaxSteps(maxSkillIterations);
                permissionSkillState.setConsecutiveTextResponses(consecutiveTextResponses);
                permissionSkillState.setTokensSinceCompression(skillTokensSinceCompression);
                PendingToolPermissionState.Continuation permission = permissionContinuation(
                        toolCall, skillMcpTools, mcpServers, parentIteration, permissionSkillState);
                if (permission != null) {
                    traceInputRequired(nestedToolSpan, permissionTraceData(permission));
                    throw new PermissionPauseSignal(permission);
                }
                McpToolResult.CallOutcome toolOutcome = executeTool(toolCall, skillMcpTools, mcpServers);
                if (toolOutcome.isElicitationRequired()) {
                    PendingElicitationState.SkillState state = new PendingElicitationState.SkillState();
                    state.setSkillName(skill.getName());
                    state.setTask(task);
                    state.setMessages(new ArrayList<>(skillMessages));
                    state.setNextStep(i + 1);
                    state.setMaxSteps(maxSkillIterations);
                    state.setConsecutiveTextResponses(consecutiveTextResponses);
                    state.setTokensSinceCompression(skillTokensSinceCompression);
                    traceInputRequired(nestedToolSpan, toolOutcome.getElicitation());
                    throw new ElicitationPauseSignal(toolOutcome.getElicitation(), state);
                }
                McpToolResult skillToolResult = toolOutcome.getResult();
                obs = skillToolResult.getTextContent();
                traceSuccess(nestedToolSpan, skillToolResult);
                AgentResponse.ToolCallRecord skillRecord = new AgentResponse.ToolCallRecord(
                        skill.getName(), toolCall.getName(), toolCall.getArguments(), obs, parentIteration);
                if (skillToolResult.hasUiResource()) {
                    skillRecord.setUiResourceUri(skillToolResult.getUiResourceUri());
                    skillRecord.setUiHtml(skillToolResult.getUiHtml());
                }
                recordSink.add(skillRecord);
            } catch (ElicitationPauseSignal pause) {
                throw pause;
            } catch (PermissionPauseSignal pause) {
                throw pause;
            } catch (Exception e) {
                obs = "Tool execution failed: " + e.getMessage();
                traceError(nestedToolSpan, e);
                LOGGER.warn("[Skill '{}'] Tool '{}' failed: {}", skill.getName(), toolCall.getName(), e.getMessage());
                recordSink.add(new AgentResponse.ToolCallRecord(
                        skill.getName(), toolCall.getName(), toolCall.getArguments(), obs, parentIteration));
            }

            // Notify after execution with the actual observation
            if (callback != null) {
                callback.onEvent(AgentIterationEvent.toolResult(parentIteration, toolCall.getName(), toolCall.getArguments(), obs, "skill:" + skill.getName()));
            }

            String obsForContext = compactToolObservation(toolCall.getName(), obs);
            skillMessages.add(new LlmMessage("tool_result", obsForContext, toolCall.getId(), toolCall.getName()));

            // ── Context compression (skill sub-loop) ─────────────────────────
            if (skillTokensSinceCompression >= COMPRESSION_TOKEN_THRESHOLD) {
                if (callback != null) {
                    callback.onEvent(AgentIterationEvent.compression(parentIteration));
                }
                skillMessages = compressHistory(llmClient, skillMessages, parentSpanId);
                skillTokensSinceCompression = 0;
            }
        }

        if (skillAnswer == null) {
            skillAnswer = "Skill '" + skill.getName() + "' did not return a final answer within "
                    + maxSkillIterations + " iterations.";
        }
        return skillAnswer;
    }

    private LlmResponse callLlmOnceTraced(LlmClient llmClient,
                                          String instructions,
                                          List<LlmMessage> messages,
                                          List<ToolDefinition> tools,
                                          int iterationNumber,
                                          String parentSpanId,
                                          String spanName) throws Exception {
        SpanScope llmSpan = startTraceSpan("LLM", spanName, parentSpanId, iterationNumber,
                ExecutionTraceContext.llmInput(instructions, messages, tools));
        try {
            LlmResponse response = llmClient.chat(instructions, messages, tools);
            if (llmSpan != null) llmSpan.tokens(response);
            traceSuccess(llmSpan, response);
            return response;
        } catch (Exception e) {
            traceError(llmSpan, e);
            throw e;
        }
    }

    /**
     * Compresses the message history by summarising all but the two most recent messages.
     * Keeps the original user request plus a generated summary as context, then appends
     * the recent messages unchanged.  Helps prevent context-window overflow on long runs.
     *
     * @param llmClient the LLM client to use for summarisation
     * @param messages  current message list (modified in-place is NOT safe; returns new list)
     * @return a new, shorter message list
     */
    private List<LlmMessage> compressHistory(LlmClient llmClient, List<LlmMessage> messages,
                                             String parentSpanId) {
        // Nothing to compress if the list is tiny.
        if (messages.size() <= 4) return messages;

        int keepTail = 2; // always keep the last 2 messages (most recent thought + tool result)
        int compressUpTo = messages.size() - keepTail;
        
        while (compressUpTo > 1 && "tool_result".equals(messages.get(compressUpTo).getRole())) {
            compressUpTo--;
        }
        // If we couldn't find a safe cut point, leave the history untouched.
        if (compressUpTo <= 1) return messages;

        SpanScope compressionSpan = startTraceSpan("COMPACTION", "Compress conversation history",
                parentSpanId, null, Collections.singletonMap("messagesBefore", messages.size()));
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Summarise the following conversation history in a few sentences. "
                    + "Preserve all key facts, tool call results, and conclusions. "
                    + "Do NOT add commentary — just produce the summary.\n\n");
            for (int i = 0; i < compressUpTo; i++) {
                LlmMessage m = messages.get(i);
                String content = m.getContent() != null ? m.getContent() : "(no text content)";
                sb.append("[").append(m.getRole()).append("] ").append(content).append("\n");
            }

            LlmResponse summary = callLlmOnceTraced(llmClient,
                    "You are a concise summariser.",
                    Collections.singletonList(new LlmMessage("user", sb.toString())),
                    Collections.emptyList(), 0, spanId(compressionSpan), "Conversation summary");

            String summaryText = summary.getContent() != null ? summary.getContent() : "(summary unavailable)";
            LOGGER.info("[Context compression] Compressed {} messages → summary ({} chars)",
                    compressUpTo, summaryText.length());

            List<LlmMessage> compressed = new ArrayList<>();
            // Keep the original user request (index 0) so the LLM always knows the goal.
            compressed.add(messages.get(0));
            // Inject the summary as a prior-context user/assistant exchange.
            compressed.add(new LlmMessage("user", "Summary of conversation so far: " + summaryText));
            compressed.add(new LlmMessage("assistant", "Understood. Continuing from that point."));
            // Re-attach the recent tail.
            compressed.addAll(messages.subList(compressUpTo, messages.size()));
            traceSuccess(compressionSpan, Collections.singletonMap("messagesAfter", compressed.size()));
            return compressed;
        } catch (Exception e) {
            LOGGER.warn("[Context compression] Failed to compress history, keeping full history: {}", e.getMessage());
            traceError(compressionSpan, e);
            return messages;
        }
    }

    /**
     * Returns {@code true} if the user message appears to be a create/update/delete operation
     * that should NOT be cached as a solved response (since re-running the same request
     * would need to perform the mutation again, not return a cached result).
     */
    static boolean isMutationRequest(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) return false;
        String lower = userMessage.toLowerCase();
        String[] words = lower.split("[\\s,;:.!?\\-_/()\\[\\]{}\"']+");
        for (String word : words) {
            if (MUTATION_VERBS.contains(word)) return true;
        }
        return false;
    }

    private String buildSolvedCacheKey(String conversationId, String requestSignature) {
        String conv = conversationId == null ? "no-conversation" : conversationId;
        return "resolved::" + conv + "::" + sha256(requestSignature);
    }

    private String buildRequestSignature(AgentComposerConfiguration config,
                                         String userMessage,
                                         List<McpServerConfig> mcpServers,
                                         int maxIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.getProvider() == null ? "" : config.getProvider().name()).append("|")
          .append(config.getModelName() == null ? "" : config.getModelName().trim().toLowerCase()).append("|")
          .append(userMessage == null ? "" : userMessage.trim().toLowerCase()).append("|")
          .append(config.getInstructions() == null ? "" : config.getInstructions()).append("|")
          .append("customEndpoint=").append(config.getCustomLlmEndpointUrl() == null
                  ? "" : config.getCustomLlmEndpointUrl().trim()).append("|")
          .append("customHeaders=").append(config.getCustomLlmHeaders() == null ? "" :
                  config.getCustomLlmHeaders().stream()
                          .map(h -> String.valueOf(h.getName()) + "=" + String.valueOf(h.getValue()))
                          .collect(Collectors.joining(","))).append("|")
          .append("maxTokens=").append(config.getMaxTokens()).append("|")
          .append("temperature=").append(config.getTemperature()).append("|")
          .append("includeMcpToolsAsSkills=").append(config.isIncludeMcpToolsAsSkills()).append("|")
          // The iteration budget changes execution semantics and is reported in AgentResponse.
          // Including it prevents a response produced with the default budget (for example 5)
          // from being returned after the application is reconfigured with a larger budget.
          .append("maxIterations=").append(maxIterations).append("|");

        if (mcpServers != null && !mcpServers.isEmpty()) {
            String servers = mcpServers.stream()
                    .sorted(Comparator.comparing(s -> (s.getServerUrl() == null ? "" : s.getServerUrl())))
                    .map(s -> {
                        String name = s.getName() == null ? "" : s.getName();
                        String url = s.getServerUrl() == null ? "" : s.getServerUrl();
                        return name + "@" + url + "#" + String.join(",", s.getToolFilterList())
                                + "#permissions=" + String.join(",", s.getToolsRequiringPermissionList())
                                + "#retries=" + s.getRetryCount();
                    })
                    .collect(Collectors.joining(","));
            sb.append(servers);
        }
        if (config.getSkills() != null && !config.getSkills().isEmpty()) {
            sb.append("|skills=").append(config.getSkills().stream()
                    .map(s -> String.valueOf(s.getName()) + "@" + String.valueOf(s.getInstructions()))
                    .collect(Collectors.joining(",")));
        }
        return sb.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private AgentResponse loadSolvedResponse(ObjectStore<Serializable> store, String cacheKey) {
        try {
            if (store.contains(cacheKey)) {
                String payload = (String) store.retrieve(cacheKey);
                AgentResponse response = GSON.fromJson(payload, AgentResponse.class);
                if (response != null) {
                    return response;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read solved-response cache '{}': {}", cacheKey, e.getMessage());
        }
        return null;
    }

    private void saveSolvedResponse(ObjectStore<Serializable> store, String cacheKey, AgentResponse response) {
        try {
            if (store.contains(cacheKey)) {
                store.remove(cacheKey);
            }
            store.store(cacheKey, response.toJson());
        } catch (Exception e) {
            LOGGER.warn("Could not persist solved-response cache '{}': {}", cacheKey, e.getMessage());
        }
    }

    private String summarizeSolvedConversation(String userTask, String solution, int iterationCount, int toolCallCount) {
        return "User request: " + (userTask == null ? "" : userTask)
                + " | Solution: " + (solution == null ? "" : solution)
                + " | Iterations: " + iterationCount
                + " | Tool calls: " + toolCallCount;
    }

    private boolean isBetterFinalAnswer(String candidate, String currentBest) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return false;
        }
        if (currentBest == null || currentBest.trim().isEmpty()) {
            return true;
        }
        return scoreFinalAnswer(candidate) > scoreFinalAnswer(currentBest);
    }

    private int scoreFinalAnswer(String answer) {
        String text = answer == null ? "" : answer.trim();
        String lower = text.toLowerCase();
        int score = Math.min(text.length(), 400);
        if (text.contains("\n")) {
            score += 20;
        }
        if (lower.contains("steps") || lower.contains("resolution") || lower.contains("action")) {
            score += 20;
        }
        if (lower.contains("feel free to ask") || lower.contains("any further questions")) {
            score -= 200;
        }
        return score;
    }

    private boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private int getTotalTokens(LlmResponse response) {
        if (response == null) {
            return 0;
        }
        return Math.max(0, response.getInputTokens()) + Math.max(0, response.getOutputTokens());
    }

    private String compactToolObservation(String toolName, String observation) {
        if (observation == null) {
            return "Tool '" + toolName + "' returned no data.";
        }

        String trimmed = observation.trim();
        if (trimmed.length() <= TOOL_RESULT_CONTEXT_MAX_CHARS) {
            return trimmed;
        }

        int headLen = Math.min(TOOL_RESULT_CONTEXT_HEAD_CHARS, trimmed.length());
        int tailLen = Math.min(TOOL_RESULT_CONTEXT_TAIL_CHARS, Math.max(0, trimmed.length() - headLen));
        String head = trimmed.substring(0, headLen);
        String tail = tailLen > 0 ? trimmed.substring(trimmed.length() - tailLen) : "";

        return "Tool '" + toolName + "' returned a large payload (" + trimmed.length() + " chars). "
                + "The full result has been saved. Do NOT call this tool again — use the preview below to continue.\n"
                + "--- BEGIN PREVIEW ---\n"
                + head
                + "\n--- PREVIEW TRUNCATED (" + (trimmed.length() - headLen - tailLen) + " chars omitted) ---\n"
                + (tail.isEmpty() ? "" : "--- END PREVIEW ---\n" + tail);
    }

    private void saveFullToolOutput(ObjectStore<Serializable> store,
                                    String conversationId,
                                    ToolCall toolCall,
                                    String observation) {
        if (store == null || conversationId == null || conversationId.trim().isEmpty() || toolCall == null) {
            return;
        }
        String toolCallId = toolCall.getId() != null && !toolCall.getId().trim().isEmpty()
                ? toolCall.getId()
                : UUID.randomUUID().toString();
        String key = "tool-output::" + conversationId + "::" + toolCallId;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolCall.getName());
        payload.put("toolArguments", toolCall.getArguments());
        payload.put("response", observation);

        try {
            if (store.contains(key)) {
                store.remove(key);
            }
            store.store(key, GSON.toJson(payload));
        } catch (Exception e) {
            LOGGER.warn("Could not persist full tool output for key '{}': {}", key, e.getMessage());
        }
    }

    // ── Tool permission continuation ───────────────────────────────────────

    private static final String PERMISSION_STORE_PREFIX = "tool:permission:";

    private static final class ParsedPermissionAnswer {
        String action;
        String requestId;
        String error;
    }

    private PendingToolPermissionState buildPendingPermissionState(
            String rootUserTask, int maxIterations, int actualIterations, int failureCount,
            boolean resumedSession, List<LlmMessage> messages,
            List<PendingToolPermissionState.Continuation> queue, int expirationSeconds) {
        PendingToolPermissionState state = new PendingToolPermissionState();
        state.setRootUserTask(rootUserTask);
        state.setMaxIterations(maxIterations);
        state.setActualIterations(actualIterations);
        state.setFailureCount(failureCount);
        state.setResumedSession(resumedSession);
        state.setMessages(messages);
        state.setToolCallRecords(snapshotToolCallRecords());
        state.setQueue(queue);
        ExecutionTraceContext context = traceContext.get();
        if (context != null) state.setOriginExecutionId(context.getExecutionId());
        long createdAt = System.currentTimeMillis();
        state.setCreatedAtEpochMs(createdAt);
        state.setExpiresAtEpochMs(expirationSeconds > 0
                ? createdAt + (expirationSeconds * 1000L) : 0L);
        return state;
    }

    private ParsedPermissionAnswer parsePermissionAnswer(String raw,
                                                          PendingToolPermissionState.Continuation active) {
        ParsedPermissionAnswer answer = new ParsedPermissionAnswer();
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(
                    raw != null ? raw.trim() : "");
            if (!parsed.isJsonObject()) throw new IllegalArgumentException();
            com.google.gson.JsonObject object = parsed.getAsJsonObject();
            answer.action = object.has("action") ? object.get("action").getAsString() : null;
            answer.requestId = object.has("permissionRequestId")
                    ? object.get("permissionRequestId").getAsString() : null;
        } catch (Exception e) {
            answer.error = "Provide a JSON object with action and permissionRequestId.";
            return answer;
        }
        if (!"approve".equals(answer.action) && !"deny".equals(answer.action)) {
            answer.error = "Action must be 'approve' or 'deny'.";
        } else if (answer.requestId == null || answer.requestId.trim().isEmpty()) {
            answer.error = "permissionRequestId is required.";
        } else if (!active.getPermissionRequestId().equals(answer.requestId)) {
            answer.error = "permissionRequestId does not match the active request.";
        }
        return answer;
    }

    private boolean looksLikePermissionResponse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(raw.trim());
            if (!parsed.isJsonObject()) return false;
            com.google.gson.JsonObject object = parsed.getAsJsonObject();
            if (!object.has("permissionRequestId") || !object.has("action")) return false;
            String action = object.get("action").getAsString();
            return "approve".equals(action) || "deny".equals(action);
        } catch (Exception ignored) {
            return false;
        }
    }

    private ResumeResult resumePendingPermission(
            ObjectStore<Serializable> store, String conversationId,
            PendingToolPermissionState state, String rawAnswer,
            AgentComposerConfiguration config, List<AgentSkillConfig> skills,
            List<ToolDefinition> tools, List<McpServerConfig> servers,
            IterationCallback callback, String parentSpanId) {
        if (state.getVersion() != PendingToolPermissionState.CURRENT_VERSION
                || state.getQueue() == null || state.getQueue().isEmpty()) {
            removePendingPermission(store, conversationId);
            throw new IllegalStateException("Stored tool permission state is missing or uses an unsupported version");
        }
        boolean expired = state.isExpired(System.currentTimeMillis());
        List<LlmMessage> messages = state.getMessages() != null
                ? new ArrayList<>(state.getMessages()) : new ArrayList<>();
        PendingToolPermissionState.Continuation active = state.getQueue().get(0);
        ParsedPermissionAnswer answer = new ParsedPermissionAnswer();
        if (expired) {
            answer.action = "expired";
            answer.requestId = active.getPermissionRequestId();
        } else {
            answer = parsePermissionAnswer(rawAnswer, active);
        }
        if (answer.error != null) {
            savePendingPermission(store, conversationId, state);
            return ResumeResult.waiting(buildPermissionResponse(state, conversationId, answer.error));
        }

        ToolCall call = active.getToolCall();
        String observation;
        McpToolResult result = null;
        Map<String, Object> decisionTrace = permissionTraceData(active);
        decisionTrace.put("decision", answer.action);
        decisionTrace.put("approvalWaitMs", Math.max(0L,
                System.currentTimeMillis() - state.getCreatedAtEpochMs()));
        String decisionName = "approve".equals(answer.action) ? "Approve "
                : "deny".equals(answer.action) ? "Deny " : "Tool permission call expired: ";
        SpanScope toolSpan = startTraceSpan("TOOL_PERMISSION",
                decisionName + call.getName(),
                parentSpanId, active.getParentIteration(), decisionTrace);
        if ("expired".equals(answer.action)) {
            observation = "Tool permission call expired before customer approval. The tool was not executed. "
                    + "Continue with another approach, or stop if the task cannot be completed safely without this tool.";
            Map<String, Object> expiredResult = new LinkedHashMap<>();
            expiredResult.put("decision", "expired");
            expiredResult.put("permissionRequestId", active.getPermissionRequestId());
            traceSuccess(toolSpan, expiredResult);
        } else if ("deny".equals(answer.action)) {
            observation = "Tool call denied by user.";
            traceSuccess(toolSpan, Collections.singletonMap("decision", "denied"));
        } else {
            try {
                McpToolResult.CallOutcome outcome = executeApprovedTool(active, tools, servers);
                if (outcome.isElicitationRequired()) {
                    PendingElicitationState.SkillState elicitationSkill = active.isSkill()
                            ? toElicitationSkillState(active.getSkillState()) : null;
                    PendingElicitationState.Continuation continuation = buildContinuation(
                            outcome.getElicitation(), active.getOuterToolCall(),
                            active.getParentIteration(), elicitationSkill);
                    PendingElicitationState elicitationState = buildPendingState(
                            state.getRootUserTask(), state.getMaxIterations(), state.getActualIterations(),
                            state.getFailureCount(), true, Collections.singletonList(continuation));
                    saveHistory(store, conversationId, messages);
                    savePendingElicitation(store, conversationId, elicitationState);
                    removePendingPermission(store, conversationId);
                    traceInputRequired(toolSpan, outcome.getElicitation());
                    return ResumeResult.waiting(buildElicitationResponse(elicitationState, conversationId, null));
                }
                result = outcome.getResult();
                observation = result.getTextContent();
                traceSuccess(toolSpan, result);
            } catch (Exception e) {
                observation = "Tool execution failed after approval: " + e.getMessage();
                state.setFailureCount(state.getFailureCount() + 1);
                traceError(toolSpan, e);
            }
        }

        if (active.isSkill()) {
            PendingToolPermissionState.SkillState permissionSkill = active.getSkillState();
            AgentResponse.ToolCallRecord nestedRecord = new AgentResponse.ToolCallRecord(
                    permissionSkill.getSkillName(), call.getName(), call.getArguments(),
                    observation, active.getParentIteration());
            if (result != null && result.hasUiResource()) {
                nestedRecord.setUiResourceUri(result.getUiResourceUri());
                nestedRecord.setUiHtml(result.getUiHtml());
            }
            toolCallRecords.add(nestedRecord);
            permissionSkill.getMessages().add(new LlmMessage("tool_result",
                    compactToolObservation(call.getName(), observation), call.getId(), call.getName()));
            if (callback != null) callback.onEvent(AgentIterationEvent.toolResult(
                    active.getParentIteration(), call.getName(), call.getArguments(), observation,
                    "skill:" + permissionSkill.getSkillName()));
            SpanScope skillSpan = startTraceSpan("SKILL", "Resume skill " + permissionSkill.getSkillName(),
                    parentSpanId, active.getParentIteration(),
                    Collections.singletonMap("nextStep", permissionSkill.getNextStep()));
            try {
                SkillResumeResult resumed = continueSkill(toElicitationSkillState(permissionSkill),
                        tools, servers, config, active.getParentIteration(), callback, spanId(skillSpan));
                if (resumed.permission != null) {
                    resumed.permission.setOuterToolCall(active.getOuterToolCall());
                    state.getQueue().set(0, resumed.permission);
                    state.setMessages(messages);
                    state.setToolCallRecords(snapshotToolCallRecords());
                    savePendingPermission(store, conversationId, state);
                    traceInputRequired(skillSpan, permissionTraceData(resumed.permission));
                    return ResumeResult.waiting(buildPermissionResponse(state, conversationId, null));
                }
                if (resumed.elicitation != null) {
                    PendingElicitationState.Continuation continuation = buildContinuation(
                            resumed.elicitation, active.getOuterToolCall(), active.getParentIteration(), resumed.state);
                    PendingElicitationState elicitationState = buildPendingState(
                            state.getRootUserTask(), state.getMaxIterations(), state.getActualIterations(),
                            state.getFailureCount(), true, Collections.singletonList(continuation));
                    savePendingElicitation(store, conversationId, elicitationState);
                    removePendingPermission(store, conversationId);
                    traceInputRequired(skillSpan, resumed.elicitation);
                    return ResumeResult.waiting(buildElicitationResponse(elicitationState, conversationId, null));
                }
                observation = resumed.answer;
                replaceToolResult(messages, active.getOuterToolCall(), observation);
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        "skill:" + permissionSkill.getSkillName(), active.getOuterToolCall().getName(),
                        active.getOuterToolCall().getArguments(), observation, active.getParentIteration()));
                traceSuccess(skillSpan, observation);
            } catch (Exception e) {
                observation = "Skill execution failed after tool permission: " + e.getMessage();
                state.setFailureCount(state.getFailureCount() + 1);
                replaceToolResult(messages, active.getOuterToolCall(), observation);
                traceError(skillSpan, e);
            }
        } else {
            replaceToolResult(messages, active.getOuterToolCall(), observation);
            AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                    active.getServerName(), call.getName(), call.getArguments(),
                    observation, active.getParentIteration());
            if (result != null && result.hasUiResource()) {
                record.setUiResourceUri(result.getUiResourceUri());
                record.setUiHtml(result.getUiHtml());
            }
            toolCallRecords.add(record);
            saveFullToolOutput(store, conversationId, call, observation);
            if (callback != null) callback.onEvent(AgentIterationEvent.toolResult(
                    active.getParentIteration(), call.getName(), call.getArguments(), observation,
                    active.getServerName()));
        }

        state.getQueue().remove(0);
        state.setMessages(messages);
        state.setToolCallRecords(snapshotToolCallRecords());
        saveHistory(store, conversationId, messages);
        if (!state.getQueue().isEmpty()) {
            savePendingPermission(store, conversationId, state);
            return ResumeResult.waiting(buildPermissionResponse(state, conversationId, null));
        }
        removePendingPermission(store, conversationId);
        PendingElicitationState pendingElicitation = loadPendingElicitation(store, conversationId);
        if (pendingElicitation != null) {
            return ResumeResult.waiting(buildElicitationResponse(pendingElicitation, conversationId, null));
        }
        return ResumeResult.completed(messages);
    }

    private PendingElicitationState.SkillState toElicitationSkillState(
            PendingToolPermissionState.SkillState source) {
        PendingElicitationState.SkillState target = new PendingElicitationState.SkillState();
        target.setSkillName(source.getSkillName());
        target.setTask(source.getTask());
        target.setMessages(source.getMessages());
        target.setNextStep(source.getNextStep());
        target.setMaxSteps(source.getMaxSteps());
        target.setConsecutiveTextResponses(source.getConsecutiveTextResponses());
        target.setTokensSinceCompression(source.getTokensSinceCompression());
        return target;
    }

    private AgentResponse buildPermissionResponse(PendingToolPermissionState state,
                                                   String conversationId, String validationError) {
        PendingToolPermissionState.Continuation active = state.getQueue().get(0);
        ToolCall call = active.getToolCall();
        String prompt = "Approve tool '" + call.getName() + "' on MCP server '"
                + active.getServerName() + "'?";
        if (validationError != null) prompt = validationError + " " + prompt;
        AgentResponse response = new AgentResponse(state.getRootUserTask(), prompt,
                state.getMaxIterations(), state.getActualIterations(), false, conversationId);
        response.setRequiresInput(true);
        response.setInputKind("tool_permission");
        response.setInputRequest(prompt);
        response.setPermissionRequestId(active.getPermissionRequestId());
        response.setPermissionServerName(active.getServerName());
        response.setPermissionToolName(call.getName());
        response.setPermissionArguments(call.getArguments());
        response.setPermissionExpiresAtEpochMs(state.getExpiresAtEpochMs() > 0
                ? state.getExpiresAtEpochMs() : null);
        response.setResponseSchema(toolPermissionResponseSchema());
        response.setResumedSession(state.isResumedSession());
        response.setFailureCount(state.getFailureCount());
        response.setReturnReason("input required");
        response.setToolCalls(snapshotToolCallRecords());
        return response;
    }

    private static Map<String, Object> toolPermissionResponseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", java.util.Arrays.asList(
                "inputKind", "permissionRequestId", "action"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("inputKind", Collections.singletonMap("const", "tool_permission"));
        properties.put("permissionRequestId", Collections.singletonMap("type", "string"));
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", java.util.Arrays.asList("approve", "deny"));
        properties.put("action", action);
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private void emitIterationComplete(IterationCallback callback, int iteration, String result) {
        if (callback != null) {
            callback.onEvent(AgentIterationEvent.iterationComplete(iteration, result));
        }
    }

    private void savePendingPermission(ObjectStore<Serializable> store, String conversationId,
                                       PendingToolPermissionState state) {
        String key = PERMISSION_STORE_PREFIX + conversationId;
        try {
            if (store.contains(key)) store.remove(key);
            store.store(key, GSON.toJson(state));
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist tool permission state for '" + conversationId + "'", e);
        }
    }

    private PendingToolPermissionState loadPendingPermission(ObjectStore<Serializable> store,
                                                              String conversationId) {
        String key = PERMISSION_STORE_PREFIX + conversationId;
        try {
            if (!store.contains(key)) return null;
            return GSON.fromJson((String) store.retrieve(key), PendingToolPermissionState.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load tool permission state for '" + conversationId + "'", e);
        }
    }

    private void removePendingPermission(ObjectStore<Serializable> store, String conversationId) {
        String key = PERMISSION_STORE_PREFIX + conversationId;
        try {
            if (store.contains(key)) store.remove(key);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove tool permission state for '" + conversationId + "'", e);
        }
    }

    // ── MCP elicitation continuation ────────────────────────────────────────

    private static final String ELICITATION_STORE_PREFIX = "mcp:elicitation:";

    private static final class ResumeResult {
        final AgentResponse response;
        final List<LlmMessage> messages;

        private ResumeResult(AgentResponse response, List<LlmMessage> messages) {
            this.response = response;
            this.messages = messages;
        }

        static ResumeResult waiting(AgentResponse response) { return new ResumeResult(response, null); }
        static ResumeResult completed(List<LlmMessage> messages) { return new ResumeResult(null, messages); }
    }

    private static final class ParsedElicitationAnswer {
        String action;
        Map<String, Object> content;
        String error;
    }

    private static final class SkillResumeResult {
        String answer;
        McpToolResult.ElicitationRequest elicitation;
        PendingElicitationState.SkillState state;
        PendingToolPermissionState.Continuation permission;
    }

    private PendingElicitationState.Continuation buildContinuation(
            McpToolResult.ElicitationRequest request, ToolCall outerToolCall, int parentIteration,
            PendingElicitationState.SkillState skillState) {
        PendingElicitationState.Continuation continuation = new PendingElicitationState.Continuation();
        continuation.setRequest(request);
        continuation.setOuterToolCall(outerToolCall);
        continuation.setParentIteration(parentIteration);
        continuation.setSkillState(skillState);
        return continuation;
    }

    private PendingElicitationState buildPendingState(
            String rootUserTask, int maxIterations, int actualIterations, int failureCount,
            boolean resumedSession, List<PendingElicitationState.Continuation> queue) {
        PendingElicitationState state = new PendingElicitationState();
        state.setRootUserTask(rootUserTask);
        state.setMaxIterations(maxIterations);
        state.setActualIterations(actualIterations);
        state.setFailureCount(failureCount);
        state.setResumedSession(resumedSession);
        synchronized (toolCallRecords) {
            state.setToolCallRecords(new ArrayList<>(toolCallRecords));
        }
        state.setQueue(new ArrayList<>(queue));
        return state;
    }

    /**
     * Keeps a pending continuation's original iteration budget authoritative when the
     * application is reconfigured with a lower value, while allowing an explicit budget
     * increase to take effect on resume. Paused skill loops receive the same additional
     * budget so they do not retain an obsolete lower cap.
     */
    static int reconcilePendingIterationBudget(PendingElicitationState state,
                                               int configuredMaxIterations) {
        int persistedMaxIterations = state.getMaxIterations();
        int effectiveMaxIterations = Math.max(persistedMaxIterations, configuredMaxIterations);
        int addedIterations = effectiveMaxIterations - persistedMaxIterations;
        if (addedIterations > 0) {
            state.setMaxIterations(effectiveMaxIterations);
            if (state.getQueue() != null) {
                for (PendingElicitationState.Continuation continuation : state.getQueue()) {
                    PendingElicitationState.SkillState skillState = continuation.getSkillState();
                    if (skillState != null) {
                        skillState.setMaxSteps(skillState.getMaxSteps() + addedIterations);
                    }
                }
            }
        }
        return effectiveMaxIterations;
    }

    private ResumeResult resumePendingElicitation(
            ObjectStore<Serializable> store,
            String conversationId,
            PendingElicitationState state,
            String elicitationAnswer,
            AgentComposerConfiguration config,
            List<AgentSkillConfig> skills,
            List<ToolDefinition> mcpTools,
            List<McpServerConfig> mcpServers,
            IterationCallback callback,
            String parentSpanId) {
        List<LlmMessage> messages = loadHistory(store, conversationId);
        if (state.getVersion() != PendingElicitationState.CURRENT_VERSION
                || state.getQueue() == null || state.getQueue().isEmpty()) {
            removePendingElicitation(store, conversationId);
            throw new IllegalStateException("Stored MCP elicitation state is missing or uses an unsupported version");
        }

        PendingElicitationState.Continuation active = state.getQueue().get(0);
        ParsedElicitationAnswer parsed = parseElicitationAnswer(
                active.getRequest().getRequestedSchema(), elicitationAnswer);
        if (parsed.error != null) {
            savePendingElicitation(store, conversationId, state);
            return ResumeResult.waiting(buildElicitationResponse(state, conversationId, parsed.error));
        }

        Map<String, Object> resolveInput = new LinkedHashMap<>();
        resolveInput.put("server", active.getRequest().getServerName());
        resolveInput.put("tool", active.getRequest().getToolName());
        resolveInput.put("action", parsed.action);
        resolveInput.put("content", parsed.content);
        SpanScope resolveSpan = startTraceSpan("MCP_TOOL",
                "Resume " + active.getRequest().getToolName(), parentSpanId,
                active.getParentIteration(), resolveInput);
        McpToolResult result = null;
        String failure = null;
        try {
            McpServerConfig server = findMcpServer(active.getRequest(), mcpServers);
            McpToolResult.CallOutcome outcome = mcpClient.resolveElicitation(
                    server, active.getRequest(), parsed.action, parsed.content);
            if (outcome.isElicitationRequired()) {
                active.setRequest(outcome.getElicitation());
                savePendingElicitation(store, conversationId, state);
                traceInputRequired(resolveSpan, outcome.getElicitation());
                return ResumeResult.waiting(buildElicitationResponse(state, conversationId, null));
            }
            result = outcome.getResult();
            traceSuccess(resolveSpan, result);
        } catch (Exception e) {
            traceError(resolveSpan, e);
            failure = "Tool execution failed while resuming MCP elicitation: " + e.getMessage();
            state.setFailureCount(state.getFailureCount() + 1);
            LOGGER.warn("Could not resume MCP elicitation for conversation '{}': {}",
                    conversationId, e.getMessage(), e);
        }

        String observation = failure != null ? failure : result.getTextContent();
        McpToolResult.ElicitationRequest request = active.getRequest();

        if (active.isSkill()) {
            PendingElicitationState.SkillState skillState = active.getSkillState();
            SpanScope skillSpan = startTraceSpan("SKILL", "Resume skill " + skillState.getSkillName(),
                    parentSpanId, active.getParentIteration(),
                    Collections.singletonMap("nextStep", skillState.getNextStep()));
            ToolCall nestedCall = lastToolCall(skillState.getMessages());
            if (nestedCall == null) {
                nestedCall = new ToolCall(null, request.getToolName(), request.getToolArguments());
            }
            AgentResponse.ToolCallRecord nestedRecord = new AgentResponse.ToolCallRecord(
                    skillState.getSkillName(), request.getToolName(), request.getToolArguments(),
                    observation, active.getParentIteration());
            if (result != null && result.hasUiResource()) {
                nestedRecord.setUiResourceUri(result.getUiResourceUri());
                nestedRecord.setUiHtml(result.getUiHtml());
            }
            toolCallRecords.add(nestedRecord);
            skillState.getMessages().add(new LlmMessage("tool_result",
                    compactToolObservation(request.getToolName(), observation),
                    nestedCall.getId(), nestedCall.getName()));
            if (callback != null) {
                callback.onEvent(AgentIterationEvent.toolResult(active.getParentIteration(),
                        request.getToolName(), request.getToolArguments(), observation,
                        "skill:" + skillState.getSkillName()));
            }

            try {
                SkillResumeResult skillResult = continueSkill(skillState, mcpTools, mcpServers,
                        config, active.getParentIteration(), callback, spanId(skillSpan));
                if (skillResult.elicitation != null) {
                    active.setRequest(skillResult.elicitation);
                    active.setSkillState(skillResult.state);
                    state.setToolCallRecords(snapshotToolCallRecords());
                    savePendingElicitation(store, conversationId, state);
                    traceInputRequired(skillSpan, skillResult.elicitation);
                    return ResumeResult.waiting(buildElicitationResponse(state, conversationId, null));
                }
                observation = skillResult.answer;
                replaceToolResult(messages, active.getOuterToolCall(), observation);
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        "skill:" + skillState.getSkillName(), active.getOuterToolCall().getName(),
                        active.getOuterToolCall().getArguments(), observation, active.getParentIteration()));
                if (callback != null) {
                    callback.onEvent(AgentIterationEvent.skillResult(active.getParentIteration(),
                            active.getOuterToolCall().getName(), active.getOuterToolCall().getArguments(), observation));
                }
                traceSuccess(skillSpan, observation);
            } catch (Exception e) {
                traceError(skillSpan, e);
                observation = "Skill execution failed after MCP elicitation: " + e.getMessage();
                state.setFailureCount(state.getFailureCount() + 1);
                replaceToolResult(messages, active.getOuterToolCall(), observation);
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        "skill:" + skillState.getSkillName(), active.getOuterToolCall().getName(),
                        active.getOuterToolCall().getArguments(), observation, active.getParentIteration()));
            }
        } else {
            replaceToolResult(messages, active.getOuterToolCall(), observation);
            AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                    request.getServerName(), request.getToolName(), request.getToolArguments(),
                    observation, active.getParentIteration());
            if (result != null && result.hasUiResource()) {
                record.setUiResourceUri(result.getUiResourceUri());
                record.setUiHtml(result.getUiHtml());
            }
            toolCallRecords.add(record);
            saveFullToolOutput(store, conversationId, active.getOuterToolCall(), observation);
            if (callback != null) {
                callback.onEvent(AgentIterationEvent.toolResult(active.getParentIteration(),
                        request.getToolName(), request.getToolArguments(), observation, request.getServerName()));
            }
        }

        state.getQueue().remove(0);
        state.setToolCallRecords(snapshotToolCallRecords());
        saveHistory(store, conversationId, messages);
        if (!state.getQueue().isEmpty()) {
            savePendingElicitation(store, conversationId, state);
            return ResumeResult.waiting(buildElicitationResponse(state, conversationId, null));
        }
        removePendingElicitation(store, conversationId);
        return ResumeResult.completed(messages);
    }

    private List<AgentResponse.ToolCallRecord> snapshotToolCallRecords() {
        synchronized (toolCallRecords) {
            return new ArrayList<>(toolCallRecords);
        }
    }

    private McpServerConfig findMcpServer(McpToolResult.ElicitationRequest request,
                                          List<McpServerConfig> servers) {
        return servers.stream()
                .filter(s -> request.getServerUrl().equals(s.getServerUrl()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Configured MCP server is no longer available: " + request.getServerName()));
    }

    private ToolCall lastToolCall(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<ToolCall> calls = messages.get(i).getToolCalls();
            if (calls != null && !calls.isEmpty()) return calls.get(calls.size() - 1);
        }
        return null;
    }

    private void replaceToolResult(List<LlmMessage> messages, ToolCall call, String observation) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if ("tool_result".equals(message.getRole())
                    && call.getId() != null && call.getId().equals(message.getToolCallId())) {
                message.setContent(compactToolObservation(call.getName(), observation));
                return;
            }
        }
        messages.add(new LlmMessage("tool_result", compactToolObservation(call.getName(), observation),
                call.getId(), call.getName()));
    }

    private SkillResumeResult continueSkill(PendingElicitationState.SkillState state,
                                             List<ToolDefinition> allMcpTools,
                                             List<McpServerConfig> mcpServers,
                                             AgentComposerConfiguration config,
                                             int parentIteration,
                                             IterationCallback callback,
                                             String parentSpanId) throws Exception {
        AgentSkillConfig skill = findSkillByName(state.getSkillName(), config.getSkills());
        if (skill == null) throw new IllegalStateException("Configured skill is no longer available: " + state.getSkillName());
        List<ToolDefinition> skillTools = config.isIncludeMcpToolsAsSkills()
                ? allMcpTools : Collections.emptyList();
        LlmClient llm = LlmClientFactory.create(config);
        List<LlmMessage> skillMessages = state.getMessages();
        int consecutiveText = state.getConsecutiveTextResponses();
        int tokenCount = state.getTokensSinceCompression();
        final int maxTextReprompts = 3;

        for (int i = state.getNextStep(); i < state.getMaxSteps(); i++) {
            LlmResponse response = callLlmOnceTraced(llm, skill.getInstructions(), skillMessages,
                    skillTools, parentIteration, parentSpanId,
                    "Skill " + skill.getName() + " resumed step " + (i + 1));
            tokenCount += getTotalTokens(response);
            if (response.hasFinalAnswer()) {
                if (!skillTools.isEmpty() && consecutiveText < maxTextReprompts) {
                    consecutiveText++;
                    skillMessages.add(new LlmMessage("assistant",
                            response.getContent() != null ? response.getContent() : "Understood."));
                    skillMessages.add(new LlmMessage("user",
                            "You MUST now execute the next required step by calling the appropriate tool directly. "
                                    + "Do not produce more text — invoke the tool now."));
                    continue;
                }
                SkillResumeResult done = new SkillResumeResult();
                done.answer = response.getContent();
                return done;
            }

            consecutiveText = 0;
            ToolCall call = response.getToolCall();
            skillMessages.add(new LlmMessage("assistant", response.getContent(), Collections.singletonList(call)));
            if (callback != null) {
                callback.onEvent(AgentIterationEvent.toolCall(parentIteration, call.getName(),
                        call.getArguments(), "skill:" + skill.getName()));
            }
            SpanScope toolSpan = startTraceSpan("MCP_TOOL", call.getName(), parentSpanId,
                    parentIteration, call.getArguments());
            try {
                PendingToolPermissionState.SkillState permissionState = new PendingToolPermissionState.SkillState();
                permissionState.setSkillName(skill.getName());
                permissionState.setTask(state.getTask());
                permissionState.setMessages(new ArrayList<>(skillMessages));
                permissionState.setNextStep(i + 1);
                permissionState.setMaxSteps(state.getMaxSteps());
                permissionState.setConsecutiveTextResponses(consecutiveText);
                permissionState.setTokensSinceCompression(tokenCount);
                PendingToolPermissionState.Continuation permission = permissionContinuation(
                        call, skillTools, mcpServers, parentIteration, permissionState);
                if (permission != null) {
                    SkillResumeResult paused = new SkillResumeResult();
                    paused.permission = permission;
                    traceInputRequired(toolSpan, permissionTraceData(permission));
                    return paused;
                }
                McpToolResult.CallOutcome toolOutcome = executeTool(call, skillTools, mcpServers);
                if (toolOutcome.isElicitationRequired()) {
                    state.setMessages(skillMessages);
                    state.setNextStep(i + 1);
                    state.setConsecutiveTextResponses(consecutiveText);
                    state.setTokensSinceCompression(tokenCount);
                    SkillResumeResult paused = new SkillResumeResult();
                    paused.elicitation = toolOutcome.getElicitation();
                    paused.state = state;
                    traceInputRequired(toolSpan, toolOutcome.getElicitation());
                    return paused;
                }
                McpToolResult toolResult = toolOutcome.getResult();
                String observation = toolResult.getTextContent();
                traceSuccess(toolSpan, toolResult);
                AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                        skill.getName(), call.getName(), call.getArguments(), observation, parentIteration);
                if (toolResult.hasUiResource()) {
                    record.setUiResourceUri(toolResult.getUiResourceUri());
                    record.setUiHtml(toolResult.getUiHtml());
                }
                toolCallRecords.add(record);
                skillMessages.add(new LlmMessage("tool_result", compactToolObservation(call.getName(), observation),
                        call.getId(), call.getName()));
                if (callback != null) {
                    callback.onEvent(AgentIterationEvent.toolResult(parentIteration, call.getName(),
                            call.getArguments(), observation, "skill:" + skill.getName()));
                }
            } catch (Exception e) {
                traceError(toolSpan, e);
                String observation = "Tool execution failed: " + e.getMessage();
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        skill.getName(), call.getName(), call.getArguments(), observation, parentIteration));
                skillMessages.add(new LlmMessage("tool_result", observation, call.getId(), call.getName()));
            }

            if (tokenCount >= COMPRESSION_TOKEN_THRESHOLD) {
                skillMessages = compressHistory(llm, skillMessages, parentSpanId);
                tokenCount = 0;
            }
        }

        SkillResumeResult exhausted = new SkillResumeResult();
        exhausted.answer = "Skill '" + skill.getName() + "' did not return a final answer within "
                + state.getMaxSteps() + " iterations.";
        return exhausted;
    }

    private ParsedElicitationAnswer parseElicitationAnswer(Map<String, Object> schema, String rawInput) {
        ParsedElicitationAnswer answer = new ParsedElicitationAnswer();
        answer.action = "accept";
        String raw = rawInput != null ? rawInput.trim() : "";
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(raw);
            if (parsed.isJsonObject()) {
                Map<String, Object> object = GSON.fromJson(parsed, new TypeToken<Map<String, Object>>() {}.getType());
                Object actionValue = object.get("action");
                if (actionValue instanceof String
                        && Arrays.asList("accept", "decline", "cancel").contains(actionValue)) {
                    answer.action = (String) actionValue;
                    if ("accept".equals(answer.action)) {
                        Object content = object.get("content");
                        if (!(content instanceof Map)) {
                            answer.error = "An accept response must include an object-valued 'content' field.";
                            return answer;
                        }
                        answer.content = (Map<String, Object>) content;
                    } else {
                        answer.content = Collections.emptyMap();
                        return answer;
                    }
                } else {
                    answer.content = object;
                }
            } else if (parsed.isJsonPrimitive()) {
                String scalar = parsed.getAsJsonPrimitive().isString()
                        ? parsed.getAsString() : raw;
                answer.content = parseSingleFieldText(schema, scalar, answer);
            } else {
                answer.error = "The elicitation response must be a JSON object.";
                return answer;
            }
        } catch (Exception ignored) {
            answer.content = parseSingleFieldText(schema, raw, answer);
        }
        if (answer.error == null && "accept".equals(answer.action)) {
            answer.error = validateElicitationContent(schema, answer.content);
        }
        return answer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSingleFieldText(Map<String, Object> schema, String raw,
                                                     ParsedElicitationAnswer answer) {
        Object propsValue = schema != null ? schema.get("properties") : null;
        if (!(propsValue instanceof Map) || ((Map<?, ?>) propsValue).size() != 1) {
            answer.error = "Provide a JSON object matching the requested schema.";
            return null;
        }
        Map.Entry<String, Object> entry = ((Map<String, Object>) propsValue).entrySet().iterator().next();
        Map<String, Object> property = entry.getValue() instanceof Map
                ? (Map<String, Object>) entry.getValue() : Collections.emptyMap();
        String type = String.valueOf(property.getOrDefault("type", "string"));
        Object value;
        try {
            switch (type) {
                case "integer": value = Long.parseLong(raw); break;
                case "number": value = Double.parseDouble(raw); break;
                case "boolean":
                    if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                        throw new IllegalArgumentException();
                    }
                    value = Boolean.parseBoolean(raw); break;
                case "string": value = raw; break;
                default:
                    answer.error = "Plain text fallback is available only for one scalar field.";
                    return null;
            }
        } catch (Exception e) {
            answer.error = "Could not convert the response to the requested '" + type + "' value.";
            return null;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(entry.getKey(), value);
        return content;
    }

    @SuppressWarnings("unchecked")
    private String validateElicitationContent(Map<String, Object> schema, Map<String, Object> content) {
        if (content == null) return "The elicitation response content is required.";
        Map<String, Object> properties = schema != null && schema.get("properties") instanceof Map
                ? (Map<String, Object>) schema.get("properties") : Collections.emptyMap();
        Object requiredValue = schema != null ? schema.get("required") : null;
        if (requiredValue instanceof List) {
            for (Object name : (List<?>) requiredValue) {
                if (!content.containsKey(String.valueOf(name)) || content.get(String.valueOf(name)) == null) {
                    return "Missing required field '" + name + "'.";
                }
            }
        }
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            if (!(properties.get(entry.getKey()) instanceof Map)) continue;
            Map<String, Object> definition = (Map<String, Object>) properties.get(entry.getKey());
            Object value = entry.getValue();
            String type = String.valueOf(definition.getOrDefault("type", "string"));
            if (!matchesType(type, value)) return "Field '" + entry.getKey() + "' must be of type " + type + ".";
            String constraintError = validateConstraints(entry.getKey(), value, definition);
            if (constraintError != null) return constraintError;
        }
        return null;
    }

    private boolean matchesType(String type, Object value) {
        if (value == null) return true;
        switch (type) {
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "number": return value instanceof Number;
            case "integer": return value instanceof Number && Math.floor(((Number) value).doubleValue()) == ((Number) value).doubleValue();
            case "array": return value instanceof List;
            default: return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String validateConstraints(String field, Object value, Map<String, Object> definition) {
        if (value instanceof String) {
            String text = (String) value;
            if (definition.get("minLength") instanceof Number && text.length() < ((Number) definition.get("minLength")).intValue())
                return "Field '" + field + "' is shorter than minLength.";
            if (definition.get("maxLength") instanceof Number && text.length() > ((Number) definition.get("maxLength")).intValue())
                return "Field '" + field + "' is longer than maxLength.";
            if (definition.get("pattern") instanceof String && !Pattern.compile((String) definition.get("pattern")).matcher(text).find())
                return "Field '" + field + "' does not match the required pattern.";
            String format = definition.get("format") instanceof String ? (String) definition.get("format") : null;
            try {
                if ("email".equals(format) && !text.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException();
                if ("uri".equals(format)) URI.create(text);
                if ("date".equals(format)) LocalDate.parse(text);
                if ("date-time".equals(format)) OffsetDateTime.parse(text);
            } catch (Exception e) {
                return "Field '" + field + "' is not a valid " + format + ".";
            }
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (definition.get("minimum") instanceof Number && number < ((Number) definition.get("minimum")).doubleValue())
                return "Field '" + field + "' is below the minimum.";
            if (definition.get("maximum") instanceof Number && number > ((Number) definition.get("maximum")).doubleValue())
                return "Field '" + field + "' is above the maximum.";
        }
        if (definition.get("enum") instanceof List && !((List<?>) definition.get("enum")).contains(value))
            return "Field '" + field + "' is not one of the allowed values.";
        List<?> variants = definition.get("oneOf") instanceof List ? (List<?>) definition.get("oneOf")
                : definition.get("anyOf") instanceof List ? (List<?>) definition.get("anyOf") : null;
        if (variants != null) {
            boolean matched = variants.stream().anyMatch(v -> v instanceof Map && value.equals(((Map<?, ?>) v).get("const")));
            if (!matched) return "Field '" + field + "' is not one of the allowed values.";
        }
        if (value instanceof List && definition.get("items") instanceof Map) {
            int size = ((List<?>) value).size();
            if (definition.get("minItems") instanceof Number && size < ((Number) definition.get("minItems")).intValue())
                return "Field '" + field + "' has fewer items than minItems.";
            if (definition.get("maxItems") instanceof Number && size > ((Number) definition.get("maxItems")).intValue())
                return "Field '" + field + "' has more items than maxItems.";
            Map<String, Object> items = (Map<String, Object>) definition.get("items");
            for (Object item : (List<?>) value) {
                String error = validateConstraints(field, item, items);
                if (error != null) return error;
            }
        }
        return null;
    }

    private AgentResponse buildElicitationResponse(PendingElicitationState state,
                                                    String conversationId,
                                                    String validationError) {
        PendingElicitationState.Continuation active = state.getQueue().get(0);
        McpToolResult.ElicitationRequest request = active.getRequest();
        String prompt = request.getMessage();
        if (validationError != null) prompt = validationError + " " + prompt;
        AgentResponse response = new AgentResponse(state.getRootUserTask(), prompt,
                state.getMaxIterations(), state.getActualIterations(), false, conversationId);
        response.setRequiresInput(true);
        response.setInputKind("elicitation");
        response.setInputRequest(prompt);
        response.setElicitationSchema(request.getRequestedSchema());
        response.setElicitationServerName(request.getServerName());
        response.setElicitationToolName(request.getToolName());
        response.setResumedSession(state.isResumedSession());
        response.setFailureCount(state.getFailureCount());
        response.setReturnReason("input required");
        response.setToolCalls(snapshotToolCallRecords());
        return response;
    }

    private void savePendingElicitation(ObjectStore<Serializable> store, String conversationId,
                                        PendingElicitationState state) {
        String key = ELICITATION_STORE_PREFIX + conversationId;
        try {
            if (store.contains(key)) store.remove(key);
            store.store(key, GSON.toJson(state));
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist MCP elicitation state for '" + conversationId + "'", e);
        }
    }

    private PendingElicitationState loadPendingElicitation(ObjectStore<Serializable> store,
                                                           String conversationId) {
        String key = ELICITATION_STORE_PREFIX + conversationId;
        try {
            if (!store.contains(key)) return null;
            return GSON.fromJson((String) store.retrieve(key), PendingElicitationState.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load MCP elicitation state for '" + conversationId + "'", e);
        }
    }

    private void removePendingElicitation(ObjectStore<Serializable> store, String conversationId) {
        String key = ELICITATION_STORE_PREFIX + conversationId;
        try {
            if (store.contains(key)) store.remove(key);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove MCP elicitation state for '" + conversationId + "'", e);
        }
    }

    // ── Object Store helpers ──────────────────────────────────────────────────

    private List<LlmMessage> loadHistory(ObjectStore<Serializable> store, String conversationId) {
        try {
            if (store.contains(conversationId)) {
                return GSON.fromJson((String) store.retrieve(conversationId), MSG_LIST_TYPE);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load history for '{}': {}", conversationId, e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    private boolean hasConversationHistory(ObjectStore<Serializable> store, String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return false;
        }
        try {
            return store.contains(conversationId);
        } catch (Exception e) {
            LOGGER.warn("Could not inspect history for '{}': {}", conversationId, e.getMessage());
            return false;
        }
    }

    private void saveHistory(ObjectStore<Serializable> store, String conversationId,
                             List<LlmMessage> messages) {
        try {
            if (store.contains(conversationId)) store.remove(conversationId);
            store.store(conversationId, GSON.toJson(messages));
        } catch (Exception e) {
            LOGGER.warn("Could not persist history for '{}': {}", conversationId, e.getMessage(), e);
        }
    }

    // ── Public memory operations ──────────────────────────────────────────────

    /**
     * Clears all entries in the object store, resetting agent memory from scratch.
     */
    public java.util.Map<String, Object> resetMemory(String objectStoreName) throws Exception {
        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        List<String> keys = store.allKeys();
        int count = keys.size();
        for (String key : keys) {
            try {
                store.remove(key);
            } catch (Exception e) {
                LOGGER.warn("Could not remove key '{}' during reset: {}", key, e.getMessage());
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "Memory reset. Removed " + count + " entr" + (count == 1 ? "y" : "ies") + ".");
        result.put("objectStore", objectStoreName);
        result.put("entriesRemoved", count);
        return result;
    }

    /**
     * Searches past solved responses for tasks semantically similar to {@code userTask},
     * using the LLM to score relevance. Returns up to {@code maxResults} matches.
     */
    public java.util.Map<String, Object> checkMemory(AgentComposerConfiguration config,
                                                      String userTask,
                                                      String objectStoreName,
                                                      int maxResults) throws Exception {
        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        List<String> allKeys = store.allKeys();

        List<AgentResponse> candidates = new ArrayList<>();
        for (String key : allKeys) {
            if (key.startsWith("resolved::")) {
                AgentResponse response = loadSolvedResponse(store, key);
                if (response != null && response.getUserTask() != null && !response.getUserTask().trim().isEmpty()) {
                    candidates.add(response);
                }
            }
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (candidates.isEmpty()) {
            result.put("found", false);
            result.put("message", "No past memories found in object store.");
            result.put("matches", Collections.emptyList());
            return result;
        }

        // Build a numbered list of past tasks for the LLM
        StringBuilder memoryList = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            AgentResponse r = candidates.get(i);
            memoryList.append(i).append(". Task: ").append(r.getUserTask());
            if (r.getConversationSummary() != null) {
                memoryList.append(" | Summary: ").append(r.getConversationSummary());
            }
            memoryList.append("\n");
        }

        LlmClient llmClient = LlmClientFactory.create(config);
        String systemPrompt = "You are a memory search assistant. Given a current user task and a list of past agent memories, "
                + "identify which past memories are most relevant. Return ONLY a valid JSON object with key "
                + "'matches' containing an array of objects, each with: index (integer), confidence (integer 0-100), reason (string). "
                + "Return at most " + maxResults + " items, ordered by descending confidence. "
                + "Only include items with confidence >= 40.";
        String userContent = "Current task: " + userTask + "\n\nPast memories:\n" + memoryList;

        LlmResponse llmResponse = llmClient.chat(
                systemPrompt,
                Collections.singletonList(new LlmMessage("user", userContent)),
                Collections.emptyList());

        // Parse the LLM response to extract matched indices
        List<java.util.Map<String, Object>> matches = new ArrayList<>();
        try {
            String content = llmResponse.getContent();
            // Strip markdown code fences if present
            if (content != null) {
                content = content.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
            }
            java.lang.reflect.Type mapType = new TypeToken<java.util.Map<String, Object>>() {}.getType();
            java.util.Map<String, Object> parsed = GSON.fromJson(content, mapType);
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> llmMatches = (List<java.util.Map<String, Object>>) parsed.get("matches");
            if (llmMatches != null) {
                for (java.util.Map<String, Object> m : llmMatches) {
                    int idx = ((Number) m.get("index")).intValue();
                    if (idx >= 0 && idx < candidates.size()) {
                        AgentResponse matched = candidates.get(idx);
                        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("sessionId", matched.getSessionId());
                        entry.put("userTask", matched.getUserTask());
                        entry.put("response", matched.getResponse());
                        entry.put("confidence", m.get("confidence"));
                        entry.put("reason", m.get("reason"));
                        matches.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not parse LLM memory-check response: {}", e.getMessage());
        }

        result.put("found", !matches.isEmpty());
        result.put("userTask", userTask);
        result.put("matches", matches);
        result.put("totalMemoriesSearched", candidates.size());
        return result;
    }

    /**
     * Retrieves the conversation history and solved responses for the given session ID.
     */
    public java.util.Map<String, Object> retrieveConversation(String sessionId,
                                                               String objectStoreName) throws Exception {
        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sessionId", sessionId);

        // Conversation history (chat turns)
        List<LlmMessage> history = loadHistory(store, sessionId);
        result.put("conversationHistory", history);
        result.put("conversationTurns", history.size());

        PendingElicitationState pending = loadPendingElicitation(store, sessionId);
        if (pending != null && pending.getQueue() != null && !pending.getQueue().isEmpty()) {
            McpToolResult.ElicitationRequest request = pending.getQueue().get(0).getRequest();
            java.util.Map<String, Object> pendingSummary = new java.util.LinkedHashMap<>();
            pendingSummary.put("inputKind", "elicitation");
            pendingSummary.put("inputRequest", request.getMessage());
            pendingSummary.put("elicitationSchema", request.getRequestedSchema());
            pendingSummary.put("serverName", request.getServerName());
            pendingSummary.put("toolName", request.getToolName());
            pendingSummary.put("queuedRequests", pending.getQueue().size());
            result.put("pendingElicitation", pendingSummary);
        } else {
            result.put("pendingElicitation", null);
        }

        PendingToolPermissionState pendingPermission = loadPendingPermission(store, sessionId);
        if (pendingPermission != null && pendingPermission.getQueue() != null
                && !pendingPermission.getQueue().isEmpty()) {
            PendingToolPermissionState.Continuation active = pendingPermission.getQueue().get(0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("inputKind", "tool_permission");
            summary.put("permissionRequestId", active.getPermissionRequestId());
            summary.put("serverName", active.getServerName());
            summary.put("toolName", active.getToolCall().getName());
            summary.put("arguments", active.getToolCall().getArguments());
            summary.put("responseSchema", toolPermissionResponseSchema());
            summary.put("queuedRequests", pendingPermission.getQueue().size());
            result.put("pendingToolPermission", summary);
        } else {
            result.put("pendingToolPermission", null);
        }

        // Solved / cached responses scoped to this session
        List<java.util.Map<String, Object>> solvedEntries = new ArrayList<>();
        try {
            List<String> allKeys = store.allKeys();
            String prefix = "resolved::" + sessionId + "::";
            for (String key : allKeys) {
                if (key.startsWith(prefix)) {
                    AgentResponse response = loadSolvedResponse(store, key);
                    if (response != null) {
                        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("userTask", response.getUserTask());
                        entry.put("response", response.getResponse());
                        entry.put("conversationSummary", response.getConversationSummary());
                        entry.put("toolCalls", response.getToolCalls());
                        entry.put("iterationCount", response.getIterationCount());
                        entry.put("complete", response.isComplete());
                        entry.put("returnReason", response.getReturnReason());
                        solvedEntries.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not scan solved responses for session '{}': {}", sessionId, e.getMessage());
        }

        result.put("solvedResponses", solvedEntries);
        result.put("found", !history.isEmpty() || !solvedEntries.isEmpty()
                || pending != null || pendingPermission != null);
        return result;
    }

    /**
     * Deletes conversation entries by session ID, by user task match, or both.
     * At least one of {@code sessionId} or {@code userTask} must be non-null.
     */
    public java.util.Map<String, Object> deleteConversation(String sessionId,
                                                             String userTask,
                                                             String objectStoreName) throws Exception {
        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        List<String> allKeys = store.allKeys();
        List<String> removed = new ArrayList<>();

        // Delete by session ID: remove history key + all resolved:: scoped keys
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            if (store.contains(sessionId)) {
                store.remove(sessionId);
                removed.add(sessionId);
            }
            String pendingKey = ELICITATION_STORE_PREFIX + sessionId;
            if (store.contains(pendingKey)) {
                store.remove(pendingKey);
                removed.add(pendingKey);
            }
            String permissionKey = PERMISSION_STORE_PREFIX + sessionId;
            if (store.contains(permissionKey)) {
                store.remove(permissionKey);
                removed.add(permissionKey);
            }
            String scopedPrefix = "resolved::" + sessionId + "::";
            for (String key : allKeys) {
                if (key.startsWith(scopedPrefix)) {
                    try {
                        store.remove(key);
                        removed.add(key);
                    } catch (Exception e) {
                        LOGGER.warn("Could not remove key '{}': {}", key, e.getMessage());
                    }
                }
            }
        }

        // Delete by user task: scan all resolved:: entries and remove matching ones
        if (userTask != null && !userTask.trim().isEmpty()) {
            String taskLower = userTask.trim().toLowerCase();
            for (String key : allKeys) {
                if (removed.contains(key)) continue;
                if (key.startsWith("resolved::")) {
                    AgentResponse response = loadSolvedResponse(store, key);
                    if (response != null && response.getUserTask() != null
                            && response.getUserTask().toLowerCase().contains(taskLower)) {
                        // Also remove the conversation history if session ID is known
                        String linkedSession = response.getSessionId();
                        if (linkedSession != null && !linkedSession.trim().isEmpty()
                                && !removed.contains(linkedSession) && store.contains(linkedSession)) {
                            try {
                                store.remove(linkedSession);
                                removed.add(linkedSession);
                            } catch (Exception e) {
                                LOGGER.warn("Could not remove history for session '{}': {}", linkedSession, e.getMessage());
                            }
                        }
                        if (linkedSession != null && !linkedSession.trim().isEmpty()) {
                            for (String pendingPrefix : Arrays.asList(ELICITATION_STORE_PREFIX, PERMISSION_STORE_PREFIX)) {
                                String linkedPending = pendingPrefix + linkedSession;
                                if (!removed.contains(linkedPending) && store.contains(linkedPending)) {
                                    try {
                                        store.remove(linkedPending);
                                        removed.add(linkedPending);
                                    } catch (Exception e) {
                                        LOGGER.warn("Could not remove pending state '{}': {}", linkedPending, e.getMessage());
                                    }
                                }
                            }
                        }
                        try {
                            store.remove(key);
                            removed.add(key);
                        } catch (Exception e) {
                            LOGGER.warn("Could not remove key '{}': {}", key, e.getMessage());
                        }
                    }
                }
            }
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "success");
        result.put("entriesRemoved", removed.size());
        result.put("removedKeys", removed);
        result.put("objectStore", objectStoreName);
        return result;
    }
}
