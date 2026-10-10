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
import org.mule.extension.agent.composer.internal.model.ToolCall;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Type;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final List<AgentResponse.ToolCallRecord> toolCallRecords = new ArrayList<>();

    public ReactEngine(ObjectStoreManager objectStoreManager) {
        this.objectStoreManager = objectStoreManager;
        this.mcpClient = new McpClient();
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

        String instructions = config.getInstructions();
        List<McpServerConfig> mcpServers = config.getMcpServers();
        String objectStoreName = config.getObjectStore();

        LlmClient llmClient = LlmClientFactory.create(config);

        List<ToolDefinition> mcpTools = discoverMcpTools(mcpServers);
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

        boolean resumedSession = hasConversationHistory(store, conversationId);

        String requestSignature = buildRequestSignature(config, userMessage, mcpServers);
        String solvedCacheKeyScoped = buildSolvedCacheKey(conversationId, requestSignature);
        String solvedCacheKeyGlobal = buildSolvedCacheKey(null, requestSignature);

        if (!resumedSession) {
            AgentResponse cached = loadSolvedResponse(store, solvedCacheKeyScoped);
            if (cached == null) {
                cached = loadSolvedResponse(store, solvedCacheKeyGlobal);
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
                return cached;
            }
        }

        List<LlmMessage> messages = loadHistory(store, conversationId);
        messages.add(new LlmMessage("user", userMessage));

        String currentThought = userMessage;
        String finalAnswer = null;
        String bestFinalAnswer = null;
        int actualIterations = 0;
        int toolFailureCount = 0;
        int tokensSinceCompression = 0; // compress when accumulated LLM token usage crosses threshold

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            actualIterations = iteration + 1;
            LOGGER.info("--- ReAct Iteration {}/{} | conversationId='{}' ---", iteration + 1, maxIterations, conversationId);
            LOGGER.debug("[Iteration {}] What am I trying to do? {}", iteration + 1, currentThought);

            LOGGER.debug("[Iteration {}] Sending request to LLM | provider={} model={}", iteration + 1, config.getProvider(), config.getModelName());
            LOGGER.debug("[Iteration {}] Instructions: {}", iteration + 1, instructions);
            LOGGER.debug("[Iteration {}] User prompt: {}", iteration + 1, userMessage);
            LOGGER.debug("[Iteration {}] Tools in use ({}): {}", iteration + 1, allTools.size(),
                    allTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
            LOGGER.info("[Iteration {}] Sending {} message(s) to LLM", iteration + 1, messages.size());

            LlmResponse response = callLlmWithSingleRetry(llmClient, instructions, messages, allTools, iteration + 1);
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
                    break;
                }

                LOGGER.warn("[Iteration {}] LLM returned no tool calls and no final text; asking for a textual answer.", iteration + 1);
                messages.add(new LlmMessage("user",
                        "Provide the final answer to the user in text using the available tool results. "
                                + "Do not call additional tools unless more information is truly required."));
                saveHistory(store, conversationId, messages);
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
                        .map(tc -> CompletableFuture.supplyAsync(
                                () -> executeToolOrSkill(tc, mcpTools, mcpServers, config, skills,
                                        capturedThought, iterNum, remainingIter, null),
                                MCP_IO_EXECUTOR))
                        .collect(Collectors.toList());

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Collect results, fire response callbacks, record tool calls, append tool_result messages
                McpToolResult.ElicitationRequest pendingElicitation = null;
                for (int t = 0; t < toolCalls.size(); t++) {
                    ToolCall tc = toolCalls.get(t);
                    ExecutionOutcome outcome;
                    try {
                        outcome = futures.get(t).get();
                    } catch (Exception e) {
                        outcome = new ExecutionOutcome("Error: " + e.getMessage(), null, true, null, null);
                    }

                    if (outcome.elicitation != null) {
                        // One paused tool call pauses the whole iteration; still record a tool_result
                        // placeholder for every tool_use so the LLM message history stays well-formed.
                        if (pendingElicitation == null) pendingElicitation = outcome.elicitation;
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

                if (pendingElicitation != null) {
                    saveHistory(store, conversationId, messages);
                    savePendingElicitation(store, conversationId, pendingElicitation);
                    return buildElicitationResponse(pendingElicitation, userMessage, maxIterations,
                            actualIterations, conversationId, resumedSession, toolCallRecords);
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
                        currentThought, iteration + 1, maxIterations - iteration, callback);

                if (outcome.elicitation != null) {
                    messages.add(new LlmMessage("tool_result", "Waiting for user input (elicitation).", toolCall.getId(), toolCall.getName()));
                    saveHistory(store, conversationId, messages);
                    savePendingElicitation(store, conversationId, outcome.elicitation);
                    return buildElicitationResponse(outcome.elicitation, userMessage, maxIterations,
                            actualIterations, conversationId, resumedSession, toolCallRecords);
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
                messages = compressHistory(llmClient, messages);
                tokensSinceCompression = 0;
            }

            saveHistory(store, conversationId, messages);
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

        if (result.isComplete() && !isMutationRequest(userMessage)) {
            String summary = summarizeSolvedConversation(userMessage, finalAnswer, actualIterations, toolCallRecords.size());
            result.setConversationSummary(summary);
            saveSolvedResponse(store, solvedCacheKeyScoped, result);
            saveSolvedResponse(store, solvedCacheKeyGlobal, result);
        } else if (result.isComplete()) {
            LOGGER.debug("Skipping solved-response cache for mutation request: '{}'", userMessage);
        }

        return result;
    }

    // ── tool discovery ────────────────────────────────────────────────────────

    private List<ToolDefinition> discoverMcpTools(List<McpServerConfig> mcpServers) throws Exception {
        if (mcpServers == null || mcpServers.isEmpty()) return Collections.emptyList();

        // Independent per-server discovery calls — fan out instead of querying servers one at a time.
        List<CompletableFuture<List<ToolDefinition>>> futures = mcpServers.stream()
                .map(server -> CompletableFuture.supplyAsync(() -> {
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
                }, MCP_IO_EXECUTOR))
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
        /** Non-null when the MCP server paused this call with an elicitation/create request. */
        final McpToolResult.ElicitationRequest elicitation;

        ExecutionOutcome(String observation, String serverName, boolean failed, String uiResourceUri, String uiHtml) {
            this.observation = observation;
            this.serverName = serverName;
            this.failed = failed;
            this.uiResourceUri = uiResourceUri;
            this.uiHtml = uiHtml;
            this.elicitation = null;
        }

        private ExecutionOutcome(McpToolResult.ElicitationRequest elicitation) {
            this.observation = null;
            this.serverName = elicitation.getServerName();
            this.failed = false;
            this.uiResourceUri = null;
            this.uiHtml = null;
            this.elicitation = elicitation;
        }

        static ExecutionOutcome paused(McpToolResult.ElicitationRequest elicitation) {
            return new ExecutionOutcome(elicitation);
        }
    }

    /** Internal signal used to bubble an MCP elicitation pause up through a skill's sub-loop without being mistaken for a tool failure. */
    private static final class ElicitationPauseSignal extends RuntimeException {
        final McpToolResult.ElicitationRequest request;
        ElicitationPauseSignal(McpToolResult.ElicitationRequest request) { this.request = request; }
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
                                                IterationCallback subLoopCallback) {
        AgentSkillConfig matchedSkill = findSkillByName(toolCall.getName(), skills);
        if (matchedSkill != null) {
            String taskArg = toolCall.getArguments() != null
                    ? (String) toolCall.getArguments().getOrDefault("task", currentThought)
                    : currentThought;
            String serverName = "skill:" + matchedSkill.getName();
            LOGGER.info("[Iteration {}] >>> Using skill '{}' | task: {}", iteration, matchedSkill.getName(), taskArg);
            try {
                String obs = executeSkill(matchedSkill, mcpTools, mcpServers, config, taskArg,
                        iteration, remainingIterations, subLoopCallback);
                LOGGER.info("[Iteration {}] Skill response | name='{}' result={}", iteration, matchedSkill.getName(), obs);
                return new ExecutionOutcome(obs, serverName, false, null, null);
            } catch (ElicitationPauseSignal pause) {
                LOGGER.info("[Iteration {}] Skill '{}' paused for user input (MCP elicitation) | requestId={}",
                        iteration, matchedSkill.getName(), pause.request.getRequestId());
                return ExecutionOutcome.paused(pause.request);
            } catch (Exception e) {
                LOGGER.warn("[Iteration {}] Skill '{}' failed: {}", iteration, matchedSkill.getName(), e.getMessage(), e);
                return new ExecutionOutcome("Skill execution failed: " + e.getMessage(), serverName, true, null, null);
            }
        }

        String serverName = findMcpServerName(toolCall, mcpTools, mcpServers);
        LOGGER.info("[Iteration {}] >>> Using tool '{}' (server: {}) | args: {}",
                iteration, toolCall.getName(), serverName != null ? serverName : "unknown", toolCall.getArguments());
        try {
            McpToolResult.CallOutcome outcome = executeTool(toolCall, mcpTools, mcpServers);
            if (outcome.isElicitationRequired()) {
                LOGGER.info("[Iteration {}] Tool '{}' paused for user input (MCP elicitation) | requestId={}",
                        iteration, toolCall.getName(), outcome.getElicitation().getRequestId());
                return ExecutionOutcome.paused(outcome.getElicitation());
            }
            McpToolResult result = outcome.getResult();
            LOGGER.info("[Iteration {}] Tool response | name='{}' result={}", iteration, toolCall.getName(), result.getTextContent());
            return new ExecutionOutcome(result.getTextContent(), serverName, false,
                    result.getUiResourceUri(), result.getUiHtml());
        } catch (Exception e) {
            LOGGER.warn("[Iteration {}] Tool '{}' failed: {}", iteration, toolCall.getName(), e.getMessage(), e);
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

    private LlmResponse callLlmWithSingleRetry(LlmClient llmClient,
                                               String instructions,
                                               List<LlmMessage> messages,
                                               List<ToolDefinition> tools,
                                               int iterationNumber) throws Exception {
        try {
            return llmClient.chat(instructions, messages, tools);
        } catch (Exception firstError) {
            LOGGER.warn("[Iteration {}] LLM call failed on first attempt, retrying once: {}",
                    iterationNumber, firstError.getMessage());
            try {
                return llmClient.chat(instructions, messages, tools);
            } catch (Exception secondError) {
                LOGGER.error("[Iteration {}] LLM call failed after one retry: {}",
                        iterationNumber, secondError.getMessage(), secondError);
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
                                IterationCallback callback) throws Exception {
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
            LlmResponse response = llmClient.chat(skillInstructions, skillMessages, skillMcpTools);
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
            try {
                McpToolResult.CallOutcome toolOutcome = executeTool(toolCall, skillMcpTools, mcpServers);
                if (toolOutcome.isElicitationRequired()) {
                    throw new ElicitationPauseSignal(toolOutcome.getElicitation());
                }
                McpToolResult skillToolResult = toolOutcome.getResult();
                obs = skillToolResult.getTextContent();
                AgentResponse.ToolCallRecord skillRecord = new AgentResponse.ToolCallRecord(
                        skill.getName(), toolCall.getName(), toolCall.getArguments(), obs, parentIteration);
                if (skillToolResult.hasUiResource()) {
                    skillRecord.setUiResourceUri(skillToolResult.getUiResourceUri());
                    skillRecord.setUiHtml(skillToolResult.getUiHtml());
                }
                toolCallRecords.add(skillRecord);
            } catch (ElicitationPauseSignal pause) {
                throw pause;
            } catch (Exception e) {
                obs = "Tool execution failed: " + e.getMessage();
                LOGGER.warn("[Skill '{}'] Tool '{}' failed: {}", skill.getName(), toolCall.getName(), e.getMessage());
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
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
                skillMessages = compressHistory(llmClient, skillMessages);
                skillTokensSinceCompression = 0;
            }
        }

        if (skillAnswer == null) {
            skillAnswer = "Skill '" + skill.getName() + "' did not return a final answer within "
                    + maxSkillIterations + " iterations.";
        }
        return skillAnswer;
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
    private List<LlmMessage> compressHistory(LlmClient llmClient, List<LlmMessage> messages) {
        // Nothing to compress if the list is tiny.
        if (messages.size() <= 4) return messages;

        int keepTail = 2; // always keep the last 2 messages (most recent thought + tool result)
        int compressUpTo = messages.size() - keepTail;
        
        while (compressUpTo > 1 && "tool_result".equals(messages.get(compressUpTo).getRole())) {
            compressUpTo--;
        }
        // If we couldn't find a safe cut point, leave the history untouched.
        if (compressUpTo <= 1) return messages;

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

            LlmResponse summary = llmClient.chat(
                    "You are a concise summariser.",
                    Collections.singletonList(new LlmMessage("user", sb.toString())),
                    Collections.emptyList());

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
            return compressed;
        } catch (Exception e) {
            LOGGER.warn("[Context compression] Failed to compress history, keeping full history: {}", e.getMessage());
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
        return "resolved::" + conv + "::" + Integer.toHexString(requestSignature.hashCode());
    }

    private String buildRequestSignature(AgentComposerConfiguration config,
                                         String userMessage,
                                         List<McpServerConfig> mcpServers) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.getProvider() == null ? "" : config.getProvider().name()).append("|")
          .append(config.getModelName() == null ? "" : config.getModelName().trim().toLowerCase()).append("|")
          .append(userMessage == null ? "" : userMessage.trim().toLowerCase()).append("|");

        if (mcpServers != null && !mcpServers.isEmpty()) {
            String servers = mcpServers.stream()
                    .sorted(Comparator.comparing(s -> (s.getServerUrl() == null ? "" : s.getServerUrl())))
                    .map(s -> {
                        String name = s.getName() == null ? "" : s.getName();
                        String url = s.getServerUrl() == null ? "" : s.getServerUrl();
                        return name + "@" + url;
                    })
                    .collect(Collectors.joining(","));
            sb.append(servers);
        }
        return sb.toString();
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
        result.put("found", !history.isEmpty() || !solvedEntries.isEmpty());
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
