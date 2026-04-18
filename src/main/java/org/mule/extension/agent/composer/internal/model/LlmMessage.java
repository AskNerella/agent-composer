package org.mule.extension.agent.composer.internal.model;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a single turn in a conversation with an LLM.
 *
 * Roles:
 *  - "user"        : human-turn message
 *  - "assistant"   : model response, optionally carrying tool calls
 *  - "tool_result" : the result of a previously requested tool call
 */
public class LlmMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String role;

    /** Textual content of this message. May be null for pure tool-call assistant turns. */
    private String content;

    /** Non-empty when the assistant requests one or more tool invocations. */
    private List<ToolCall> toolCalls;

    /** Present on "tool_result" messages – ties back to the original tool_call id. */
    private String toolCallId;

    /** Present on "tool_result" messages – the name of the tool that was called. */
    private String toolName;

    // ── constructors ──────────────────────────────────────────────────────────

    public LlmMessage() {}

    /** Simple text message (user or assistant without tool calls). */
    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** Assistant message that also carries tool-call requests. */
    public LlmMessage(String role, String content, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
    }

    /** Tool-result message. */
    public LlmMessage(String role, String content, String toolCallId, String toolName) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    // ── helpers ───────────────────────────────────────────────────────────────

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
