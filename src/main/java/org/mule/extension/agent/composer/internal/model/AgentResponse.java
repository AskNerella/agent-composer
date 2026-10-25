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
    private boolean resumedSession;
    private int failureCount;
    private String returnReason;
    private boolean cacheHit;
    private String conversationSummary;
    private List<ToolCallRecord> toolCalls = new ArrayList<>();
    /** True when the agent paused and is waiting for the user to supply more information. */
    private boolean requiresInput;
    /** The clarifying question the agent wants answered before it can continue. */
    private String inputRequest;
    /** Distinguishes why input is required: {@code "clarification"} (LLM-initiated) or {@code "elicitation"} (MCP server-initiated, mid-tool-call). */
    private String inputKind;
    /** JSON Schema describing the structured answer expected, populated only for {@code inputKind == "elicitation"}. */
    private Map<String, Object> elicitationSchema;
    /** MCP server that requested the current elicitation. */
    private String elicitationServerName;
    /** MCP tool whose execution is paused. */
    private String elicitationToolName;
    private String permissionRequestId;
    private String permissionServerName;
    private String permissionToolName;
    private Map<String, Object> permissionArguments;
    private Long permissionExpiresAtEpochMs;
    /** JSON Schema for the structured permission decision expected from the caller. */
    private Map<String, Object> responseSchema;
    /** Unique identifier for the execution trace produced by this invocation. */
    private String executionId;
    /** Relative HTTP path for the live HTML execution report. */
    private String traceReportPath;

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
    public boolean isResumedSession() { return resumedSession; }
    public int getFailureCount() { return failureCount; }
    public String getReturnReason() { return returnReason; }
    public boolean isCacheHit() { return cacheHit; }
    public String getConversationSummary() { return conversationSummary; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public boolean isRequiresInput() { return requiresInput; }
    public String getInputRequest() { return inputRequest; }
    public String getInputKind() { return inputKind; }
    public Map<String, Object> getElicitationSchema() { return elicitationSchema; }
    public String getElicitationServerName() { return elicitationServerName; }
    public String getElicitationToolName() { return elicitationToolName; }
    public String getPermissionRequestId() { return permissionRequestId; }
    public String getPermissionServerName() { return permissionServerName; }
    public String getPermissionToolName() { return permissionToolName; }
    public Map<String, Object> getPermissionArguments() { return permissionArguments; }
    public Long getPermissionExpiresAtEpochMs() { return permissionExpiresAtEpochMs; }
    public Map<String, Object> getResponseSchema() { return responseSchema; }
    public String getExecutionId() { return executionId; }
    public String getTraceReportPath() { return traceReportPath; }

    // Setters
    public void setUserTask(String userTask) { this.userTask = userTask; }
    public void setResponse(String response) { this.response = response; }
    public void setTotalIterations(int totalIterations) { this.totalIterations = totalIterations; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setResumedSession(boolean resumedSession) { this.resumedSession = resumedSession; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public void setReturnReason(String returnReason) { this.returnReason = returnReason; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }
    public void setConversationSummary(String conversationSummary) { this.conversationSummary = conversationSummary; }
    public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }
    public void setRequiresInput(boolean requiresInput) { this.requiresInput = requiresInput; }
    public void setInputRequest(String inputRequest) { this.inputRequest = inputRequest; }
    public void setInputKind(String inputKind) { this.inputKind = inputKind; }
    public void setElicitationSchema(Map<String, Object> elicitationSchema) { this.elicitationSchema = elicitationSchema; }
    public void setElicitationServerName(String elicitationServerName) { this.elicitationServerName = elicitationServerName; }
    public void setElicitationToolName(String elicitationToolName) { this.elicitationToolName = elicitationToolName; }
    public void setPermissionRequestId(String value) { this.permissionRequestId = value; }
    public void setPermissionServerName(String value) { this.permissionServerName = value; }
    public void setPermissionToolName(String value) { this.permissionToolName = value; }
    public void setPermissionArguments(Map<String, Object> value) { this.permissionArguments = value; }
    public void setPermissionExpiresAtEpochMs(Long value) { this.permissionExpiresAtEpochMs = value; }
    public void setResponseSchema(Map<String, Object> value) { this.responseSchema = value; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public void setTraceReportPath(String traceReportPath) { this.traceReportPath = traceReportPath; }

    public void addToolCall(ToolCallRecord record) {
        this.toolCalls.add(record);
    }

    /** Returns the HTML of the first tool call that produced an MCP-UI widget, or {@code null}. */
    public String getFirstUiHtml() {
        for (ToolCallRecord r : toolCalls) {
            if (r.getUiHtml() != null && !r.getUiHtml().isEmpty()) return r.getUiHtml();
        }
        return null;
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
        /** URI of the MCP-UI resource returned by this tool call, if any (e.g. {@code ui://...}). */
        private String uiResourceUri;
        /** Rendered HTML of the MCP-UI resource associated with this tool call, if any. */
        private String uiHtml;

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
        public String getUiResourceUri() { return uiResourceUri; }
        public String getUiHtml() { return uiHtml; }

        // Setters
        public void setUiResourceUri(String uiResourceUri) { this.uiResourceUri = uiResourceUri; }
        public void setUiHtml(String uiHtml) { this.uiHtml = uiHtml; }
    }
}
