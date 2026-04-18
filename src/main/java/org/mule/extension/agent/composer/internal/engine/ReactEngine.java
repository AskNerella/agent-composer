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
import org.mule.runtime.api.store.ObjectStoreSettings;
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
     * @param mcpServers          MCP servers to discover tools from
     * @param contextFilter       tool name whitelist (empty = all tools)
     * @param memoryStoreName     Object Store name for conversation history
     * @param conversationId      key scoping this conversation in the Object Store
     * @param maxIterations       maximum Reason-Act cycles before forced exit
     * @param beforeIterationFlow Mule flow executed before each LLM call (null = skip)
     * @param afterIterationFlow  Mule flow executed after each tool observation (null = skip)
     */
    public String run(AgentComposerConfiguration config,
                      String instructions,
                      String userMessage,
                      List<McpServerConfig> mcpServers,
                      List<String> contextFilter,
                      String memoryStoreName,
                      String conversationId,
                      int maxIterations,
                      String beforeIterationFlow,
                      String afterIterationFlow) throws Exception {

        LlmClient llmClient = LlmClientFactory.create(config);

        List<ToolDefinition> tools = discoverTools(mcpServers, contextFilter);
        LOGGER.debug("Tools available: {}", tools.stream().map(ToolDefinition::getName).collect(Collectors.joining(", ")));

        ObjectStore<Serializable> store = objectStoreManager.getOrCreateObjectStore(
                memoryStoreName, ObjectStoreSettings.builder().persistent(true).build());

        List<LlmMessage> messages = loadHistory(store, conversationId);
        messages.add(new LlmMessage("user", userMessage));

        String currentThought = userMessage;
        String finalAnswer = null;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LOGGER.debug("ReAct iteration {}/{}", iteration + 1, maxIterations);

            executeHookFlow(beforeIterationFlow, currentThought);

            LlmResponse response = llmClient.chat(instructions, messages, tools);
            currentThought = response.getContent() != null ? response.getContent() : "";
            LOGGER.debug("LLM stop_reason='{}' hasToolCall={}", response.getStopReason(), response.hasToolCall());

            if (response.hasFinalAnswer()) {
                finalAnswer = currentThought;
                messages.add(new LlmMessage("assistant", finalAnswer));
                saveHistory(store, conversationId, messages);
                break;
            }

            ToolCall toolCall = response.getToolCall();
            messages.add(new LlmMessage("assistant", currentThought, Collections.singletonList(toolCall)));

            String observation;
            try {
                observation = executeTool(toolCall, tools, mcpServers);
                LOGGER.debug("Tool '{}' observation: {}", toolCall.getName(), observation);
            } catch (Exception e) {
                observation = "Tool execution failed: " + e.getMessage();
                LOGGER.warn("Tool '{}' failed: {}", toolCall.getName(), e.getMessage(), e);
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

    private List<ToolDefinition> discoverTools(List<McpServerConfig> mcpServers, List<String> contextFilter) {
        if (mcpServers == null || mcpServers.isEmpty()) return Collections.emptyList();

        List<ToolDefinition> all = new ArrayList<>();
        for (McpServerConfig server : mcpServers) {
            all.addAll(mcpClient.listTools(server));
        }
        if (contextFilter == null || contextFilter.isEmpty()) return all;
        return all.stream().filter(t -> contextFilter.contains(t.getName())).collect(Collectors.toList());
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
