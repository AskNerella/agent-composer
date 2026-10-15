package org.mule.extension.agent.composer.internal.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable continuation for an agent turn paused before a permission-gated MCP tool call. */
public class PendingToolPermissionState {
    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private String rootUserTask;
    private int maxIterations;
    private int actualIterations;
    private int failureCount;
    private boolean resumedSession;
    private String originExecutionId;
    private List<LlmMessage> messages = new ArrayList<>();
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
    public String getOriginExecutionId() { return originExecutionId; }
    public void setOriginExecutionId(String originExecutionId) { this.originExecutionId = originExecutionId; }
    public List<LlmMessage> getMessages() { return messages; }
    public void setMessages(List<LlmMessage> messages) { this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>(); }
    public List<AgentResponse.ToolCallRecord> getToolCallRecords() { return toolCallRecords; }
    public void setToolCallRecords(List<AgentResponse.ToolCallRecord> records) { this.toolCallRecords = records != null ? new ArrayList<>(records) : new ArrayList<>(); }
    public List<Continuation> getQueue() { return queue; }
    public void setQueue(List<Continuation> queue) { this.queue = queue != null ? new ArrayList<>(queue) : new ArrayList<>(); }

    public static class Continuation {
        private String permissionRequestId;
        private String serverName;
        private String serverUrl;
        private ToolCall toolCall;
        private ToolCall outerToolCall;
        private int parentIteration;
        private SkillState skillState;

        public String getPermissionRequestId() { return permissionRequestId; }
        public void setPermissionRequestId(String value) { this.permissionRequestId = value; }
        public String getServerName() { return serverName; }
        public void setServerName(String value) { this.serverName = value; }
        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String value) { this.serverUrl = value; }
        public ToolCall getToolCall() { return toolCall; }
        public void setToolCall(ToolCall value) { this.toolCall = value; }
        public ToolCall getOuterToolCall() { return outerToolCall != null ? outerToolCall : toolCall; }
        public void setOuterToolCall(ToolCall value) { this.outerToolCall = value; }
        public int getParentIteration() { return parentIteration; }
        public void setParentIteration(int value) { this.parentIteration = value; }
        public SkillState getSkillState() { return skillState; }
        public void setSkillState(SkillState value) { this.skillState = value; }
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
        public void setSkillName(String value) { this.skillName = value; }
        public String getTask() { return task; }
        public void setTask(String value) { this.task = value; }
        public List<LlmMessage> getMessages() { return messages; }
        public void setMessages(List<LlmMessage> value) { this.messages = value != null ? new ArrayList<>(value) : new ArrayList<>(); }
        public int getNextStep() { return nextStep; }
        public void setNextStep(int value) { this.nextStep = value; }
        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int value) { this.maxSteps = value; }
        public int getConsecutiveTextResponses() { return consecutiveTextResponses; }
        public void setConsecutiveTextResponses(int value) { this.consecutiveTextResponses = value; }
        public int getTokensSinceCompression() { return tokensSinceCompression; }
        public void setTokensSinceCompression(int value) { this.tokensSinceCompression = value; }
    }
}
