# Agent Listener and traces

| Endpoint | Method | Purpose |
|---|---|---|
| `/.well-known/agent-card.json` | GET | Root agent card. |
| `{agentPath}/.well-known/agent-card.json` | GET | Scoped agent card. |
| `{agentPath}/.well-known/agent.json` | GET | Legacy alias. |
| `{agentPath}` | POST | A2A JSON-RPC. |
| `{tracesPath}` | GET | Trace index or selected report. |
| `{tracesPath}?executionId=…&format=json` | GET | Redacted trace JSON. |
| `{tracesPath}?executionId=…&action=cancel` | POST | Interrupt a running execution. |

The trace report displays connected MCP servers and their tools. Successful tool discovery is reused between agent-card generation and agent calls, avoiding a health request on every invocation.

The stop control is process-local and shown only while a trace is running. It cannot undo an MCP side effect that already completed.
