package org.mule.extension.agent.composer.internal.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
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

    /** Verbs that indicate a create/update/delete intent — responses to these are not cached. */
    private static final List<String> MUTATION_VERBS = Arrays.asList(
            "create", "add", "insert", "make", "build", "generate", "upload", "register", "new",
            "update", "edit", "modify", "change", "set", "rename", "patch", "replace", "move",
            "delete", "remove", "drop", "clear", "reset", "destroy", "wipe", "purge",
            "disable", "enable", "toggle", "send", "submit", "publish", "deploy", "push");

    /** Callback invoked after each tool or skill execution in the ReAct loop. */
    @FunctionalInterface
    public interface IterationCallback {
        void onIteration(int iteration, String actionName, String observation);
    }

    private final ObjectStoreManager objectStoreManager;
    private final McpClient mcpClient;
    private final List<AgentResponse.ToolCallRecord> toolCallRecords = new ArrayList<>();

    public ReactEngine(ObjectStoreManager objectStoreManager) {
        this.objectStoreManager = objectStoreManager;
        this.mcpClient = new McpClient();
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
        List<ToolDefinition> allTools = new ArrayList<>(mcpTools);
        allTools.addAll(skillTools);
        LOGGER.info("MCP tools available ({}): {}", mcpTools.size(),
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

            LOGGER.debug("[Iteration {}] Tokens used | input={} output={} total={}",
                    iteration + 1, response.getInputTokens(), response.getOutputTokens(),
                    response.getInputTokens() + response.getOutputTokens());
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

                if (shouldStopAfterFinalAnswer(toolCallRecords, allTools)) {
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
            AgentSkillConfig matchedSkill = findSkillByName(toolCall.getName(), skills);

            if (matchedSkill != null) {
                // ── Skill execution ─────────────────────────────────────────
                String taskArg = toolCall.getArguments() != null
                        ? (String) toolCall.getArguments().getOrDefault("task", currentThought)
                        : currentThought;
                LOGGER.info("[Iteration {}] >>> Using skill '{}' | task: {}",
                        iteration + 1, matchedSkill.getName(), taskArg);
                mcpServerName = "skill:" + matchedSkill.getName();
                try {
                    observation = executeSkill(matchedSkill, mcpTools, mcpServers, config, taskArg, iteration + 1);
                } catch (Exception e) {
                    observation = "Skill execution failed: " + e.getMessage();
                    toolFailureCount++;
                    LOGGER.warn("[Iteration {}] Skill '{}' failed: {}", iteration + 1, matchedSkill.getName(), e.getMessage(), e);
                }
                if (callback != null) callback.onIteration(iteration + 1, matchedSkill.getName(), observation);
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        mcpServerName, toolCall.getName(), toolCall.getArguments(), observation, iteration + 1));
            } else {
                // ── MCP tool execution ───────────────────────────────────────
                mcpServerName = findMcpServerName(toolCall, mcpTools, mcpServers);
                LOGGER.info("[Iteration {}] >>> Using tool '{}' (server: {}) | args: {}",
                        iteration + 1, toolCall.getName(),
                        mcpServerName != null ? mcpServerName : "unknown",
                        toolCall.getArguments());
                try {
                    observation = executeTool(toolCall, mcpTools, mcpServers);
                    LOGGER.debug("[Iteration {}] Tool '{}' observation: {}", iteration + 1, toolCall.getName(), observation);
                    if (callback != null) callback.onIteration(iteration + 1, toolCall.getName(), observation);
                    toolCallRecords.add(new AgentResponse.ToolCallRecord(
                            mcpServerName != null ? mcpServerName : "unknown",
                            toolCall.getName(), toolCall.getArguments(), observation, iteration + 1));
                } catch (Exception e) {
                    observation = "Tool execution failed: " + e.getMessage();
                    toolFailureCount++;
                    LOGGER.warn("[Iteration {}] Tool '{}' failed: {}", iteration + 1, toolCall.getName(), e.getMessage(), e);
                    if (callback != null) callback.onIteration(iteration + 1, toolCall.getName(), observation);
                    toolCallRecords.add(new AgentResponse.ToolCallRecord(
                            mcpServerName != null ? mcpServerName : "unknown",
                            toolCall.getName(), toolCall.getArguments(), observation, iteration + 1));
                }
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

        List<ToolDefinition> all = new ArrayList<>();
        for (McpServerConfig server : mcpServers) {
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
                                int parentIteration) throws Exception {
        List<String> allowedToolNames = skill.getToolList();
        List<ToolDefinition> skillMcpTools = allowedToolNames.isEmpty() ? allMcpTools
                : allMcpTools.stream()
                        .filter(t -> allowedToolNames.contains(t.getName()))
                        .collect(Collectors.toList());

        String skillInstructions = skill.getInstructions();
        LOGGER.debug("[Skill '{}'] Starting sub-loop | task='{}' | tools=({}): {}",
                skill.getName(), task, skillMcpTools.size(),
                skillMcpTools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

        LlmClient llmClient = LlmClientFactory.create(config);
        List<LlmMessage> skillMessages = new ArrayList<>();
        skillMessages.add(new LlmMessage("user", task));

        String skillAnswer = null;
        int maxSkillIterations = 3;

        for (int i = 0; i < maxSkillIterations; i++) {
            LOGGER.info("[Skill '{}' | Step {}/{}] Sending {} message(s) to LLM",
                    skill.getName(), i + 1, maxSkillIterations, skillMessages.size());
            LlmResponse response = llmClient.chat(skillInstructions, skillMessages, skillMcpTools);

            if (response.hasFinalAnswer()) {
                LOGGER.debug("[Skill '{}' | Step {}/{}] Tokens used | input={} output={} total={}",
                        skill.getName(), i + 1, maxSkillIterations,
                        response.getInputTokens(), response.getOutputTokens(),
                        response.getInputTokens() + response.getOutputTokens());
                skillAnswer = response.getContent();
                LOGGER.debug("[Skill '{}'] Final answer after {} iteration(s).", skill.getName(), i + 1);
                break;
            }

            LOGGER.debug("[Skill '{}' | Step {}/{}] Tokens used | input={} output={} total={}",
                    skill.getName(), i + 1, maxSkillIterations,
                    response.getInputTokens(), response.getOutputTokens(),
                    response.getInputTokens() + response.getOutputTokens());

            ToolCall toolCall = response.getToolCall();
            LOGGER.info("[Skill '{}' | Step {}/{}] >>> Using tool '{}' | args: {}",
                    skill.getName(), i + 1, maxSkillIterations, toolCall.getName(), toolCall.getArguments());
            skillMessages.add(new LlmMessage("assistant", response.getContent(), Collections.singletonList(toolCall)));

            String obs;
            try {
                obs = executeTool(toolCall, skillMcpTools, mcpServers);
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        skill.getName(), toolCall.getName(), toolCall.getArguments(), obs, parentIteration));
            } catch (Exception e) {
                obs = "Tool execution failed: " + e.getMessage();
                LOGGER.warn("[Skill '{}'] Tool '{}' failed: {}", skill.getName(), toolCall.getName(), e.getMessage());
                toolCallRecords.add(new AgentResponse.ToolCallRecord(
                        skill.getName(), toolCall.getName(), toolCall.getArguments(), obs, parentIteration));
            }
            skillMessages.add(new LlmMessage("tool_result", obs, toolCall.getId(), toolCall.getName()));
        }

        if (skillAnswer == null) {
            skillAnswer = "Skill '" + skill.getName() + "' did not return a final answer within "
                    + maxSkillIterations + " iterations.";
        }
        return skillAnswer;
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

    private boolean shouldStopAfterFinalAnswer(List<AgentResponse.ToolCallRecord> toolCalls,
                                               List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return true;
        }
        if (toolCalls == null || toolCalls.isEmpty()) {
            // LLM answered directly without using any tools — stop immediately
            return true;
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
