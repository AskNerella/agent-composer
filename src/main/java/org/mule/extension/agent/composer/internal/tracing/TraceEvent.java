package org.mule.extension.agent.composer.internal.tracing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Point-in-time diagnostic attached to a trace span. */
public class TraceEvent {
    private String timestamp;
    private long timestampEpochMs;
    private String type;
    private String message;
    private Map<String, Object> data = new ConcurrentHashMap<>();

    public TraceEvent() {}

    public TraceEvent(String timestamp, long timestampEpochMs, String type,
                      String message, Map<String, Object> data) {
        this.timestamp = timestamp;
        this.timestampEpochMs = timestampEpochMs;
        this.type = type;
        this.message = message;
        if (data != null) this.data.putAll(data);
    }

    public String getTimestamp() { return timestamp; }
    public long getTimestampEpochMs() { return timestampEpochMs; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public Map<String, Object> getData() { return data; }
}
