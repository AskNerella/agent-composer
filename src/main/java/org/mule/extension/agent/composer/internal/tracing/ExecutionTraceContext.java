package org.mule.extension.agent.composer.internal.tracing;

import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe recorder used by a single ReAct invocation. */
public final class ExecutionTraceContext {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_INPUT_REQUIRED = "INPUT_REQUIRED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";

    private final ExecutionTrace trace;
    private final ObjectStore<Serializable> store;
    private final String objectStoreName;
    private final Object persistenceLock = new Object();

    private ExecutionTraceContext(ExecutionTrace trace, ObjectStore<Serializable> store,
                                  String objectStoreName) {
        this.trace = trace;
        this.store = store;
        this.objectStoreName = objectStoreName;
    }

    public static ExecutionTraceContext start(ObjectStoreManager manager,
                                              AgentComposerConfiguration config,
                                              String userTask,
                                              String conversationId,
                                              int maxIterations) {
        ExecutionTrace trace = new ExecutionTrace();
        trace.setExecutionId(UUID.randomUUID().toString());
        trace.setConversationId(conversationId);
        trace.setUserTask(TraceRedactor.redactText(userTask));
        trace.setProvider(config.getProvider() != null ? config.getProvider().name() : null);
        trace.setModelName(config.getModelName());
        trace.setMaxIterations(maxIterations);
        trace.setStatus(STATUS_RUNNING);
        long now = System.currentTimeMillis();
        trace.setStartedAtEpochMs(now);
        trace.setStartedAt(Instant.ofEpochMilli(now).toString());

        ObjectStore<Serializable> objectStore = null;
        if (manager != null) {
            try {
                objectStore = manager.getObjectStore(config.getObjectStore());
            } catch (Exception e) {
                trace.setPersistenceError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        ExecutionTraceContext context = new ExecutionTraceContext(trace, objectStore, config.getObjectStore());
        context.persist();
        return context;
    }

    public ExecutionTrace getTrace() { return trace; }
    public String getExecutionId() { return trace.getExecutionId(); }

    public SpanScope startSpan(String type, String name, String parentSpanId,
                               Integer iteration, Object input, Map<String, Object> attributes) {
        TraceSpan span = new TraceSpan();
        span.setSpanId(UUID.randomUUID().toString());
        span.setParentSpanId(parentSpanId);
        span.setType(type);
        span.setName(name);
        span.setStatus(STATUS_RUNNING);
        long now = System.currentTimeMillis();
        span.setStartedAtEpochMs(now);
        span.setStartedAt(Instant.ofEpochMilli(now).toString());
        span.setThreadName(Thread.currentThread().getName());
        span.setIteration(iteration);
        span.setInput(TraceRedactor.redact(input));
        if (attributes != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> safe = (Map<String, Object>) TraceRedactor.redact(attributes);
            span.setAttributes(safe);
        }
        trace.getSpans().add(span);
        persist();
        return new SpanScope(this, span);
    }

    public SpanScope startSpan(String type, String name, String parentSpanId,
                               Integer iteration, Object input) {
        return startSpan(type, name, parentSpanId, iteration, input, Collections.emptyMap());
    }

    public void attribute(String key, Object value) {
        trace.getAttributes().put(key, TraceRedactor.redact(value));
        persist();
    }

    public void finish(String status, Object output) {
        trace.setStatus(status);
        trace.getAttributes().put("result", TraceRedactor.redact(output));
        finishOpenSpans(status, null);
        finishClock();
        persist();
    }

    public void fail(Throwable error) {
        trace.setStatus(STATUS_FAILED);
        trace.setErrorCount(Math.max(1, trace.getErrorCount()));
        trace.setError(error != null
                ? TraceRedactor.redactText(error.getClass().getSimpleName() + ": " + error.getMessage())
                : "Unknown error");
        finishOpenSpans(STATUS_FAILED, error);
        finishClock();
        persist();
    }

    private void finishOpenSpans(String status, Throwable error) {
        long now = System.currentTimeMillis();
        for (TraceSpan span : trace.getSpans()) {
            if (!STATUS_RUNNING.equals(span.getStatus())) continue;
            span.setStatus(status);
            span.setEndedAtEpochMs(now);
            span.setEndedAt(Instant.ofEpochMilli(now).toString());
            span.setDurationMs(Math.max(0, now - span.getStartedAtEpochMs()));
            if (error != null) {
                span.setErrorType(error.getClass().getName());
                span.setErrorMessage(TraceRedactor.redactText(error.getMessage()));
            }
        }
    }

    private void finishClock() {
        long now = System.currentTimeMillis();
        trace.setEndedAtEpochMs(now);
        trace.setEndedAt(Instant.ofEpochMilli(now).toString());
        trace.setDurationMs(Math.max(0, now - trace.getStartedAtEpochMs()));
    }

    void persist() {
        if (STATUS_RUNNING.equals(trace.getStatus())) {
            long now = System.currentTimeMillis();
            trace.setDurationMs(Math.max(0, now - trace.getStartedAtEpochMs()));
            for (TraceSpan span : trace.getSpans()) {
                if (STATUS_RUNNING.equals(span.getStatus())) {
                    span.setDurationMs(Math.max(0, now - span.getStartedAtEpochMs()));
                }
            }
        }
        synchronized (persistenceLock) {
            ExecutionTraceStore.persist(trace, store, objectStoreName);
        }
    }

    /** Mutable scope whose terminal method may safely be called only once. */
    public static final class SpanScope implements AutoCloseable {
        private final ExecutionTraceContext context;
        private final TraceSpan span;
        private final AtomicBoolean ended = new AtomicBoolean(false);

        private SpanScope(ExecutionTraceContext context, TraceSpan span) {
            this.context = context;
            this.span = span;
        }

        public String getSpanId() { return span.getSpanId(); }
        public TraceSpan getSpan() { return span; }

        public void event(String type, String message, Map<String, Object> data) {
            long now = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            Map<String, Object> safe = data == null ? Collections.emptyMap()
                    : (Map<String, Object>) TraceRedactor.redact(data);
            span.getEvents().add(new TraceEvent(Instant.ofEpochMilli(now).toString(), now,
                    type, TraceRedactor.redactText(message), safe));
            context.persist();
        }

        public void incrementRetry(String reason) {
            span.setRetryCount(span.getRetryCount() + 1);
            context.trace.setRetryCount(context.trace.getRetryCount() + 1);
            event("retry", reason, Collections.singletonMap("attempt", span.getRetryCount() + 1));
        }

        public void tokens(LlmResponse response) {
            if (response == null) return;
            span.setInputTokens(response.getInputTokens());
            span.setOutputTokens(response.getOutputTokens());
            context.trace.setInputTokens(context.trace.getInputTokens() + response.getInputTokens());
            context.trace.setOutputTokens(context.trace.getOutputTokens() + response.getOutputTokens());
        }

        public void success(Object output) { end(STATUS_COMPLETED, output, null); }
        public void inputRequired(Object output) { end(STATUS_INPUT_REQUIRED, output, null); }

        public void error(Throwable error) {
            end(STATUS_FAILED, null, error);
        }

        private void end(String status, Object output, Throwable error) {
            if (!ended.compareAndSet(false, true)) return;
            span.setStatus(status);
            span.setOutput(TraceRedactor.redact(output));
            long now = System.currentTimeMillis();
            span.setEndedAtEpochMs(now);
            span.setEndedAt(Instant.ofEpochMilli(now).toString());
            span.setDurationMs(Math.max(0, now - span.getStartedAtEpochMs()));
            if (error != null) {
                span.setErrorType(error.getClass().getName());
                span.setErrorMessage(TraceRedactor.redactText(error.getMessage()));
                context.trace.setErrorCount(context.trace.getErrorCount() + 1);
            }
            context.persist();
        }

        @Override
        public void close() {
            if (!ended.get()) success(null);
        }
    }

    public static Map<String, Object> llmInput(String instructions, Object messages, Object tools) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("instructions", instructions);
        input.put("messages", messages);
        input.put("tools", tools);
        return input;
    }
}
