# Execute Agent

| Attribute | Required | Default | Meaning |
|---|---:|---:|---|
| User Message | No | `#[payload.params.message.parts[0].text]` | Current input; override for non-A2A flows. |
| Conversation ID | No | `#[payload.params.message.contextId]` | Conversation and trace correlation scope. It does not enable memory by itself. |
| Max Iterations | No | `5` | Hard outer-loop limit for ReAct reasoning. |

The response includes `response`, `complete`, `iterationCount`, `toolCalls`, `cacheHit`, `executionId`, and `traceReportPath`. Input pauses return `requiresInput`, `inputKind`, and `inputRequest`. Tool approval also returns `permissionRequestId`, `permissionServerName`, `permissionToolName`, the exact `permissionArguments`, and `responseSchema`. The schema describes the data object the caller must send to approve or deny that exact invocation.
