package org.mule.extension.agent.composer.internal.source;

import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.extension.agent.composer.internal.model.AgentIterationEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Process-local bridge between an Agent Listener SSE request and Execute Agent in the Mule flow. */
public final class StreamingEventRegistry {
    private static final ConcurrentHashMap<String, Consumer<AgentIterationEvent>> SINKS = new ConcurrentHashMap<>();

    private StreamingEventRegistry() {}

    public static void register(String conversationId, Consumer<AgentIterationEvent> sink) {
        if (conversationId != null && sink != null) SINKS.put(conversationId, sink);
    }

    public static ReactEngine.IterationCallback callback(String conversationId) {
        Consumer<AgentIterationEvent> sink = conversationId != null ? SINKS.get(conversationId) : null;
        return sink != null ? sink::accept : null;
    }

    public static void unregister(String conversationId) {
        if (conversationId != null) SINKS.remove(conversationId);
    }

    static void clear() { SINKS.clear(); }
}
