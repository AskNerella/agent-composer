# A2A support matrix

Agent Listener reads `A2A-Version` from the header or query parameter. Missing means 0.3; patch versions are compared by major/minor.

| Capability | A2A 0.3 | A2A 1.0 | Notes |
|---|:---:|:---:|---|
| Agent card | Yes | Yes | Advertises 0.3 fields and supported 0.3/1.0 interfaces. |
| Send message | `message/send`, `tasks/send` | `SendMessage` | JSON-RPC and compatible plain input. |
| Streaming | `message/stream`, `tasks/sendSubscribe` | `SendStreamingMessage`, `SubscribeToTask` | SSE status and artifact updates. |
| Get task | `tasks/get` | `GetTask` | Optional history trimming. |
| Cancel task | `tasks/cancel` | `CancelTask` | Marks the A2A task canceled. |
| Text parts | Yes | Yes | Default input mode. |
| Data parts | Yes | Yes | Structured task and artifact data. |
| Input-required | Yes | Yes | Agent clarification requests. |
| Push notifications | No | No | Advertised as unsupported. |
| Auth negotiation | No | No | Protect endpoints with Mule/API gateway controls. |
| State-transition history | No | No | Advertised as unsupported. |

Clarification replies are sent as ordinary user messages using the same context ID.
