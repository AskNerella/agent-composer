package org.mule.extension.agent.composer.internal.model;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PendingElicitationStateTest {

    @Test
    public void roundTripsQueuedDirectAndSkillContinuationsInOrder() {
        PendingElicitationState.Continuation direct = continuation("first", false);
        PendingElicitationState.Continuation skill = continuation("second", true);
        PendingElicitationState state = new PendingElicitationState();
        state.setRootUserTask("root task");
        state.setMaxIterations(5);
        state.setActualIterations(2);
        state.setQueue(Arrays.asList(direct, skill));

        PendingElicitationState restored = new Gson().fromJson(
                new Gson().toJson(state), PendingElicitationState.class);

        assertEquals("first", restored.getQueue().get(0).getRequest().getToolName());
        assertEquals("second", restored.getQueue().get(1).getRequest().getToolName());
        assertTrue(restored.getQueue().get(1).isSkill());
        assertEquals("nested-1", restored.getQueue().get(1).getSkillState()
                .getMessages().get(0).getToolCalls().get(0).getId());
    }

    private static PendingElicitationState.Continuation continuation(String tool, boolean skill) {
        PendingElicitationState.Continuation continuation = new PendingElicitationState.Continuation();
        continuation.setRequest(new McpToolResult.ElicitationRequest(
                "request-" + tool, 1, "input", Collections.emptyMap(), "server", "url",
                tool, Collections.emptyMap(), null, "session"));
        continuation.setOuterToolCall(new ToolCall("outer-" + tool, tool, Collections.emptyMap()));
        if (skill) {
            PendingElicitationState.SkillState state = new PendingElicitationState.SkillState();
            state.setSkillName("skill");
            state.setMessages(Collections.singletonList(new LlmMessage("assistant", null,
                    Collections.singletonList(new ToolCall("nested-1", tool, Collections.emptyMap())))));
            continuation.setSkillState(state);
        }
        return continuation;
    }
}
