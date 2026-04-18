package org.mule.extension.agent.composer.internal.operations;

import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.llm.OpenAiLlmClient;
import org.mule.runtime.api.artifact.Registry;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.core.api.event.EventContextService;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.MediaType;

import javax.inject.Inject;
import java.util.List;

import static org.mule.sdk.api.annotation.param.MediaType.ANY;

public class AgentComposerOperations {

    @Inject
    private Registry registry;

    @Inject
    private ObjectStoreManager objectStoreManager;

    @Inject
    private EventContextService eventContextService;

    /**
     * Runs a ReAct agent loop: Reason, call tools via MCP, and return the final answer.
     */
    @MediaType(ANY)
    public String executeAgent(
            @Config AgentComposerConfiguration config,

            @DisplayName("User Message")
            @Summary("The user's input message for this conversation turn.")
            String userMessage,

            @DisplayName("Instructions")
            @Summary("System prompt / persona prepended to every LLM call.")
            @Text
            String instructions,

            @DisplayName("MCP Servers")
            @Summary("MCP server endpoints whose tools are offered to the LLM.")
            @Optional
            List<McpServerConfig> mcpServers,

            @DisplayName("Context Filter (Tool Whitelist)")
            @Summary("Tool names to include. Leave empty to allow all discovered tools.")
            @Optional
            List<String> contextFilters,

            @DisplayName("Memory Store Name")
            @Summary("Name of the Mule Object Store used to persist conversation history.")
            @Optional(defaultValue = "agent-memory")
            String memoryStoreName,

            @DisplayName("Conversation ID")
            @Summary("Key that scopes this conversation within the Object Store.")
            @Optional(defaultValue = "default")
            String conversationId,

            @DisplayName("Max Iterations")
            @Summary("Hard cap on Reason-Act cycles before returning a fallback answer.")
            @Optional(defaultValue = "5")
            Integer maxIterations,

            @DisplayName("Before Iteration Flow")
            @Summary("Name of a Mule flow executed before each LLM call. Receives the current Thought as payload.")
            @Optional
            String beforeIterationFlow,

            @DisplayName("After Iteration Flow")
            @Summary("Name of a Mule flow executed after each tool observation. Receives the Observation as payload.")
            @Optional
            String afterIterationFlow) throws Exception {

        ReactEngine engine = new ReactEngine(registry, objectStoreManager, eventContextService);
        return engine.run(config, instructions, userMessage, mcpServers, contextFilters,
                memoryStoreName, conversationId, maxIterations, beforeIterationFlow, afterIterationFlow);
    }

    /**
     * Returns the list of model IDs available from the OpenAI API.
     * Only applicable when the configured provider is OPENAI.
     */
    @MediaType(ANY)
    public List<String> listModels(@Config AgentComposerConfiguration config) throws Exception {
        if (config.getProvider() != LlmProvider.OPENAI) {
            throw new UnsupportedOperationException("listModels is only supported for the OPENAI provider.");
        }
        return new OpenAiLlmClient(config).listModels();
    }
}

