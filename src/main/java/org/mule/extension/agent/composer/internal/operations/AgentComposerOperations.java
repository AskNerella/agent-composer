package org.mule.extension.agent.composer.internal.operations;

import com.google.gson.Gson;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.extension.agent.composer.internal.error.AgentComposerErrorTypeProvider;
import org.mule.extension.agent.composer.internal.llm.LlmClient;
import org.mule.extension.agent.composer.internal.llm.LlmClientFactory;
import org.mule.extension.agent.composer.internal.model.AgentResponse;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.extension.api.annotation.metadata.fixed.OutputJsonType;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.sdk.api.annotation.error.Throws;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mule.sdk.api.annotation.param.MediaType.APPLICATION_JSON;

public class AgentComposerOperations {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentComposerOperations.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @Inject
    private ObjectStoreManager objectStoreManager;

    // ── Execute Agent ─────────────────────────────────────────────────────────

    /**
     * Runs a ReAct agent loop: Reason, call tools via MCP, and return the final answer with metrics.
     * Instructions, MCP Servers, and Object Store are read from the connector configuration.
     */
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/execute-agent-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String executeAgent(
            @Config AgentComposerConfiguration config,

            @DisplayName("User Message")
            @Summary("The user's input message for this conversation turn.")
            @Optional(defaultValue = "#[payload]")
            String userMessage,

            @DisplayName("Conversation ID")
            @Summary("Key that scopes this conversation within the Object Store. Defaults to the A2A message.contextId when present, otherwise the Mule correlation ID.")
            @Optional(defaultValue = "#[correlationId]")
            String conversationId,

            @DisplayName("Max Iterations")
            @Summary("Hard cap on Reason-Act cycles before returning a fallback answer.")
            @Optional(defaultValue = "5")
            Integer maxIterations) throws Exception {

        ReactEngine engine = new ReactEngine(objectStoreManager);
        AgentResponse response = engine.run(config, userMessage, conversationId, maxIterations);
        return GSON.toJson(response);
    }

    // ── Evaluate Agent ────────────────────────────────────────────────────────

    /**
     * Evaluates an agent response against the user task using the configured LLM.
     */
    @DisplayName("Evaluate Agent")
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/evaluate-agent-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String evaluateAgent(
            @Config AgentComposerConfiguration config,

            @DisplayName("User Task")
            @Summary("The original task or question provided by the user.")
            @Text
            String userTask,

            @DisplayName("Agent Response")
            @Summary("The agent response to evaluate.")
            @Text
            String agentResponse,

            @DisplayName("Evaluation Criteria")
            @Summary("Optional rubric used to score response quality.")
            @Text
            @Optional(defaultValue = "Evaluate correctness, completeness, relevance, clarity, and actionability.")
            String evaluationCriteria) throws Exception {

        LlmClient llmClient = LlmClientFactory.create(config);

        String systemPrompt = "You evaluate agent responses. Return ONLY a valid JSON object with keys: "
                + "verdict, score, confidence, reason, improvements. "
                + "verdict must be PASS or FAIL. score must be integer 0-100. "
                + "confidence must be integer 0-100. improvements must be an array of strings.";

        String evaluationInput = "User Task:\n" + userTask
                + "\n\nAgent Response:\n" + agentResponse
                + "\n\nEvaluation Criteria:\n" + evaluationCriteria;

        LlmResponse llmEvaluation = llmClient.chat(
                systemPrompt,
                Collections.singletonList(new LlmMessage("user", evaluationInput)),
                Collections.emptyList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userTask", userTask);
        result.put("agentResponse", agentResponse);
        result.put("evaluationCriteria", evaluationCriteria);
        result.put("evaluation", llmEvaluation.getContent());
        result.put("provider", String.valueOf(config.getProvider()));
        result.put("modelName", config.getModelName());

        return GSON.toJson(result);
    }

    // ── Memory Operations ─────────────────────────────────────────────────────

    /**
     * Clears all entries from the object store, resetting the agent memory from scratch.
     */
    @DisplayName("Reset Memory")
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/reset-memory-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String resetMemory(
            @Config AgentComposerConfiguration config) throws Exception {

        ReactEngine engine = new ReactEngine(objectStoreManager);
        Map<String, Object> result = engine.resetMemory(config.getObjectStore());
        return GSON.toJson(result);
    }

    /**
     * Given a user task, searches past agent memories and retrieves any similar past conversations.
     */
    @DisplayName("Check Memory")
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/check-memory-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String checkMemory(
            @Config AgentComposerConfiguration config,

            @DisplayName("User Task")
            @Summary("The task or question to match against past agent memories.")
            @Text
            String userTask,

            @DisplayName("Max Results")
            @Summary("Maximum number of matching memories to return.")
            @Optional(defaultValue = "3")
            Integer maxResults) throws Exception {

        ReactEngine engine = new ReactEngine(objectStoreManager);
        Map<String, Object> result = engine.checkMemory(config, userTask, config.getObjectStore(),
                maxResults != null ? maxResults : 3);
        return GSON.toJson(result);
    }

    /**
     * Retrieves the full conversation history, tool calls, and summary for a given session ID.
     */
    @DisplayName("Retrieve Conversation")
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/retrieve-conversation-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String retrieveConversation(
            @Config AgentComposerConfiguration config,

            @DisplayName("Session ID")
            @Summary("The conversation / session ID whose history should be retrieved.")
            String sessionId) throws Exception {

        ReactEngine engine = new ReactEngine(objectStoreManager);
        Map<String, Object> result = engine.retrieveConversation(sessionId, config.getObjectStore());
        return GSON.toJson(result);
    }

    /**
     * Deletes a conversation from the object store by session ID, by matching user task text, or both.
     */
    @DisplayName("Delete Conversation")
    @MediaType(value = APPLICATION_JSON, strict = false)
    @OutputJsonType(schema = "schemas/delete-conversation-output.json")
    @Throws(AgentComposerErrorTypeProvider.class)
    public String deleteConversation(
            @Config AgentComposerConfiguration config,

            @DisplayName("Session ID")
            @Summary("Session ID of the conversation to delete. Provide this, User Task, or both.")
            @Optional
            String sessionId,

            @DisplayName("User Task")
            @Summary("Deletes all solved memories whose user task contains this text (case-insensitive).")
            @Optional
            String userTask) throws Exception {

        if ((sessionId == null || sessionId.trim().isEmpty())
                && (userTask == null || userTask.trim().isEmpty())) {
            throw new IllegalArgumentException("At least one of 'Session ID' or 'User Task' must be provided.");
        }

        ReactEngine engine = new ReactEngine(objectStoreManager);
        Map<String, Object> result = engine.deleteConversation(sessionId, userTask, config.getObjectStore());
        return GSON.toJson(result);
    }
}
