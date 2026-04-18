package org.mule.extension.agent.composer.internal.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.llm.LlmClient;
import org.mule.extension.agent.composer.internal.llm.LlmClientFactory;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.extension.agent.composer.internal.model.ToolCall;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.mule.runtime.api.artifact.Registry;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.event.EventContextService;
import org.mule.runtime.core.internal.event.DefaultEventContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements the <strong>ReAct (Reason + Act)</strong> agent loop.
 *
 * <p>Each iteration follows this sequence:
 * <ol>
 *   <li><b>Pre-Step</b> – look up {@code beforeIterationFlow} in the Mule registry and
 *       execute it, passing the current "Thought" string as the message payload.</li>
 *   <li><b>Inference</b> – call the LLM with the system prompt, conversation memory,
 *       and the (filtered) set of tool definitions.</li>
 *   <li><b>Action</b> – if the model returns a {@code tool_use} response, locate the
 *       owning MCP server, execute the tool, and capture the result as the
 *       "Observation".</li>
 *   <li><b>Post-Step</b> – execute {@code afterIterationFlow} with the Observation.</li>
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

    private final Registry registry;
    private final ObjectStoreManager objectStoreManager;
    private final EventContextService eventContextService;
    private final McpClient mcpClient;

    public ReactEngine(Registry registry, ObjectStoreManager objectStoreManager, EventContextService eventContextService) {
        this.registry = registry;
        this.objectStoreManager = objectStoreManager;
        this.eventContextService = eventContextService;
        this.mcpClient = new McpClient();
    }

    // ── main entry point ──────────────────────────────────────────────────────

    /**
     * Runs the ReAct loop and returns the LLM's final answer.
     *
     * @param config              LLM configuration (provider, model, key, etc.)
     * @param instructions        system prompt
     * @param userMessage         the user's input for this turn
     * @param mcpServers          MCP servers to discover tools from (each carries its own tool filter)
     * @param objectStoreName     name of the Object Store used to persist conversation history
     * @param conversationId      key scoping this conversation in the Object Store
     * @param maxIterations       maximum Reason-Act cycles before forced exit
     * @param beforeIterationFlow Mule flow executed before each LLM call (null = skip)
     * @param afterIterationFlow  Mule flow executed after each tool observation (null = skip)
     */
    public String run(AgentComposerConfiguration config,
                      String instructions,
                      String userMessage,
                      List<McpServerConfig> mcpServers,
                      String objectStoreName,
                      String conversationId,
                      int maxIterations,
                      String beforeIterationFlow,
                      String afterIterationFlow) throws Exception {

        LlmClient llmClient = LlmClientFactory.create(config);

        List<ToolDefinition> tools = discoverTools(mcpServers);
        LOGGER.info("Tools available ({}): {}", tools.size(),
                tools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

        ObjectStore<Serializable> store = objectStoreManager.getObjectStore(objectStoreName);

        List<LlmMessage> messages = loadHistory(store, conversationId);
        messages.add(new LlmMessage("user", userMessage));

        String currentThought = userMessage;
        String finalAnswer = null;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LOGGER.info("--- ReAct Iteration {}/{} | conversationId='{}' ---", iteration + 1, maxIterations, conversationId);
            LOGGER.info("[Iteration {}] What am I trying to do? {}", iteration + 1, currentThought);

            executeHookFlow(beforeIterationFlow, currentThought);

            LOGGER.info("[Iteration {}] Sending request to LLM | provider={} model={}", iteration + 1, config.getProvider(), config.getModelName());
            LOGGER.info("[Iteration {}] Instructions: {}", iteration + 1, instructions);
            LOGGER.info("[Iteration {}] User prompt: {}", iteration + 1, userMessage);
            LOGGER.info("[Iteration {}] Tools in use ({}): {}", iteration + 1, tools.size(),
                    tools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

            LlmResponse response = llmClient.chat(instructions, messages, tools);
            currentThought = response.getContent() != null ? response.getContent() : "";

            LOGGER.info("[Iteration {}] LLM response | stop_reason='{}' hasToolCall={}", iteration + 1, response.getStopReason(), response.hasToolCall());
            if (!currentThought.isEmpty()) {
                LOGGER.info("[Iteration {}] LLM content: {}", iteration + 1, currentThought);
            }

            if (response.hasFinalAnswer()) {
                finalAnswer = currentThought;
                LOGGER.info("[Iteration {}] Final answer received.", iteration + 1);
                messages.add(new LlmMessage("assistant", finalAnswer));
                saveHistory(store, conversationId, messages);
                break;
            }

            ToolCall toolCall = response.getToolCall();
            LOGGER.info("[Iteration {}] Tool call requested: name='{}' args={}", iteration + 1, toolCall.getName(), toolCall.getArguments());
            messages.add(new LlmMessage("assistant", currentThought, Collections.singletonList(toolCall)));

            String observation;
            try {
                observation = executeTool(toolCall, tools, mcpServers);
                LOGGER.info("[Iteration {}] Tool '{}' observation: {}", iteration + 1, toolCall.getName(), observation);
            } catch (Exception e) {
                observation = "Tool execution failed: " + e.getMessage();
                LOGGER.warn("[Iteration {}] Tool '{}' failed: {}", iteration + 1, toolCall.getName(), e.getMessage(), e);
            }

            executeHookFlow(afterIterationFlow, observation);
            messages.add(new LlmMessage("tool_result", observation, toolCall.getId(), toolCall.getName()));
            saveHistory(store, conversationId, messages);
        }

        if (finalAnswer == null) {
            finalAnswer = "Maximum iterations (" + maxIterations + ") reached without a final answer.";
            LOGGER.warn("ReAct loop hit maxIterations={} for conversationId={}", maxIterations, conversationId);
            saveHistory(store, conversationId, messages);
        }

        return finalAnswer;
    }

    // ── tool discovery ────────────────────────────────────────────────────────

    private List<ToolDefinition> discoverTools(List<McpServerConfig> mcpServers) {
        if (mcpServers == null || mcpServers.isEmpty()) return Collections.emptyList();

        List<ToolDefinition> all = new ArrayList<>();
        for (McpServerConfig server : mcpServers) {
            LOGGER.info("Fetching MCP tools for client '{}' from {}", server.getName(), server.getServerUrl());
            List<ToolDefinition> serverTools = mcpClient.listTools(server);
            List<String> filter = server.getToolFilters();
            if (filter != null && !filter.isEmpty()) {
                serverTools = serverTools.stream()
                        .filter(t -> filter.contains(t.getName()))
                        .collect(Collectors.toList());
            }
            LOGGER.info("Fetched {} tool(s) for client '{}': {}", serverTools.size(), server.getName(),
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

    // ── lifecycle hooks ───────────────────────────────────────────────────────

    /**
     * Looks up the named flow from the registry and executes it with {@code payload} as the message.
     * Failures are logged but do not abort the ReAct loop.
     */
    private void executeHookFlow(String flowName, Object payload) {
        if (flowName == null || flowName.trim().isEmpty()) return;
        try {
            Object flowObj = registry.lookupByName(flowName).orElse(null);
            if (flowObj == null) {
                LOGGER.warn("Hook flow '{}' not found in registry; skipping.", flowName);
                return;
            }
            if (!(flowObj instanceof Flow)) {
                LOGGER.warn("Registry entry '{}' is not a Flow; skipping.", flowName);
                return;
            }
            Flow flow = (Flow) flowObj;
            Message message = Message.builder().value(payload).build();
            DefaultEventContext eventContext = new DefaultEventContext(
                    (FlowConstruct) flow, eventContextService, flow.getLocation(),
                    UUID.randomUUID().toString(), Optional.empty());
            CoreEvent event = CoreEvent.builder(eventContext).message(message).build();
            flow.process(event);
            LOGGER.debug("Executed hook flow '{}'", flowName);
        } catch (Exception e) {
            LOGGER.warn("Error executing hook flow '{}': {}", flowName, e.getMessage(), e);
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
