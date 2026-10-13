package org.mule.extension.agent.composer.internal.tracing;

import com.google.gson.Gson;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
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
