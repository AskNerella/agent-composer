# Execute Agent

| Attribute | Required | Default | Meaning |
|---|---:|---:|---|
| User Message | No | `#[payload.params.message.parts[0].text]` | Current input; override for non-A2A flows. |
| Conversation ID | No | `#[payload.params.message.contextId]` | Conversation and trace correlation scope. It does not enable memory by itself. |
| Max Iterations | No | `5` | Hard outer-loop limit for ReAct reasoning. |

The response includes `response`, `complete`, `iterationCount`, `toolCalls`, `cacheHit`, `executionId`, and `traceReportPath`. When the agent requests clarification, it also returns `requiresInput`, `inputKind`, and `inputRequest`.
