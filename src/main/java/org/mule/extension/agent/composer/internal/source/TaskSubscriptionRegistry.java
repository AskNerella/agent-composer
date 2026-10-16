package org.mule.extension.agent.composer.internal.source;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Process-local fan-out for reconnectable A2A task subscriptions. */
final class TaskSubscriptionRegistry {
    private static final Map<String, CopyOnWriteArrayList<Consumer<String>>> SUBSCRIBERS =
            new ConcurrentHashMap<>();

    private TaskSubscriptionRegistry() {}

    static Runnable register(String taskId, Consumer<String> subscriber) {
        SUBSCRIBERS.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> {
            List<Consumer<String>> subscribers = SUBSCRIBERS.get(taskId);
            if (subscribers == null) return;
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) SUBSCRIBERS.remove(taskId, subscribers);
        };
    }

    static void publish(String taskId, String event) {
        List<Consumer<String>> subscribers = SUBSCRIBERS.get(taskId);
        if (subscribers == null) return;
        for (Consumer<String> subscriber : subscribers) subscriber.accept(event);
    }

    static void clear() {
        SUBSCRIBERS.clear();
    }
}
