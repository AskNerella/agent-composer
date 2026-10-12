package org.mule.extension.agent.composer.internal.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable continuation state for one agent turn paused by MCP form elicitation.
 * Stored as JSON in the configured Mule Object Store.
 */
public class PendingElicitationState {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private String rootUserTask;
    private int maxIterations;
    private int actualIterations;
    private int failureCount;
    private boolean resumedSession;
    private List<AgentResponse.ToolCallRecord> toolCallRecords = new ArrayList<>();
    private List<Continuation> queue = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getRootUserTask() { return rootUserTask; }
    public void setRootUserTask(String rootUserTask) { this.rootUserTask = rootUserTask; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getActualIterations() { return actualIterations; }
    public void setActualIterations(int actualIterations) { this.actualIterations = actualIterations; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public boolean isResumedSession() { return resumedSession; }
    public void setResumedSession(boolean resumedSession) { this.resumedSession = resumedSession; }
    public List<AgentResponse.ToolCallRecord> getToolCallRecords() { return toolCallRecords; }
    public void setToolCallRecords(List<AgentResponse.ToolCallRecord> toolCallRecords) {
        this.toolCallRecords = toolCallRecords != null ? new ArrayList<>(toolCallRecords) : new ArrayList<>();
    }
    public List<Continuation> getQueue() { return queue; }
    public void setQueue(List<Continuation> queue) {
        this.queue = queue != null ? new ArrayList<>(queue) : new ArrayList<>();
    }

    public static class Continuation {
        private McpToolResult.ElicitationRequest request;
        private ToolCall outerToolCall;
        private int parentIteration;
        private SkillState skillState;

        public McpToolResult.ElicitationRequest getRequest() { return request; }
        public void setRequest(McpToolResult.ElicitationRequest request) { this.request = request; }
        public ToolCall getOuterToolCall() { return outerToolCall; }
        public void setOuterToolCall(ToolCall outerToolCall) { this.outerToolCall = outerToolCall; }
        public int getParentIteration() { return parentIteration; }
        public void setParentIteration(int parentIteration) { this.parentIteration = parentIteration; }
        public SkillState getSkillState() { return skillState; }
        public void setSkillState(SkillState skillState) { this.skillState = skillState; }
        public boolean isSkill() { return skillState != null; }
    }

    public static class SkillState {
        private String skillName;
        private String task;
        private List<LlmMessage> messages = new ArrayList<>();
        private int nextStep;
        private int maxSteps;
        private int consecutiveTextResponses;
        private int tokensSinceCompression;

        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
        public List<LlmMessage> getMessages() { return messages; }
        public void setMessages(List<LlmMessage> messages) {
            this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        }
        public int getNextStep() { return nextStep; }
        public void setNextStep(int nextStep) { this.nextStep = nextStep; }
        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public int getConsecutiveTextResponses() { return consecutiveTextResponses; }
        public void setConsecutiveTextResponses(int consecutiveTextResponses) {
            this.consecutiveTextResponses = consecutiveTextResponses;
        }
        public int getTokensSinceCompression() { return tokensSinceCompression; }
        public void setTokensSinceCompression(int tokensSinceCompression) {
            this.tokensSinceCompression = tokensSinceCompression;
        }
    }
}
