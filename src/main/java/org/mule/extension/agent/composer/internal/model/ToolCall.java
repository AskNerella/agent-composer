package org.mule.extension.agent.composer.internal.model;

import java.io.Serializable;
import java.util.Map;

/**
 * Represents a single tool invocation requested by the LLM.
 */
public class ToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Provider-issued call identifier (e.g. OpenAI "call_abc123", Anthropic "toolu_01..."). */
    private String id;

    /** Name of the tool to invoke. */
    private String name;

    /** Parsed arguments keyed by parameter name. */
    private Map<String, Object> arguments;

    // ── constructors ──────────────────────────────────────────────────────────

    public ToolCall() {}

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
}
