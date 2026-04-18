package org.mule.extension.agent.composer.internal.model;

/**
 * Normalised response from an LLM inference call.
 *
 * Stop-reason semantics (provider-agnostic):
 *  - {@code "stop"}     – model reached a natural end; {@code content} is the final answer.
 *  - {@code "tool_use"} – model requested a tool call; inspect {@code toolCall}.
 */
public class LlmResponse {

    private String content;
    private ToolCall toolCall;

    /**
     * Normalised stop reason:
     *  OpenAI  → "stop" | "tool_calls"
     *  Anthropic → "end_turn" | "tool_use"
     * Stored as returned by the provider; {@link #hasFinalAnswer()} abstracts over both.
     */
    private String stopReason;

    // ── constructors ──────────────────────────────────────────────────────────

    public LlmResponse() {}

    public LlmResponse(String content, ToolCall toolCall, String stopReason) {
        this.content = content;
        this.toolCall = toolCall;
        this.stopReason = stopReason;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Returns {@code true} when the LLM has produced a final text answer. */
    public boolean hasFinalAnswer() {
        return toolCall == null;
    }

    /** Returns {@code true} when the LLM is requesting a tool invocation. */
    public boolean hasToolCall() {
        return toolCall != null;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public ToolCall getToolCall() { return toolCall; }
    public void setToolCall(ToolCall toolCall) { this.toolCall = toolCall; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
}
