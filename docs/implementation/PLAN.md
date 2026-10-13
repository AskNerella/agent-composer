# Agent Composer Enhancement Roadmap

This roadmap tracks `feature.md`. Work proceeds one phase at a time and preserves the existing Mule, MCP, A2A, and Agent Skill contracts. Sub-agents and YAML configuration are explicitly out of scope.

## Phase 1 — Execution tracing and live report

- [x] Versioned execution trace model with parent-linked nested spans.
- [x] Capture execution status, timings, LLM request/response data, tool/skill calls, token usage, retries, and errors.
- [x] Redact credentials before trace persistence, JSON export, or HTML rendering.
- [x] Persist trace snapshots in the configured Object Store and retain live snapshots in a tenant-scoped registry.
- [x] Add `executionId` and `traceReportPath` to execute-agent responses and A2A task metadata.
- [x] Add connector operations to retrieve, list, and render traces.
- [x] Add `/agent/traces` HTML and JSON endpoints with live refresh.
- [x] Add a ShadCN-tokenized light/dark trace UI with an interactive waterfall, span table, Lucide icons, and a fixed sliding details/metrics drawer.
- [x] Track Status, Kind, Name, Input, Output, Error, Start Time, and Latency for every span in the table view.
- [x] Add Agent Composer branding, correlation-ID-focused headings, and compact icon-only header actions; omit inactive tabs and speculative action controls.
- [x] Replace collision-prone Java hash cache keys with SHA-256 and include execution-affecting configuration.
- [x] Complete live Mule application verification, including Object Store recovery after a runtime restart.

## Phase 2 — Cancellation and run controls

- [ ] Cooperative cancellation context shared by A2A, connector operations, parallel tools, LLM retries, and Agent Skills.
- [ ] Define replay safety rules and immutable source-run linkage.

## Phase 3 — Custom LLM endpoints and authentication

- [x] Add conditional OpenAI, Anthropic, and `CUSTOM` Studio subtypes so only provider-relevant fields are displayed.
- [x] Add a `CUSTOM` provider with an OpenAI Responses-compatible endpoint URL and arbitrary custom headers.
- [ ] Add Anthropic-compatible custom endpoints, structured client ID/secret authentication, configurable timeouts, and streaming.

## Phase 4 — Recovery, fallback, approvals, and guardrails

- [ ] Classified tool retries and safe argument repair.
- [ ] Tool fallback mappings, priorities, and circuit breakers.
- [ ] Human approval policies built on MCP elicitation.
- [ ] Iteration, token, cost, elapsed-time, retry, and parallelism limits.

## Phase 5 — Compaction, evaluations, structured outputs, MCP reliability

- [ ] Configurable conversation compaction strategies.
- [ ] Evaluation rubrics, datasets, structured scores, and regressions.
- [ ] JSON Schema output validation and bounded repair.
- [ ] MCP health, discovery cache, timeouts, and diagnostics.
- [x] Add per-server MCP retry count configuration with a safe default of zero; saved-session elicitation resume remains non-retriable.

## Phase 6 — Hardening

- [ ] Security audit, persistence failure policy, performance/load tests, full documentation, and compatibility matrix.
