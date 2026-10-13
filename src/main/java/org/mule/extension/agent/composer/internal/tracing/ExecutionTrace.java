package org.mule.extension.agent.composer.internal.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Versioned, JSON-serializable record of one Agent Composer invocation.
 * Spans are stored in start order and linked through {@code parentSpanId} so
 * parallel tool calls and nested skill loops can be reconstructed reliably.
 */
public class ExecutionTrace {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private String executionId;
    private String conversationId;
    private String userTask;
    private String provider;
    private String modelName;
    private int maxIterations;
    private String status;
    private String startedAt;
    private String endedAt;
    private long startedAtEpochMs;
    private long endedAtEpochMs;
    private long durationMs;
    private int inputTokens;
    private int outputTokens;
    private int retryCount;
    private int errorCount;
    private String error;
    private String persistenceError;
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
    private List<TraceSpan> spans = new CopyOnWriteArrayList<>();

    public int getVersion() { return version; }
    public String getExecutionId() { return executionId; }
    public String getConversationId() { return conversationId; }
    public String getUserTask() { return userTask; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public int getMaxIterations() { return maxIterations; }
    public String getStatus() { return status; }
    public String getStartedAt() { return startedAt; }
    public String getEndedAt() { return endedAt; }
    public long getStartedAtEpochMs() { return startedAtEpochMs; }
    public long getEndedAtEpochMs() { return endedAtEpochMs; }
    public long getDurationMs() { return durationMs; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getRetryCount() { return retryCount; }
    public int getErrorCount() { return errorCount; }
    public String getError() { return error; }
    public String getPersistenceError() { return persistenceError; }
    public Map<String, Object> getAttributes() { return attributes; }
    public List<TraceSpan> getSpans() { return spans; }

    public void setVersion(int version) { this.version = version; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setUserTask(String userTask) { this.userTask = userTask; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public void setStatus(String status) { this.status = status; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public void setEndedAt(String endedAt) { this.endedAt = endedAt; }
    public void setStartedAtEpochMs(long startedAtEpochMs) { this.startedAtEpochMs = startedAtEpochMs; }
    public void setEndedAtEpochMs(long endedAtEpochMs) { this.endedAtEpochMs = endedAtEpochMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public void setError(String error) { this.error = error; }
    public void setPersistenceError(String persistenceError) { this.persistenceError = persistenceError; }
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? new ConcurrentHashMap<>(attributes) : new ConcurrentHashMap<>();
    }
    public void setSpans(List<TraceSpan> spans) {
        this.spans = spans != null ? new CopyOnWriteArrayList<>(spans) : new CopyOnWriteArrayList<>();
    }

    public int getTotalTokens() { return inputTokens + outputTokens; }

    public List<TraceSpan> snapshotSpans() { return new ArrayList<>(spans); }
}
