package org.mule.extension.agent.composer.internal.model;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Response model for the {@code executeAgent} operation.
 * Contains the final answer, execution metrics, tool calls history, and session information.
 */
public class AgentResponse {

    private String userTask;
    private String response;
    private int totalIterations;
    private int iterationCount;
    private boolean complete;
    private String sessionId;
    private int failureCount;
    private String returnReason;
    private boolean cacheHit;
    private String conversationSummary;
    private List<ToolCallRecord> toolCalls = new ArrayList<>();

    public AgentResponse() {}

    public AgentResponse(String userTask, String response, int totalIterations, 
                        int iterationCount, boolean complete, String sessionId) {
        this.userTask = userTask;
        this.response = response;
        this.totalIterations = totalIterations;
        this.iterationCount = iterationCount;
        this.complete = complete;
        this.sessionId = sessionId;
    }

    // Getters
    public String getUserTask() { return userTask; }
    public String getResponse() { return response; }
    public int getTotalIterations() { return totalIterations; }
    public int getIterationCount() { return iterationCount; }
    public boolean isComplete() { return complete; }
    public String getSessionId() { return sessionId; }
    public int getFailureCount() { return failureCount; }
    public String getReturnReason() { return returnReason; }
    public boolean isCacheHit() { return cacheHit; }
    public String getConversationSummary() { return conversationSummary; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }

    // Setters
    public void setUserTask(String userTask) { this.userTask = userTask; }
    public void setResponse(String response) { this.response = response; }
    public void setTotalIterations(int totalIterations) { this.totalIterations = totalIterations; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public void setReturnReason(String returnReason) { this.returnReason = returnReason; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }
    public void setConversationSummary(String conversationSummary) { this.conversationSummary = conversationSummary; }
    public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }

    public void addToolCall(ToolCallRecord record) {
        this.toolCalls.add(record);
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    /**
     * Represents a single tool call made during agent execution.
     */
    public static class ToolCallRecord {
        private String mcpClientName;
        private String toolName;
        private Map<String, Object> toolRequest;
        private String toolResponse;
        private int iterationNumber;

        public ToolCallRecord(String mcpClientName, String toolName, 
                            Map<String, Object> toolRequest, String toolResponse,
                            int iterationNumber) {
            this.mcpClientName = mcpClientName;
            this.toolName = toolName;
            this.toolRequest = toolRequest;
            this.toolResponse = toolResponse;
            this.iterationNumber = iterationNumber;
        }

        // Getters
        public String getMcpClientName() { return mcpClientName; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getToolRequest() { return toolRequest; }
        public String getToolResponse() { return toolResponse; }
        public int getIterationNumber() { return iterationNumber; }
    }
}
