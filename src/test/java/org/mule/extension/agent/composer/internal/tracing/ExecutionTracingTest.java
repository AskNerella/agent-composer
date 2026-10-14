package org.mule.extension.agent.composer.internal.tracing;

import org.junit.After;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreManager;

import java.lang.reflect.Field;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ExecutionTracingTest {

    @After
    public void clearRegistry() {
        ExecutionTraceStore.clearLiveForTests();
    }

    @Test
    public void recordsNestedSpansTokensRetriesAndJsonExport() throws Exception {
        AgentComposerConfiguration config = config("tenant-a");
        ExecutionTraceContext context = ExecutionTraceContext.start(
                null, config, "Create a ticket", "conversation-1", 20);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("authorization", "Bearer top-secret-token");
        ExecutionTraceContext.SpanScope iteration = context.startSpan(
                "ITERATION", "Iteration 1", null, 1, Collections.singletonMap("request", request));
        ExecutionTraceContext.SpanScope llm = context.startSpan(
                "LLM", "Reasoning", iteration.getSpanId(), 1, Collections.singletonMap("apiKey", "sk-1234567890123456"));

        llm.incrementRetry("temporary failure");
        LlmResponse response = new LlmResponse("done", null, "end_turn");
        response.setInputTokens(11);
        response.setOutputTokens(7);
        llm.tokens(response);
        llm.success(response);
        iteration.success(Collections.singletonMap("answer", "done"));
        context.finish(ExecutionTraceContext.STATUS_COMPLETED, Collections.singletonMap("complete", true));

        ExecutionTrace trace = context.getTrace();
        assertEquals(ExecutionTraceContext.STATUS_COMPLETED, trace.getStatus());
        assertEquals(18, trace.getTotalTokens());
        assertEquals(1, trace.getRetryCount());
        assertEquals(2, trace.getSpans().size());
        assertEquals(iteration.getSpanId(), trace.getSpans().get(1).getParentSpanId());

        String json = ExecutionTraceStore.toJson(trace);
        assertFalse(json.contains("top-secret-token"));
        assertFalse(json.contains("sk-1234567890123456"));
        assertTrue(json.contains("[REDACTED]"));

        assertSame(trace, ExecutionTraceStore.get(trace.getExecutionId(), null, "tenant-a"));
        assertNull(ExecutionTraceStore.get(trace.getExecutionId(), null, "tenant-b"));
    }

    @Test
    public void htmlEscapesDataShowsControlsAndRefreshesRunningTrace() throws Exception {
        AgentComposerConfiguration config = config("tenant-html");
        ExecutionTraceContext context = ExecutionTraceContext.start(
                null, config, "<script>alert('x')</script>", "conversation-html", 5);
        ExecutionTraceContext.SpanScope span = context.startSpan(
                "LLM", "Unsafe <name>", null, 1,
                Collections.singletonMap("password", "never-render-me"));
        ExecutionTraceContext.SpanScope objectStore = context.startSpan(
                "OBJECT_STORE", "Load conversation", null, null,
                Collections.singletonMap("conversationId", "conversation-html"));
        objectStore.success(Collections.singletonMap("source", "conversation-cache"));

        String runningHtml = TraceHtmlRenderer.render(context.getTrace(), "/agent/traces");
        assertTrue(runningHtml.contains("\\u003cscript"));
        assertFalse(runningHtml.contains("<script>alert('x')</script>"));
        assertFalse(runningHtml.contains("never-render-me"));
        assertFalse(runningHtml.contains("Rerun"));
        assertFalse(runningHtml.contains("Edit &amp; Rerun"));
        assertFalse(runningHtml.contains("Replay"));
        assertFalse(runningHtml.contains(">Logs<"));
        assertFalse(runningHtml.contains(">Metrics<"));
        assertFalse(runningHtml.contains(">Evaluations<"));
        assertTrue(runningHtml.contains("setTimeout(()=>location.reload(),2000)"));
        assertTrue(runningHtml.contains("Export JSON"));
        assertTrue(runningHtml.contains("Agent Composer"));
        assertTrue(runningHtml.contains("/agent/assets/agent-composer.svg"));
        assertTrue(runningHtml.contains("Correlation ID &middot; conversation-html"));
        assertTrue(runningHtml.contains("id=\"loopDiagram\""));
        assertTrue(runningHtml.contains("mermaid@11"));
        assertTrue(runningHtml.contains("buildLoopDiagram"));
        assertTrue(runningHtml.contains("Load conversation"));
        assertTrue(runningHtml.contains("OBJECT_STORE"));
        assertTrue(runningHtml.contains("lucide-database"));
        assertTrue(runningHtml.contains("Stop task"));
        assertTrue(runningHtml.contains("action=cancel"));
        assertTrue(runningHtml.contains("Trace Timeline"));
        assertTrue(runningHtml.contains("class=\"track\""));
        assertTrue(runningHtml.contains("selectSpan("));
        assertTrue(runningHtml.contains("detailMetrics"));
        assertTrue(runningHtml.contains("detail-drawer"));
        assertTrue(runningHtml.contains("closeDrawer()"));
        assertTrue(runningHtml.contains("Span table"));
        assertTrue(runningHtml.contains("<th>Status</th><th>Kind</th><th>Name</th><th>Input</th><th>Output</th><th>Error</th><th>Start Time</th><th>Latency</th>"));
        assertTrue(runningHtml.contains("agent-trace-theme"));
        assertTrue(runningHtml.contains("lucide-moon"));
        assertTrue(runningHtml.contains("--primary:#ff385c"));

        String indexHtml = TraceHtmlRenderer.renderIndex(
                Collections.singletonList(context.getTrace()), "/agent/traces");
        assertFalse(indexHtml.contains("<span>Trace</span>"));
        assertTrue(indexHtml.contains("<span>Correlation / Trace ID</span>"));
        assertFalse(indexHtml.contains("<code>" + context.getExecutionId().substring(0, 8)));

        span.success("safe");
        context.finish(ExecutionTraceContext.STATUS_COMPLETED, "done");
        String completedHtml = TraceHtmlRenderer.render(context.getTrace(), "/agent/traces");
        assertFalse(completedHtml.contains("setTimeout(()=>location.reload(),2000)"));
    }

    @Test
    public void usesTraceIdWhenCorrelationIdIsMissingWithoutDuplicatingIt() throws Exception {
        AgentComposerConfiguration config = config("tenant-trace-id");
        ExecutionTraceContext context = ExecutionTraceContext.start(null, config, "task", null, 5);
        String html = TraceHtmlRenderer.render(context.getTrace(), "/agent/traces");

        assertTrue(html.contains("Trace ID &middot; " + context.getExecutionId()));
        assertFalse(html.contains("Correlation ID &middot; null"));
        assertFalse(html.contains("<span>Trace ID</span><strong>" + context.getExecutionId()));
    }

    @Test
    public void redactsNestedCredentialKeysAndInlineBearerTokens() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("clientSecret", "client-secret-value");
        nested.put("message", "Authorization: Bearer abc.def-123");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("headers", nested);

        String json = new com.google.gson.Gson().toJson(TraceRedactor.redact(input));
        assertFalse(json.contains("client-secret-value"));
        assertFalse(json.contains("abc.def-123"));
        assertTrue(json.contains("REDACTED"));
    }

    @Test
    public void roundTripsCompletedTraceThroughObjectStore() throws Exception {
        Map<String, Serializable> memory = new HashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        ObjectStoreManager manager = mock(ObjectStoreManager.class);
        when(manager.getObjectStore("persist-store")).thenReturn(store);

        ExecutionTraceContext context = ExecutionTraceContext.start(
                manager, config("persist-store"), "Persist this trace", "conversation-persist", 20);
        context.finish(ExecutionTraceContext.STATUS_COMPLETED, Collections.singletonMap("ok", true));
        String executionId = context.getExecutionId();
        assertTrue(memory.containsKey(ExecutionTraceStore.KEY_PREFIX + executionId));

        ExecutionTraceStore.clearLiveForTests();
        ExecutionTrace restored = ExecutionTraceStore.get(executionId, manager, "persist-store");
        assertNotNull(restored);
        assertEquals(ExecutionTraceContext.STATUS_COMPLETED, restored.getStatus());
        assertEquals(20, restored.getMaxIterations());
        assertEquals("conversation-persist", restored.getConversationId());
    }

    private static AgentComposerConfiguration config(String objectStore) throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "provider", LlmProvider.OPENAI);
        setField(config, "modelName", "test-model");
        setField(config, "objectStore", objectStore);
        setField(config, "agentPath", "/agent");
        return config;
    }

    @SuppressWarnings("unchecked")
    private static ObjectStore<Serializable> mockStore(Map<String, Serializable> memory) throws Exception {
        ObjectStore<Serializable> store = mock(ObjectStore.class);
        when(store.contains(anyString())).thenAnswer(i -> memory.containsKey(i.getArgument(0)));
        when(store.retrieve(anyString())).thenAnswer(i -> memory.get(i.getArgument(0)));
        doAnswer(i -> {
            memory.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(store).store(anyString(), any(Serializable.class));
        when(store.remove(anyString())).thenAnswer(i -> memory.remove(i.getArgument(0)));
        return store;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
