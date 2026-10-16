package org.mule.extension.agent.composer.internal.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.engine.ReactEngine;
import org.mule.extension.agent.composer.internal.model.AgentIterationEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AgentListenerElicitationMetadataTest {

    @Test
    public void recognizesVersionSpecificTaskListingAndSubscriptionMethods() throws Exception {
        Method list = AgentListenerSource.class.getDeclaredMethod(
                "isTaskListRequest", String.class, String.class);
        Method subscribe = AgentListenerSource.class.getDeclaredMethod(
                "isTaskSubscribeRequest", String.class, String.class);
        list.setAccessible(true);
        subscribe.setAccessible(true);

        assertEquals(Boolean.TRUE, list.invoke(null, "ListTasks", "1.0"));
        assertEquals(Boolean.FALSE, list.invoke(null, "tasks/list", "0.3"));
        assertEquals(Boolean.TRUE, subscribe.invoke(null, "tasks/resubscribe", "0.3"));
        assertEquals(Boolean.TRUE, subscribe.invoke(null, "SubscribeToTask", "1.0"));
    }

    @Test
    public void inputRequiredTaskContainsTextAndStructuredDataParts() throws Exception {
        Method buildTask = AgentListenerSource.class.getDeclaredMethod("buildTask",
                String.class, String.class, String.class, String.class, String.class, String.class);
        buildTask.setAccessible(true);
        JsonObject task = (JsonObject) buildTask.invoke(null,
                "task-1", "ctx-1", "input-required", "Which cabin?", null, null);

        JsonObject response = new JsonObject();
        response.addProperty("inputKind", "elicitation");
        response.addProperty("elicitationServerName", "travel");
        response.addProperty("elicitationToolName", "book_trip");
        response.addProperty("executionId", "execution-1");
        response.addProperty("traceReportPath", "/agent/traces?executionId=execution-1");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        response.add("elicitationSchema", schema);

        Method addMetadata = AgentListenerSource.class.getDeclaredMethod(
                "addInputRequiredMetadata", JsonObject.class, JsonObject.class);
        addMetadata.setAccessible(true);
        addMetadata.invoke(null, task, response);

        JsonArray parts = task.getAsJsonObject("status").getAsJsonObject("message").getAsJsonArray("parts");
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("data", parts.get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals("travel", parts.get(1).getAsJsonObject().getAsJsonObject("data")
                .get("elicitationServerName").getAsString());
        assertEquals("execution-1", parts.get(1).getAsJsonObject().getAsJsonObject("data")
                .get("executionId").getAsString());
    }

    @Test
    public void toolPermissionTaskContainsExactCallMetadata() throws Exception {
        Method buildTask = AgentListenerSource.class.getDeclaredMethod("buildTask",
                String.class, String.class, String.class, String.class, String.class, String.class);
        buildTask.setAccessible(true);
        JsonObject task = (JsonObject) buildTask.invoke(null,
                "task-2", "ctx-2", "input-required", "Approve delete?", null, null);
        JsonObject response = new JsonObject();
        response.addProperty("inputKind", "tool_permission");
        response.addProperty("permissionRequestId", "permission-1");
        response.addProperty("permissionServerName", "tickets");
        response.addProperty("permissionToolName", "delete_ticket");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("id", "T-42");
        response.add("permissionArguments", arguments);
        JsonObject responseSchema = new JsonObject();
        responseSchema.addProperty("type", "object");
        response.add("responseSchema", responseSchema);

        Method addMetadata = AgentListenerSource.class.getDeclaredMethod(
                "addInputRequiredMetadata", JsonObject.class, JsonObject.class);
        addMetadata.setAccessible(true);
        addMetadata.invoke(null, task, response);

        JsonObject data = task.getAsJsonObject("status").getAsJsonObject("message")
                .getAsJsonArray("parts").get(1).getAsJsonObject().getAsJsonObject("data");
        assertEquals("tool_permission", data.get("inputKind").getAsString());
        assertEquals("permission-1", data.get("permissionRequestId").getAsString());
        assertEquals("T-42", data.getAsJsonObject("permissionArguments").get("id").getAsString());
        assertEquals("object", data.getAsJsonObject("responseSchema").get("type").getAsString());
    }

    @Test
    public void iterationUpdateIsNonFinalAndContainsStructuredData() throws Exception {
        Method method = AgentListenerSource.class.getDeclaredMethod("buildIterationSseEvent",
                String.class, String.class, AgentIterationEvent.class, boolean.class, String.class);
        method.setAccessible(true);
        String sse = (String) method.invoke(null, "task-1", "ctx-1",
                AgentIterationEvent.iterationComplete(3, "planned and executed"), true, "7");

        assertTrue(sse.startsWith("data: "));
        JsonObject rpc = com.google.gson.JsonParser.parseString(
                sse.substring("data: ".length()).trim()).getAsJsonObject();
        JsonObject update = rpc.getAsJsonObject("result");
        assertFalse(update.get("final").getAsBoolean());
        assertEquals("working", update.getAsJsonObject("status").get("state").getAsString());
        JsonArray parts = update.getAsJsonObject("status").getAsJsonObject("message").getAsJsonArray("parts");
        assertEquals("iteration_complete", parts.get(1).getAsJsonObject()
                .getAsJsonObject("data").get("eventKind").getAsString());
        assertEquals(3, parts.get(1).getAsJsonObject().getAsJsonObject("data")
                .get("iteration").getAsInt());
    }

    @Test
    public void missingStreamingContextIsInjectedBeforeFlowExecution() throws Exception {
        Method method = AgentListenerSource.class.getDeclaredMethod(
                "ensureMessageContextId", String.class, String.class);
        method.setAccessible(true);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}}";
        String updated = (String) method.invoke(null, body, "generated-context");
        JsonObject root = com.google.gson.JsonParser.parseString(updated).getAsJsonObject();
        assertEquals("generated-context", root.getAsJsonObject("params").getAsJsonObject("message")
                .get("contextId").getAsString());
    }

    @Test
    public void registryDeliversEachEventUntilUnregistered() {
        StreamingEventRegistry.clear();
        java.util.List<AgentIterationEvent> received = new java.util.ArrayList<>();
        StreamingEventRegistry.register("ctx-1", received::add);
        ReactEngine.IterationCallback callback = StreamingEventRegistry.callback("ctx-1");
        assertNotNull(callback);
        callback.onEvent(AgentIterationEvent.toolCall(1, "search", java.util.Collections.emptyMap(), "mcp"));
        callback.onEvent(AgentIterationEvent.iterationComplete(1, "done"));
        assertEquals(2, received.size());
        StreamingEventRegistry.unregister("ctx-1");
        assertNull(StreamingEventRegistry.callback("ctx-1"));
    }

    @Test
    public void permissionDataPartBecomesFlowInputWithoutChangingA2aContinuationIds() throws Exception {
        Method method = AgentListenerSource.class.getDeclaredMethod(
                "normalizeMessageForFlow", String.class, String.class, String.class);
        method.setAccessible(true);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"resume-1\",\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m-2\",\"taskId\":\"task-1\","
                + "\"contextId\":\"ctx-1\",\"role\":\"user\",\"parts\":[{\"kind\":\"data\","
                + "\"data\":{\"action\":\"approve\",\"permissionRequestId\":\"permission-1\"}}]}}}";

        JsonObject normalized = com.google.gson.JsonParser.parseString(
                (String) method.invoke(null, body, "ctx-1", "task-1")).getAsJsonObject();
        JsonObject message = normalized.getAsJsonObject("params").getAsJsonObject("message");
        assertEquals("task-1", message.get("taskId").getAsString());
        assertEquals("ctx-1", message.get("contextId").getAsString());
        JsonObject decision = com.google.gson.JsonParser.parseString(
                message.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString())
                .getAsJsonObject();
        assertEquals("approve", decision.get("action").getAsString());
        assertEquals("permission-1", decision.get("permissionRequestId").getAsString());
    }

    @Test
    public void v1SendMessageResponseUsesTaskEnvelopeAndProtoEnums() throws Exception {
        Method buildTask = AgentListenerSource.class.getDeclaredMethod("buildTask",
                String.class, String.class, String.class, String.class, String.class, String.class);
        buildTask.setAccessible(true);
        JsonObject task = (JsonObject) buildTask.invoke(null,
                "task-1", "ctx-1", "input-required", "Approve?", null, null);
        Method wrap = AgentListenerSource.class.getDeclaredMethod("wrapTaskResponse",
                JsonObject.class, boolean.class, String.class, String.class);
        wrap.setAccessible(true);

        JsonObject rpc = com.google.gson.JsonParser.parseString(
                (String) wrap.invoke(null, task, true, "\"request-1\"", "1.0")).getAsJsonObject();
        JsonObject wireTask = rpc.getAsJsonObject("result").getAsJsonObject("task");
        assertEquals("TASK_STATE_INPUT_REQUIRED",
                wireTask.getAsJsonObject("status").get("state").getAsString());
        assertEquals("ROLE_AGENT", wireTask.getAsJsonObject("status")
                .getAsJsonObject("message").get("role").getAsString());
        assertFalse(wireTask.has("kind"));
    }

    @Test
    public void versionSpecificMethodNamesAreEnforced() throws Exception {
        Method send = AgentListenerSource.class.getDeclaredMethod(
                "isMessageSendRequest", String.class, String.class);
        send.setAccessible(true);
        assertTrue((Boolean) send.invoke(null, "message/send", "0.3"));
        assertFalse((Boolean) send.invoke(null, "tasks/respond", "0.3"));
        assertFalse((Boolean) send.invoke(null, "SendMessage", "0.3"));
        assertTrue((Boolean) send.invoke(null, "SendMessage", "1.0"));
        assertFalse((Boolean) send.invoke(null, "message/send", "1.0"));
    }

    @Test
    public void versionSpecificMessageRolesAreValidated() throws Exception {
        Method validate = AgentListenerSource.class.getDeclaredMethod(
                "validateMessageRequest", JsonObject.class, String.class);
        validate.setAccessible(true);
        JsonObject v03 = com.google.gson.JsonParser.parseString("{\"params\":{\"message\":{"
                + "\"messageId\":\"m-1\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}}")
                .getAsJsonObject();
        JsonObject v10 = com.google.gson.JsonParser.parseString("{\"params\":{\"message\":{"
                + "\"messageId\":\"m-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}}}")
                .getAsJsonObject();
        assertNull(validate.invoke(null, v03, "0.3"));
        assertNull(validate.invoke(null, v10, "1.0"));
        assertNotNull(validate.invoke(null, v03, "1.0"));
    }

    @Test
    public void cancelCleanupRemovesPendingPermissionAndElicitationForContext() throws Exception {
        Map<String, Serializable> values = new LinkedHashMap<>();
        values.put("tool:permission:ctx-1", "permission");
        values.put("mcp:elicitation:ctx-1", "elicitation");
        values.put("conversation:ctx-1", "history");
        org.mule.runtime.api.store.ObjectStore<Serializable> store = mock(org.mule.runtime.api.store.ObjectStore.class);
        when(store.contains(anyString())).thenAnswer(invocation -> values.containsKey(invocation.getArgument(0)));
        doAnswer(invocation -> values.remove(invocation.getArgument(0))).when(store).remove(anyString());
        org.mule.runtime.api.store.ObjectStoreManager manager = mock(org.mule.runtime.api.store.ObjectStoreManager.class);
        when(manager.getObjectStore(anyString())).thenReturn(store);
        org.mule.extension.agent.composer.internal.AgentComposerConfiguration config =
                mock(org.mule.extension.agent.composer.internal.AgentComposerConfiguration.class);
        when(config.getObjectStore()).thenReturn("test-store");

        AgentListenerSource source = new AgentListenerSource();
        setField(source, "objectStoreManager", manager);
        setField(source, "config", config);
        Method cleanup = AgentListenerSource.class.getDeclaredMethod("removePendingInputState", String.class);
        cleanup.setAccessible(true);
        cleanup.invoke(source, "ctx-1");

        assertFalse(values.containsKey("tool:permission:ctx-1"));
        assertFalse(values.containsKey("mcp:elicitation:ctx-1"));
        assertTrue(values.containsKey("conversation:ctx-1"));
    }

    @Test
    public void expiredPermissionCheckpointMarksTaskExpiredAndRemovesContinuation() throws Exception {
        Map<String, Serializable> values = new LinkedHashMap<>();
        values.put("tool:permission:ctx-expired",
                "{\"expiresAtEpochMs\":" + (System.currentTimeMillis() - 1000) + "}");
        org.mule.runtime.api.store.ObjectStore<Serializable> store = mock(org.mule.runtime.api.store.ObjectStore.class);
        when(store.contains(anyString())).thenAnswer(invocation -> values.containsKey(invocation.getArgument(0)));
        when(store.retrieve(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> values.remove(invocation.getArgument(0))).when(store).remove(anyString());
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(store).store(anyString(), any(Serializable.class));

        AgentListenerSource source = new AgentListenerSource();
        Method expire = AgentListenerSource.class.getDeclaredMethod(
                "expirePermissionTaskIfNeeded", JsonObject.class,
                org.mule.runtime.api.store.ObjectStore.class);
        expire.setAccessible(true);
        JsonObject task = new JsonObject();
        task.addProperty("id", "task-expired");
        task.addProperty("contextId", "ctx-expired");
        JsonObject status = new JsonObject();
        status.addProperty("state", "input-required");
        task.add("status", status);

        JsonObject expired = (JsonObject) expire.invoke(source, task, store);

        assertEquals("expired", expired.getAsJsonObject("status").get("state").getAsString());
        assertTrue(values.containsKey("tool:permission:ctx-expired"));
        assertTrue(values.containsKey("a2a:task:task-expired"));
    }

    @Test
    public void expiredIsATerminalTaskState() throws Exception {
        Method terminal = AgentListenerSource.class.getDeclaredMethod("isTerminalState", String.class);
        terminal.setAccessible(true);
        assertEquals(Boolean.TRUE, terminal.invoke(null, "expired"));
        assertEquals(Boolean.FALSE, terminal.invoke(null, "input-required"));
    }

    @Test
    public void activePermissionCheckpointCanRecoverAStaleTerminalSnapshot() throws Exception {
        Method accepts = AgentListenerSource.class.getDeclaredMethod(
                "canAcceptInput", String.class, boolean.class);
        accepts.setAccessible(true);
        assertEquals(Boolean.TRUE, accepts.invoke(null, "input-required", false));
        assertEquals(Boolean.TRUE, accepts.invoke(null, "completed", true));
        assertEquals(Boolean.FALSE, accepts.invoke(null, "completed", false));
        assertEquals(Boolean.FALSE, accepts.invoke(null, "expired", false));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
