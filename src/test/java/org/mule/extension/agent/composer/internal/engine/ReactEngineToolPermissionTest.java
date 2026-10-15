package org.mule.extension.agent.composer.internal.engine;

import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.AgentResponse;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.McpToolResult;
import org.mule.extension.agent.composer.internal.model.PendingToolPermissionState;
import org.mule.extension.agent.composer.internal.model.ToolCall;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.mule.runtime.api.store.ObjectStore;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReactEngineToolPermissionTest {
    @Test
    public void directPermissionGatePausesBeforeCallingMcp() throws Exception {
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        McpServerConfig server = new McpServerConfig();
        set(server, "name", "tickets");
        set(server, "serverUrl", "http://example.invalid/mcp");
        set(server, "toolsRequiringPermission", "delete_ticket");
        ToolDefinition tool = new ToolDefinition("delete_ticket", "Deletes a ticket",
                Collections.emptyMap(), "http://example.invalid/mcp");
        ToolCall call = new ToolCall("call-1", "delete_ticket",
                Collections.singletonMap("id", "T-42"));
        Method method = ReactEngine.class.getDeclaredMethod("executeToolOrSkill",
                ToolCall.class, List.class, List.class, AgentComposerConfiguration.class,
                List.class, String.class, int.class, int.class,
                ReactEngine.IterationCallback.class, String.class);
        method.setAccessible(true);
        Object outcome = method.invoke(engine, call, Collections.singletonList(tool),
                Collections.singletonList(server), new AgentComposerConfiguration(),
                Collections.emptyList(), "delete it", 1, 4, null, null);
        Field permission = outcome.getClass().getDeclaredField("permission");
        permission.setAccessible(true);
        PendingToolPermissionState.Continuation continuation =
                (PendingToolPermissionState.Continuation) permission.get(outcome);
        assertNotNull(continuation);
        assertEquals("tickets", continuation.getServerName());
        assertEquals("T-42", continuation.getToolCall().getArguments().get("id"));
        verifyNoInteractions(mcp);
    }

    @Test
    public void approveExecutesPersistedCallExactlyOnceAndClearsState() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        PendingToolPermissionState state = state();
        memory.put("tool:permission:conversation-1", new com.google.gson.Gson().toJson(state));
        when(mcp.callTool(any(), eq("delete_ticket"), anyMap(), isNull()))
                .thenReturn(McpToolResult.CallOutcome.completed(new McpToolResult("deleted")));

        Object result = invoke(engine, store, state,
                "{\"action\":\"approve\",\"permissionRequestId\":\"permission-1\"}");

        assertEquals("deleted", resultMessages(result).get(2).getContent());
        assertFalse(memory.containsKey("tool:permission:conversation-1"));
        verify(mcp, times(1)).callTool(any(), eq("delete_ticket"), anyMap(), isNull());
    }

    @Test
    public void denyDoesNotExecuteToolAndContinuesWithDeterministicResult() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        PendingToolPermissionState state = state();

        Object result = invoke(engine, store, state,
                "{\"action\":\"deny\",\"permissionRequestId\":\"permission-1\"}");

        assertEquals("Tool call denied by user.", resultMessages(result).get(2).getContent());
        verifyNoInteractions(mcp);
        assertEquals(0, state.getFailureCount());
    }

    @Test
    public void mismatchedRequestIdRemainsPending() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        PendingToolPermissionState state = state();

        Object result = invoke(engine, store, state,
                "{\"action\":\"approve\",\"permissionRequestId\":\"stale\"}");

        AgentResponse response = resultResponse(result);
        assertNotNull(response);
        assertTrue(response.isRequiresInput());
        assertEquals("tool_permission", response.getInputKind());
        assertNotNull(response.getResponseSchema());
        assertEquals("object", response.getResponseSchema().get("type"));
        Map<String, Object> properties = (Map<String, Object>) response.getResponseSchema().get("properties");
        assertEquals("tool_permission", ((Map<?, ?>) properties.get("inputKind")).get("const"));
        assertTrue(response.getInputRequest().contains("does not match"));
        assertTrue(memory.containsKey("tool:permission:conversation-1"));
        verifyNoInteractions(mcp);
    }

    @Test
    public void queuedPermissionsAreSurfacedOneAtATimeInOriginalOrder() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        PendingToolPermissionState state = state();
        PendingToolPermissionState.Continuation second = new PendingToolPermissionState.Continuation();
        second.setPermissionRequestId("permission-2");
        second.setServerName("tickets");
        second.setServerUrl("http://example.invalid/mcp");
        second.setParentIteration(1);
        ToolCall secondCall = new ToolCall("call-2", "delete_ticket",
                Collections.singletonMap("id", "T-43"));
        second.setToolCall(secondCall);
        second.setOuterToolCall(secondCall);
        state.setQueue(java.util.Arrays.asList(state.getQueue().get(0), second));
        state.getMessages().add(new LlmMessage("tool_result", "Waiting for tool permission.",
                "call-2", "delete_ticket"));
        when(mcp.callTool(any(), eq("delete_ticket"), anyMap(), isNull()))
                .thenReturn(McpToolResult.CallOutcome.completed(new McpToolResult("deleted")));

        Object result = invoke(engine, store, state,
                "{\"action\":\"approve\",\"permissionRequestId\":\"permission-1\"}");

        AgentResponse response = resultResponse(result);
        assertNotNull(response);
        assertEquals("permission-2", response.getPermissionRequestId());
        assertEquals("T-43", response.getPermissionArguments().get("id"));
        assertEquals(1, state.getQueue().size());
        verify(mcp, times(1)).callTool(any(), eq("delete_ticket"), anyMap(), isNull());
    }

    private static PendingToolPermissionState state() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", "T-42");
        ToolCall call = new ToolCall("call-1", "delete_ticket", arguments);
        PendingToolPermissionState.Continuation continuation = new PendingToolPermissionState.Continuation();
        continuation.setPermissionRequestId("permission-1");
        continuation.setServerName("tickets");
        continuation.setServerUrl("http://example.invalid/mcp");
        continuation.setToolCall(call);
        continuation.setOuterToolCall(call);
        continuation.setParentIteration(1);
        PendingToolPermissionState state = new PendingToolPermissionState();
        state.setRootUserTask("delete ticket T-42");
        state.setMaxIterations(5);
        state.setActualIterations(1);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("user", "delete ticket T-42"));
        messages.add(new LlmMessage("assistant", null, Collections.singletonList(call)));
        messages.add(new LlmMessage("tool_result", "Waiting for tool permission.", "call-1", "delete_ticket"));
        state.setMessages(messages);
        state.setQueue(Collections.singletonList(continuation));
        return state;
    }

    private static Object invoke(ReactEngine engine, ObjectStore<Serializable> store,
                                 PendingToolPermissionState state, String answer) throws Exception {
        Method method = ReactEngine.class.getDeclaredMethod("resumePendingPermission",
                ObjectStore.class, String.class, PendingToolPermissionState.class, String.class,
                AgentComposerConfiguration.class, List.class, List.class, List.class,
                ReactEngine.IterationCallback.class, String.class);
        method.setAccessible(true);
        McpServerConfig server = new McpServerConfig();
        set(server, "name", "tickets");
        set(server, "serverUrl", "http://example.invalid/mcp");
        ToolDefinition tool = new ToolDefinition("delete_ticket", "Deletes a ticket",
                Collections.emptyMap(), "http://example.invalid/mcp");
        return method.invoke(engine, store, "conversation-1", state, answer,
                new AgentComposerConfiguration(), Collections.emptyList(),
                Collections.singletonList(tool), Collections.singletonList(server), null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<LlmMessage> resultMessages(Object result) throws Exception {
        Field field = result.getClass().getDeclaredField("messages");
        field.setAccessible(true);
        return (List<LlmMessage>) field.get(result);
    }

    private static AgentResponse resultResponse(Object result) throws Exception {
        Field field = result.getClass().getDeclaredField("response");
        field.setAccessible(true);
        return (AgentResponse) field.get(result);
    }

    @SuppressWarnings("unchecked")
    private static ObjectStore<Serializable> mockStore(Map<String, Serializable> memory) throws Exception {
        ObjectStore<Serializable> store = mock(ObjectStore.class);
        when(store.contains(anyString())).thenAnswer(i -> memory.containsKey(i.getArgument(0)));
        when(store.retrieve(anyString())).thenAnswer(i -> memory.get(i.getArgument(0)));
        doAnswer(i -> { memory.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(store).store(anyString(), any(Serializable.class));
        when(store.remove(anyString())).thenAnswer(i -> memory.remove(i.getArgument(0)));
        return store;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
