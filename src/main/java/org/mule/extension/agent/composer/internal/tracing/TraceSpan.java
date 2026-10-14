package org.mule.extension.agent.composer.internal.tracing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A timed unit of work within an {@link ExecutionTrace}. */
public class TraceSpan {
    private String spanId;
    private String parentSpanId;
    private String type;
    private String name;
    private String status;
    private String startedAt;
    private String endedAt;
    private long startedAtEpochMs;
    private long endedAtEpochMs;
    private long durationMs;
    private String threadName;
    private Integer iteration;
    private Object input;
    private Object output;
    private int inputTokens;
    private int outputTokens;
    private int retryCount;
    private String errorType;
    private String errorMessage;
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
    private List<TraceEvent> events = new CopyOnWriteArrayList<>();

    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getStartedAt() { return startedAt; }
    public String getEndedAt() { return endedAt; }
    public long getStartedAtEpochMs() { return startedAtEpochMs; }
    public long getEndedAtEpochMs() { return endedAtEpochMs; }
    public long getDurationMs() { return durationMs; }
    public String getThreadName() { return threadName; }
    public Integer getIteration() { return iteration; }
    public Object getInput() { return input; }
    public Object getOutput() { return output; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getRetryCount() { return retryCount; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public Map<String, Object> getAttributes() { return attributes; }
    public List<TraceEvent> getEvents() { return events; }

    public void setSpanId(String spanId) { this.spanId = spanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    public void setType(String type) { this.type = type; }
    public void setName(String name) { this.name = name; }
    public void setStatus(String status) { this.status = status; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public void setEndedAt(String endedAt) { this.endedAt = endedAt; }
    public void setStartedAtEpochMs(long startedAtEpochMs) { this.startedAtEpochMs = startedAtEpochMs; }
    public void setEndedAtEpochMs(long endedAtEpochMs) { this.endedAtEpochMs = endedAtEpochMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public void setThreadName(String threadName) { this.threadName = threadName; }
    public void setIteration(Integer iteration) { this.iteration = iteration; }
    public void setInput(Object input) { this.input = input; }
    public void setOutput(Object output) { this.output = output; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? new ConcurrentHashMap<>(attributes) : new ConcurrentHashMap<>();
    }
    public void setEvents(List<TraceEvent> events) {
        this.events = events != null ? new CopyOnWriteArrayList<>(events) : new CopyOnWriteArrayList<>();
    }

    public int getTotalTokens() { return inputTokens + outputTokens; }
}
