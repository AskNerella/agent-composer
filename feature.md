# Agent Composer — Enhancement Plan

**Repository:** https://github.com/AskNerella/agent-composer  
**Branch:** `feature/optimize`

Enhance the existing MuleSoft AI Agent connector into a production-ready agent framework. Inspect the current implementation first, reuse existing functionality, and maintain backward compatibility.

## Features to Implement

1. **Execution Tracing:** Capture LLM requests/responses, tool calls, timings, tokens, errors, retries, and execution status. Support nested spans and JSON export.

2. **Live HTML Trace Report:** Interactive execution timeline with expandable request/response details, running indicators, elapsed time, errors, and token usage. Include **Cancel, Rerun, Edit & Rerun, Replay, and Compare Runs**. Support live updates and report refresh.

3. **Task Cancellation:** Support A2A `tasks/cancel`, connector operations, and HTML report controls. Stop further execution, retries, parallel tasks, and existing Agent Skills where possible. Handle cancellation races safely.

4. **Custom LLM Endpoints:** Support custom OpenAI/Anthropic-compatible URLs for enterprise gateways such as Omni Gateway. Allow bearer tokens, API keys, client ID/secret headers, custom headers, timeouts, and streaming. Securely resolve credentials.

5. **Tool Error Recovery:** Configurable retries, backoff, argument repair, agent-assisted recovery, and error classification. Avoid unsafe retries of side-effecting tools.

6. **Tool Fallback:** Configure alternative MCP tools with input/output mapping, priority, and circuit breakers.

7. **Conversation Compaction:** Custom thresholds, summarization strategies, preserved messages, and safe handling of tool-call history.

8. **Agent Evaluations:** Enhance existing evaluation support with scoring, rubrics, datasets, regression tests, and structured results.

9. **Human Approval:** Require explicit approval before sensitive tool actions.

10. **Execution Guardrails:** Set limits for iterations, tokens, cost, elapsed time, retries, and parallel executions.

11. **Dry Run & Replay:** Mock external tools and replay recorded responses without unintended side effects.

12. **Structured Outputs:** JSON Schema validation and bounded response repair.

13. **MCP Reliability:** Server health checks, discovery caching, timeouts, and connection diagnostics.

14. **Report Endpoint Console**: Provide a dedicated endpoint for retrieving execution reports, including live HTML traces, error logs, and structured output summaries. Support filtering by execution ID, time range, and status.

## Engineering Requirements

- Refactor the large `ReactEngine.java` incrementally into maintainable components.
- Fix cache-key collisions, tenant isolation, concurrency limits, completion-state logic, and suppressed persistence errors.
- Redact credentials and sensitive data from logs and HTML reports.
- Preserve existing Agent Skills, MCP, A2A, Mule configurations, and operations.
- **Do not implement Sub-Agents or YAML configuration.**

## Implementation Order

1. Execution context, tracing, and live HTML report
2. Cancellation, rerun, replay, and run comparison
3. Custom LLM endpoints and authentication
4. Recovery, fallback, approvals, and guardrails
5. Compaction, evaluations, structured outputs, and MCP reliability
6. Security, testing, documentation, and performance improvements

## Codex Instructions

**Work one phase at a time.** Inspect existing code before changing anything.

Write tests, verify the build, and preserve backward compatibility.

**Start by reviewing the code and implementing Phase 1. Do not attempt the entire roadmap in one session.**
