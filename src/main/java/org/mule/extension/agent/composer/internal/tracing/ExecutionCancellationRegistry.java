package org.mule.extension.agent.composer.internal.tracing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local registry used by the trace console's emergency stop control. */
public final class ExecutionCancellationRegistry {
    private static final Map<String, Thread> RUNNING = new ConcurrentHashMap<>();

    private ExecutionCancellationRegistry() {}

    public static void register(String executionId) {
        if (executionId != null) RUNNING.put(executionId, Thread.currentThread());
    }

    public static void unregister(String executionId) {
        if (executionId != null) RUNNING.remove(executionId);
    }

    public static boolean cancel(String executionId) {
        Thread thread = executionId == null ? null : RUNNING.get(executionId);
        if (thread == null) return false;
        thread.interrupt();
        return true;
    }

    public static boolean isRunning(String executionId) {
        return executionId != null && RUNNING.containsKey(executionId);
    }
}
