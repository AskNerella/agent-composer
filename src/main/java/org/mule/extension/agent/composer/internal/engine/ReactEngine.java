package org.mule.extension.agent.composer.internal.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.llm.LlmClient;
import org.mule.extension.agent.composer.internal.llm.LlmClientFactory;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.AgentResponse;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
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
import java.util.List;
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

    private final ObjectStoreManager objectStoreManager;
    private final McpClient mcpClient;
    private final List<AgentResponse.ToolCallRecord> toolCallRecords = new ArrayList<>();

    public ReactEngine(ObjectStoreManager objectStoreManager) {
        this.objectStoreManager = objectStoreManager;
        this.mcpClient = new McpClient();
    }

    // ── main entry point ──────────────────────────────────────────────────────

    /**
     * Runs the ReAct loop and returns structured response with metrics.
     *
     * @param config              LLM configuration (provider, model, key, etc.)
     * @param instructions        system prompt
     * @param userMessage         the user's input for this turn
     * @param mcpServers          MCP servers to discover tools from (each carries its own tool filter)
     * @param objectStoreName     name of the Object Store used to persist conversation history
     * @param conversationId      key scoping this conversation in the Object Store
     * @param maxIterations       maximum Reason-Act cycles before forced exit
     * @return AgentResponse containing final answer, metrics, and tool call history
     */
    public AgentResponse run(AgentComposerConfiguration config,
                             String instructions,
                             String userMessage,
                             List<McpServerConfig> mcpServers,
                             String objectStoreName,
                             String conversationId,
                             int maxIterations) throws Exception {

        LlmClient llmClient = LlmClientFactory.create(config);

        List<ToolDefinition> tools = discoverTools(mcpServers);
        LOGGER.info("Tools available ({}): {}", tools.size(),
                tools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);
        toolCallRecords.clear();

        String requestSignature = buildRequestSignature(config, userMessage, mcpServers);
        String solvedCacheKeyScoped = buildSolvedCacheKey(conversationId, requestSignature);
        String solvedCacheKeyGlobal = buildSolvedCacheKey(null, requestSignature);

        AgentResponse cached = loadSolvedResponse(store, solvedCacheKeyScoped);
        if (cached == null) {
            cached = loadSolvedResponse(store, solvedCacheKeyGlobal);
        }
        if (cached != null) {
            cached.setCacheHit(true);
            if (cached.getSessionId() == null || cached.getSessionId().trim().isEmpty()) {
                cached.setSessionId(conversationId);
            }
            if (cached.getReturnReason() == null || cached.getReturnReason().trim().isEmpty()) {
                cached.setReturnReason(cached.isComplete() ? "completed successfully" : "incomplete cached response");
            }
            return cached;
        }

        List<LlmMessage> messages = loadHistory(store, conversationId);
        messages.add(new LlmMessage("user", userMessage));

        String currentThought = userMessage;
        String finalAnswer = null;
        String bestFinalAnswer = null;
        int actualIterations = 0;
        int toolFailureCount = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            actualIterations = iteration + 1;
            LOGGER.info("--- ReAct Iteration {}/{} | conversationId='{}' ---", iteration + 1, maxIterations, conversationId);
            LOGGER.debug("[Iteration {}] What am I trying to do? {}", iteration + 1, currentThought);

            LOGGER.debug("[Iteration {}] Sending request to LLM | provider={} model={}", iteration + 1, config.getProvider(), config.getModelName());
            LOGGER.debug("[Iteration {}] Instructions: {}", iteration + 1, instructions);
            LOGGER.debug("[Iteration {}] User prompt: {}", iteration + 1, userMessage);
            LOGGER.debug("[Iteration {}] Tools in use ({}): {}", iteration + 1, tools.size(),
                    tools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

            LlmResponse response = callLlmWithSingleRetry(llmClient, instructions, messages, tools, iteration + 1);
            currentThought = response.getContent() != null ? response.getContent() : "";

            LOGGER.debug("[Iteration {}] LLM response | stop_reason='{}' hasToolCall={}", iteration + 1, response.getStopReason(), response.hasToolCall());
            if (!currentThought.isEmpty()) {
                LOGGER.debug("[Iteration {}] LLM content: {}", iteration + 1, currentThought);
            }

            if (response.hasFinalAnswer()) {
                if (isBetterFinalAnswer(currentThought, bestFinalAnswer)) {
                    bestFinalAnswer = currentThought;
                }
                finalAnswer = bestFinalAnswer;
                LOGGER.debug("[Iteration {}] Final answer received.", iteration + 1);
                messages.add(new LlmMessage("assistant", currentThought));
                saveHistory(store, conversationId, messages);

                if (shouldStopAfterFinalAnswer(toolCallRecords, tools)) {
                    LOGGER.info("[Iteration {}] Stopping early: grounded final answer is available.", iteration + 1);
                    break;
                }
                continue;
            }

            ToolCall toolCall = response.getToolCall();
            LOGGER.debug("[Iteration {}] Tool call requested: name='{}' args={}", iteration + 1, toolCall.getName(), toolCall.getArguments());
            messages.add(new LlmMessage("assistant", currentThought, Collections.singletonList(toolCall)));

            String observation;
            String mcpServerName = null;
            try {
                observation = executeTool(toolCall, tools, mcpServers);
                mcpServerName = findMcpServerName(toolCall, tools, mcpServers);
                LOGGER.debug("[Iteration {}] Tool '{}' observation: {}", iteration + 1, toolCall.getName(), observation);
                
                // Track tool call for response metrics
                AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                        mcpServerName != null ? mcpServerName : "unknown",
                        toolCall.getName(),
                        toolCall.getArguments(),
                        observation,
                        iteration + 1
                );
                toolCallRecords.add(record);
                
            } catch (Exception e) {
                observation = "Tool execution failed: " + e.getMessage();
                toolFailureCount++;
                LOGGER.warn("[Iteration {}] Tool '{}' failed: {}", iteration + 1, toolCall.getName(), e.getMessage(), e);
                
                // Track failed tool call
                mcpServerName = findMcpServerName(toolCall, tools, mcpServers);
                AgentResponse.ToolCallRecord record = new AgentResponse.ToolCallRecord(
                        mcpServerName != null ? mcpServerName : "unknown",
                        toolCall.getName(),
                        toolCall.getArguments(),
                        observation,
                        iteration + 1
                );
                toolCallRecords.add(record);
            }

            messages.add(new LlmMessage("tool_result", observation, toolCall.getId(), toolCall.getName()));
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
        result.setFailureCount(toolFailureCount);
        result.setReturnReason(returnReason);
        result.setCacheHit(false);

        if (result.isComplete()) {
            String summary = summarizeSolvedConversation(userMessage, finalAnswer, actualIterations, toolCallRecords.size());
            result.setConversationSummary(summary);
            saveSolvedResponse(store, solvedCacheKeyScoped, result);
            saveSolvedResponse(store, solvedCacheKeyGlobal, result);
        }

        return result;
    }

    // ── tool discovery ────────────────────────────────────────────────────────

    private List<ToolDefinition> discoverTools(List<McpServerConfig> mcpServers) throws Exception {
        if (mcpServers == null || mcpServers.isEmpty()) return Collections.emptyList();

        List<ToolDefinition> all = new ArrayList<>();
        for (McpServerConfig server : mcpServers) {
            LOGGER.debug("Fetching MCP tools for client '{}' from {}", server.getName(), server.getServerUrl());
            List<ToolDefinition> serverTools = mcpClient.listTools(server);
            List<String> filter = server.getToolFilters();
            if (filter != null && !filter.isEmpty()) {
                serverTools = serverTools.stream()
                        .filter(t -> filter.contains(t.getName()))
                        .collect(Collectors.toList());
            }
            LOGGER.debug("Fetched {} tool(s) for client '{}': {}", serverTools.size(), server.getName(),
                    serverTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));
            all.addAll(serverTools);
        }
        return all;
    }

    // ── tool execution ────────────────────────────────────────────────────────

    private String executeTool(ToolCall toolCall, List<ToolDefinition> tools,
                               List<McpServerConfig> mcpServers) throws Exception {
        ToolDefinition toolDef = tools.stream()
                .filter(t -> t.getName().equals(toolCall.getName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool '" + toolCall.getName() + "' not found"));

        McpServerConfig server = mcpServers.stream()
                .filter(s -> s.getServerUrl().equals(toolDef.getServerUrl()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No MCP server for URL: " + toolDef.getServerUrl()));

        return mcpClient.callTool(server, toolCall.getName(), toolCall.getArguments());
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

    private boolean shouldStopAfterFinalAnswer(List<AgentResponse.ToolCallRecord> toolCalls,
                                               List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return true;
        }
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }
        for (AgentResponse.ToolCallRecord call : toolCalls) {
            if (call == null) {
                continue;
            }
            String response = call.getToolResponse();
            if (response != null && !response.trim().isEmpty() && !isLikelyToolError(response)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyToolError(String text) {
        String lower = text.toLowerCase();
        return lower.startsWith("tool execution failed")
                || lower.startsWith("error calling tool")
                || lower.contains(" server error")
                || lower.contains("http error")
                || lower.contains("connection refused")
                || lower.contains("timed out");
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

    private void saveHistory(ObjectStore<Serializable> store, String conversationId,
                             List<LlmMessage> messages) {
        try {
            if (store.contains(conversationId)) store.remove(conversationId);
            store.store(conversationId, GSON.toJson(messages));
        } catch (Exception e) {
            LOGGER.warn("Could not persist history for '{}': {}", conversationId, e.getMessage(), e);
        }
    }
}
