package org.mule.extension.agent.composer.internal.tracing;

import com.google.gson.Gson;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** In-memory live trace registry backed by the configured Mule Object Store. */
public final class ExecutionTraceStore {
    public static final String KEY_PREFIX = "agent-trace::";
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionTraceStore.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_LIVE_TRACES = 500;
    private static final Map<String, ExecutionTrace> LIVE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> ORDER = new ConcurrentLinkedDeque<>();

    private ExecutionTraceStore() {}

    static void register(ExecutionTrace trace, String objectStoreName) {
        String scopedId = scopedId(objectStoreName, trace.getExecutionId());
        LIVE.put(scopedId, trace);
        ORDER.remove(scopedId);
        ORDER.addFirst(scopedId);
        while (ORDER.size() > MAX_LIVE_TRACES) {
            String oldest = ORDER.pollLast();
            if (oldest != null) LIVE.remove(oldest);
        }
    }

    static void persist(ExecutionTrace trace, ObjectStore<Serializable> store, String objectStoreName) {
        register(trace, objectStoreName);
        if (store == null) return;
        String key = KEY_PREFIX + trace.getExecutionId();
        try {
            String json = toJson(trace);
            synchronized (store) {
                if (store.contains(key)) store.remove(key);
                store.store(key, json);
            }
            trace.setPersistenceError(null);
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            trace.setPersistenceError(message);
            LOGGER.warn("Could not persist execution trace '{}': {}", trace.getExecutionId(), message, e);
        }
    }

    public static ExecutionTrace get(String executionId, ObjectStoreManager manager,
                                     String objectStoreName) {
        if (executionId == null || executionId.trim().isEmpty()) return null;
        ExecutionTrace live = LIVE.get(scopedId(objectStoreName, executionId));
        if (live != null) return live;
        if (manager == null) return null;
        try {
            ObjectStore<Serializable> store = manager.getObjectStore(objectStoreName);
            String key = KEY_PREFIX + executionId;
            if (!store.contains(key)) return null;
            ExecutionTrace trace = GSON.fromJson((String) store.retrieve(key), ExecutionTrace.class);
            if (trace != null) register(trace, objectStoreName);
            return trace;
        } catch (Exception e) {
            LOGGER.warn("Could not load execution trace '{}': {}", executionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Loads an execution together with every earlier/later execution that belongs to the same
     * conversation. Permission and elicitation resumes create a new execution checkpoint; the
     * trace report presents those checkpoints as one continuous timeline.
     */
    public static ExecutionTrace getCorrelated(String executionId, ObjectStoreManager manager,
                                               String objectStoreName) {
        ExecutionTrace selected = get(executionId, manager, objectStoreName);
        if (selected == null || selected.getConversationId() == null
                || selected.getConversationId().trim().isEmpty()) {
            return selected;
        }
        List<ExecutionTrace> correlated = new ArrayList<>();
        for (ExecutionTrace trace : list(manager, objectStoreName, 200)) {
            if (selected.getConversationId().equals(trace.getConversationId())) correlated.add(trace);
        }
        return combine(correlated);
    }

    static ExecutionTrace combine(List<ExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) return null;
        if (traces.size() == 1) return traces.get(0);

        List<ExecutionTrace> ordered = new ArrayList<>(traces);
        ordered.sort(Comparator.comparingLong(ExecutionTrace::getStartedAtEpochMs));
        ExecutionTrace first = ordered.get(0);
        ExecutionTrace latest = ordered.get(ordered.size() - 1);
        ExecutionTrace combined = new ExecutionTrace();
        combined.setVersion(ExecutionTrace.CURRENT_VERSION);
        combined.setExecutionId(latest.getExecutionId());
        combined.setConversationId(latest.getConversationId());
        combined.setUserTask(first.getUserTask());
        combined.setProvider(latest.getProvider());
        combined.setModelName(latest.getModelName());
        combined.setMaxIterations(latest.getMaxIterations());
        combined.setStatus(latest.getStatus());
        combined.setStartedAt(first.getStartedAt());
        combined.setStartedAtEpochMs(first.getStartedAtEpochMs());
        combined.setEndedAt(latest.getEndedAt());

        long end = ordered.stream().mapToLong(ExecutionTrace::getEndedAtEpochMs).max().orElse(0L);
        combined.setEndedAtEpochMs(end);
        combined.setDurationMs(end > first.getStartedAtEpochMs()
                ? end - first.getStartedAtEpochMs()
                : ordered.stream().mapToLong(ExecutionTrace::getDurationMs).sum());
        combined.setInputTokens(ordered.stream().mapToInt(ExecutionTrace::getInputTokens).sum());
        combined.setOutputTokens(ordered.stream().mapToInt(ExecutionTrace::getOutputTokens).sum());
        combined.setRetryCount(ordered.stream().mapToInt(ExecutionTrace::getRetryCount).sum());
        combined.setErrorCount(ordered.stream().mapToInt(ExecutionTrace::getErrorCount).sum());
        combined.setError(latest.getError());
        combined.setPersistenceError(latest.getPersistenceError());

        Map<String, Object> attributes = new LinkedHashMap<>();
        List<String> executionIds = new ArrayList<>();
        List<TraceSpan> spans = new ArrayList<>();
        for (ExecutionTrace trace : ordered) {
            attributes.putAll(trace.getAttributes());
            executionIds.add(trace.getExecutionId());
            spans.addAll(trace.snapshotSpans());
        }
        attributes.put("executionIds", executionIds);
        attributes.put("executionCount", ordered.size());
        spans.sort(Comparator.comparingLong(TraceSpan::getStartedAtEpochMs));
        combined.setAttributes(attributes);
        combined.setSpans(spans);
        return combined;
    }

    public static List<ExecutionTrace> list(ObjectStoreManager manager, String objectStoreName,
                                            int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Map<String, ExecutionTrace> traces = new ConcurrentHashMap<>();
        String scopePrefix = normalizeNamespace(objectStoreName) + "::";
        for (String id : ORDER) {
            if (!id.startsWith(scopePrefix)) continue;
            ExecutionTrace trace = LIVE.get(id);
            if (trace != null) traces.put(trace.getExecutionId(), trace);
            if (traces.size() >= safeLimit) break;
        }
        if (manager != null && traces.size() < safeLimit) {
            try {
                ObjectStore<Serializable> store = manager.getObjectStore(objectStoreName);
                for (String key : store.allKeys()) {
                    if (!key.startsWith(KEY_PREFIX)) continue;
                    String id = key.substring(KEY_PREFIX.length());
                    if (traces.containsKey(id)) continue;
                    ExecutionTrace trace = GSON.fromJson((String) store.retrieve(key), ExecutionTrace.class);
                    if (trace != null) traces.put(id, trace);
                }
            } catch (Exception e) {
                LOGGER.warn("Could not list persisted execution traces: {}", e.getMessage(), e);
            }
        }
        List<ExecutionTrace> result = new ArrayList<>(traces.values());
        result.sort(Comparator.comparingLong(ExecutionTrace::getStartedAtEpochMs).reversed());
        if (result.size() > safeLimit) return new ArrayList<>(result.subList(0, safeLimit));
        return result;
    }

    public static String toJson(ExecutionTrace trace) {
        return TraceRedactor.toRedactedJson(trace);
    }

    /** Marks a pending permission trace as expired so the report immediately removes its actions. */
    public static void expirePermission(String conversationId, long waitMs,
                                        ObjectStoreManager manager, String objectStoreName) {
        if (conversationId == null || conversationId.trim().isEmpty()) return;
        for (ExecutionTrace trace : list(manager, objectStoreName, 200)) {
            if (!conversationId.equals(trace.getConversationId())
                    || !ExecutionTraceContext.STATUS_INPUT_REQUIRED.equals(trace.getStatus())) continue;
            long now = System.currentTimeMillis();
            trace.setStatus("EXPIRED");
            trace.setEndedAtEpochMs(now);
            trace.setEndedAt(Instant.ofEpochMilli(now).toString());
            trace.setDurationMs(Math.max(trace.getDurationMs(), now - trace.getStartedAtEpochMs()));
            Map<String, Object> expired = new LinkedHashMap<>();
            expired.put("inputKind", "tool_permission");
            expired.put("decision", "expired");
            expired.put("approvalWaitMs", Math.max(0L, waitMs));
            Object previous = trace.getAttributes().get("result");
            if (previous instanceof Map) expired.putAll((Map<String, Object>) previous);
            expired.put("decision", "expired");
            expired.put("approvalWaitMs", Math.max(0L, waitMs));
            trace.getAttributes().put("result", expired);
            for (TraceSpan span : trace.getSpans()) {
                if (ExecutionTraceContext.STATUS_INPUT_REQUIRED.equals(span.getStatus())) {
                    span.setStatus("EXPIRED");
                    span.setOutput(expired);
                }
            }
            try {
                ObjectStore<Serializable> store = manager != null
                        ? manager.getObjectStore(objectStoreName) : null;
                persist(trace, store, objectStoreName);
            } catch (Exception e) {
                LOGGER.warn("Could not persist expired permission trace '{}': {}",
                        trace.getExecutionId(), e.getMessage(), e);
            }
        }
    }

    /** Adds transport metadata to an existing trace and checkpoints it immediately. */
    public static void attribute(String executionId, String key, Object value,
                                 ObjectStoreManager manager, String objectStoreName) {
        ExecutionTrace trace = get(executionId, manager, objectStoreName);
        if (trace == null || key == null) return;
        trace.getAttributes().put(key, TraceRedactor.redact(value));
        try {
            ObjectStore<Serializable> store = manager != null
                    ? manager.getObjectStore(objectStoreName) : null;
            persist(trace, store, objectStoreName);
        } catch (Exception e) {
            LOGGER.warn("Could not checkpoint trace metadata for '{}': {}", executionId, e.getMessage(), e);
        }
    }

    /** Removes all retained traces in the configured namespace. */
    public static int clear(ObjectStoreManager manager, String objectStoreName) {
        int removed = 0;
        String scopePrefix = normalizeNamespace(objectStoreName) + "::";
        for (String scoped : new ArrayList<>(ORDER)) {
            if (!scoped.startsWith(scopePrefix)) continue;
            ORDER.remove(scoped);
            if (LIVE.remove(scoped) != null) removed++;
        }
        if (manager == null) return removed;
        try {
            ObjectStore<Serializable> store = manager.getObjectStore(objectStoreName);
            for (String key : new ArrayList<>(store.allKeys())) {
                if (!key.startsWith(KEY_PREFIX)) continue;
                store.remove(key);
                removed++;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not clear persisted execution traces: {}", e.getMessage(), e);
        }
        return removed;
    }

    private static String scopedId(String objectStoreName, String executionId) {
        return normalizeNamespace(objectStoreName) + "::" + executionId;
    }

    private static String normalizeNamespace(String objectStoreName) {
        return objectStoreName == null || objectStoreName.trim().isEmpty()
                ? "_defaultPersistentObjectStore" : objectStoreName.trim();
    }

    static void clearLiveForTests() {
        LIVE.clear();
        ORDER.clear();
    }
}
