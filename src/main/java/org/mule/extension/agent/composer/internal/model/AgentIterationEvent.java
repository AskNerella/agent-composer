package org.mule.extension.agent.composer.internal.model;

import java.util.Map;

/**
 * Structured notification emitted by {@code ReactEngine} for each step of the ReAct loop.
 * Replaces free-text narration so callers (e.g. A2A streaming) can render proper
 * machine-readable event data instead of flattening tool calls into prose.
 */
public class AgentIterationEvent {

    public enum Kind {
        TOOL_CALL, TOOL_RESULT,
        SKILL_CALL, SKILL_RESULT,
        PARALLEL_BATCH,
        CLARIFICATION,
        COMPRESSION,
        ITERATION_COMPLETE
    }

    private final int iteration;
    private final Kind kind;
    private final String name;
    private final Map<String, Object> arguments;
    private final String result;
    private final String serverName;

    public AgentIterationEvent(int iteration, Kind kind, String name,
                                Map<String, Object> arguments, String result, String serverName) {
        this.iteration = iteration;
        this.kind = kind;
        this.name = name;
        this.arguments = arguments;
        this.result = result;
        this.serverName = serverName;
    }

    public static AgentIterationEvent toolCall(int iteration, String name, Map<String, Object> arguments, String serverName) {
        return new AgentIterationEvent(iteration, Kind.TOOL_CALL, name, arguments, null, serverName);
    }

    public static AgentIterationEvent toolResult(int iteration, String name, Map<String, Object> arguments, String result, String serverName) {
        return new AgentIterationEvent(iteration, Kind.TOOL_RESULT, name, arguments, result, serverName);
    }

    public static AgentIterationEvent skillCall(int iteration, String name, Map<String, Object> arguments) {
        return new AgentIterationEvent(iteration, Kind.SKILL_CALL, name, arguments, null, "skill:" + name);
    }

    public static AgentIterationEvent skillResult(int iteration, String name, Map<String, Object> arguments, String result) {
        return new AgentIterationEvent(iteration, Kind.SKILL_RESULT, name, arguments, result, "skill:" + name);
    }

    public static AgentIterationEvent parallelBatch(int iteration, java.util.List<String> toolNames) {
        return new AgentIterationEvent(iteration, Kind.PARALLEL_BATCH, String.join(", ", toolNames), null, null, null);
    }

    public static AgentIterationEvent compression(int iteration) {
        return new AgentIterationEvent(iteration, Kind.COMPRESSION, null, null, null, null);
    }

    public static AgentIterationEvent iterationComplete(int iteration, String result) {
        return new AgentIterationEvent(iteration, Kind.ITERATION_COMPLETE,
                "Iteration " + iteration, null, result, null);
    }

    public int getIteration() { return iteration; }
    public Kind getKind() { return kind; }
    public String getName() { return name; }
    public Map<String, Object> getArguments() { return arguments; }
    public String getResult() { return result; }
    public String getServerName() { return serverName; }
}
