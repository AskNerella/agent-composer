package org.mule.extension.agent.composer.internal.operations;

import com.google.gson.Gson;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.extension.agent.composer.internal.error.AgentComposerErrorTypeProvider;
import org.mule.extension.agent.composer.internal.model.AgentResponse;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.sdk.api.annotation.error.Throws;
import org.mule.sdk.api.annotation.param.reference.ObjectStoreReference;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.MediaType;

import javax.inject.Inject;
import java.util.List;

import static org.mule.sdk.api.annotation.param.MediaType.APPLICATION_JSON;

public class AgentComposerOperations {

    @Inject
    private ObjectStoreManager objectStoreManager;

    /**
     * Runs a ReAct agent loop: Reason, call tools via MCP, and return the final answer with metrics.
     */
    @MediaType(APPLICATION_JSON)
    @Throws(AgentComposerErrorTypeProvider.class)
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

            @DisplayName("Object Store")
            @Summary("Reference to a Mule Object Store used to persist conversation history.")
            @ObjectStoreReference
            @Optional(defaultValue = "_defaultPersistentObjectStore")
            String objectStore,

            @DisplayName("Conversation ID")
            @Summary("Key that scopes this conversation within the Object Store. Defaults to the Mule correlation ID.")
            @Optional(defaultValue = "#[correlationId]")
            String conversationId,

            @DisplayName("Max Iterations")
            @Summary("Hard cap on Reason-Act cycles before returning a fallback answer.")
            @Optional(defaultValue = "5")
            Integer maxIterations) throws Exception {

        ReactEngine engine = new ReactEngine(objectStoreManager);
        AgentResponse response = engine.run(config, instructions, userMessage, mcpServers,
                objectStore, conversationId, maxIterations);
        return new Gson().toJson(response);
    }
}

