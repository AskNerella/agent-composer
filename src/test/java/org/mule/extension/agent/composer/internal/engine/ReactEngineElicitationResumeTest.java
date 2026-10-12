package org.mule.extension.agent.composer.internal.engine;

import com.google.gson.Gson;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.McpToolResult;
import org.mule.extension.agent.composer.internal.model.PendingElicitationState;
import org.mule.extension.agent.composer.internal.model.ToolCall;
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

public class ReactEngineElicitationResumeTest {

    @Test
    public void resumeAdoptsLargerConfiguredBudgetAndExtendsPausedSkill() {
        PendingElicitationState state = state(true);

        int effective = ReactEngine.reconcilePendingIterationBudget(state, 20);

        assertEquals(20, effective);
        assertEquals(20, state.getMaxIterations());
        assertEquals(16, state.getQueue().get(0).getSkillState().getMaxSteps());
    }

    @Test
    public void resumeNeverShrinksOriginalBudget() {
        PendingElicitationState state = state(true);

        int effective = ReactEngine.reconcilePendingIterationBudget(state, 3);

        assertEquals(5, effective);
        assertEquals(5, state.getMaxIterations());
        assertEquals(1, state.getQueue().get(0).getSkillState().getMaxSteps());
    }

    @Test
    public void directResumeReplacesPlaceholderAndClearsPendingState() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        McpServerConfig server = server();
        PendingElicitationState state = state(false);
        memory.put("conversation-1", new Gson().toJson(history("outer-1", "book_trip")));
        memory.put("mcp:elicitation:conversation-1", new Gson().toJson(state));
        when(mcp.resolveElicitation(eq(server), any(), eq("accept"), any()))
                .thenReturn(McpToolResult.CallOutcome.completed(new McpToolResult("booked")));

        Object resume = invokeResume(engine, store, state, "{\"cabin\":\"economy\"}",
                config(Collections.emptyList()), Collections.emptyList(), Collections.singletonList(server));

        List<LlmMessage> messages = resultMessages(resume);
        assertEquals("booked", messages.get(messages.size() - 1).getContent());
        assertFalse(memory.containsKey("mcp:elicitation:conversation-1"));
        verify(mcp).resolveElicitation(eq(server), any(), eq("accept"), any());
    }

    @Test
    public void skillResumeUsesSavedNestedHistoryAndReturnsToParent() throws Exception {
        Map<String, Serializable> memory = new LinkedHashMap<>();
        ObjectStore<Serializable> store = mockStore(memory);
        McpClient mcp = mock(McpClient.class);
        ReactEngine engine = new ReactEngine(null, mcp);
        McpServerConfig server = server();
        PendingElicitationState state = state(true);
        memory.put("conversation-1", new Gson().toJson(history("outer-1", "travel_skill")));
        memory.put("mcp:elicitation:conversation-1", new Gson().toJson(state));
        when(mcp.resolveElicitation(eq(server), any(), eq("accept"), any()))
                .thenReturn(McpToolResult.CallOutcome.completed(new McpToolResult("nested booked")));

        AgentSkillConfig skill = new AgentSkillConfig();
        setField(skill, "name", "travel_skill");
        setField(skill, "instructions", "Use travel tools.");
        Object resume = invokeResume(engine, store, state, "economy",
                config(Collections.singletonList(skill)), Collections.singletonList(skill),
                Collections.singletonList(server));

        List<LlmMessage> messages = resultMessages(resume);
        assertTrue(messages.get(messages.size() - 1).getContent().contains("did not return a final answer"));
        assertFalse(memory.containsKey("mcp:elicitation:conversation-1"));
    }

    private static Object invokeResume(ReactEngine engine, ObjectStore<Serializable> store,
                                       PendingElicitationState state, String answer,
                                       AgentComposerConfiguration config, List<AgentSkillConfig> skills,
                                       List<McpServerConfig> servers) throws Exception {
        Method method = ReactEngine.class.getDeclaredMethod("resumePendingElicitation",
                ObjectStore.class, String.class, PendingElicitationState.class, String.class,
                AgentComposerConfiguration.class, List.class, List.class, List.class,
                ReactEngine.IterationCallback.class);
        method.setAccessible(true);
        return method.invoke(engine, store, "conversation-1", state, answer,
                config, skills, Collections.emptyList(), servers, null);
    }

    @SuppressWarnings("unchecked")
    private static List<LlmMessage> resultMessages(Object resume) throws Exception {
        Field field = resume.getClass().getDeclaredField("messages");
        field.setAccessible(true);
        return (List<LlmMessage>) field.get(resume);
    }

    private static PendingElicitationState state(boolean skill) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cabin", field);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Collections.singletonList("cabin"));
        McpToolResult.ElicitationRequest request = new McpToolResult.ElicitationRequest(
                "elicit-1", 7, "Which cabin?", schema, "travel",
                "http://example.invalid/mcp", "book_trip", Collections.emptyMap(), null, "session-1");
        PendingElicitationState.Continuation continuation = new PendingElicitationState.Continuation();
        continuation.setRequest(request);
        continuation.setParentIteration(1);
        continuation.setOuterToolCall(new ToolCall("outer-1", skill ? "travel_skill" : "book_trip",
                Collections.emptyMap()));
        if (skill) {
            PendingElicitationState.SkillState skillState = new PendingElicitationState.SkillState();
            skillState.setSkillName("travel_skill");
            skillState.setTask("book a trip");
            List<LlmMessage> nested = new ArrayList<>();
            nested.add(new LlmMessage("user", "book a trip"));
            nested.add(new LlmMessage("assistant", null, Collections.singletonList(
                    new ToolCall("nested-1", "book_trip", Collections.emptyMap()))));
            skillState.setMessages(nested);
            skillState.setNextStep(1);
            skillState.setMaxSteps(1);
            continuation.setSkillState(skillState);
        }
        PendingElicitationState state = new PendingElicitationState();
        state.setRootUserTask("book a trip");
        state.setMaxIterations(5);
        state.setActualIterations(1);
        state.setQueue(Collections.singletonList(continuation));
        return state;
    }

    private static List<LlmMessage> history(String id, String tool) {
        List<LlmMessage> history = new ArrayList<>();
        history.add(new LlmMessage("user", "book a trip"));
        history.add(new LlmMessage("assistant", null,
                Collections.singletonList(new ToolCall(id, tool, Collections.emptyMap()))));
        history.add(new LlmMessage("tool_result", "Waiting for user input (elicitation).", id, tool));
        return history;
    }

    private static AgentComposerConfiguration config(List<AgentSkillConfig> skills) throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "provider", LlmProvider.OPENAI);
        setField(config, "modelName", "test-model");
        setField(config, "skills", skills);
        setField(config, "includeMcpToolsAsSkills", true);
        return config;
    }

    private static McpServerConfig server() throws Exception {
        McpServerConfig server = new McpServerConfig();
        setField(server, "name", "travel");
        setField(server, "serverUrl", "http://example.invalid/mcp");
        return server;
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
