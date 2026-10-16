# A2A support matrix

Agent Listener reads `A2A-Version` from the header or query parameter. Missing means 0.3; patch versions are compared by major/minor.

Import the [Postman collection](postman/Agent-Composer-A2A.postman_collection.json) and [local environment](postman/Agent-Composer-A2A.postman_environment.json) to exercise both versions. Supported operations and intentionally unsupported capability probes are separated into different folders.

For permission and cancellation tests, first run the version's **Start Permission Task** request and confirm its state is `input-required`. Then run exactly one of Approve, Deny, or Cancel. Each action consumes or terminates that task, so start a fresh permission task before testing another action. The collection clears stale identifiers, extracts identifiers from blocking and SSE responses, and stops an action early if the selected task is already terminal.

| Capability | A2A 0.3 | A2A 1.0 | Notes |
|---|:---:|:---:|---|
| Agent card | Yes | Yes | Advertises 0.3 fields and supported 0.3/1.0 interfaces. |
| Send message | `message/send` | `SendMessage` | Creates a task or continues an interrupted task. `tasks/send` and `tasks/respond` are not A2A methods. |
| Streaming | `message/stream` | `SendStreamingMessage` | Flushed SSE updates for tool/skill activity and every completed ReAct iteration, followed by artifact and terminal status. |
| Get task | `tasks/get` | `GetTask` | Optional history trimming. |
| Cancel task | `tasks/cancel` | `CancelTask` | Marks the A2A task canceled. |
| List tasks | Not in 0.3 | `ListTasks` | Supports status/context filters and page size/token pagination. Results are scoped to the configured Object Store; deployments must enforce caller visibility at the Mule/API gateway layer. |
| Subscribe to existing task | `tasks/resubscribe` | `SubscribeToTask` | Returns the current non-terminal task snapshot, streams later transitions, supports multiple subscribers, and closes at a terminal state. |
| Text parts | Yes | Yes | Default input mode. |
| Data parts | Yes | Yes | Structured task and artifact data. |
| File parts | No | No | Not advertised as an input mode. |
| Input-required | Yes | Yes | Agent clarification and tool approval use the standard multi-turn task lifecycle. |
| Push notifications | No | No | Advertised as unsupported. |
| Push configuration operations | No | No | Not implemented because push notifications are disabled. |
| Extended authenticated card | No | No | Not advertised or implemented. |
| Auth negotiation | No | No | Protect endpoints with Mule/API gateway controls. |
| State-transition history | No | No | Advertised as unsupported. |

## Continuing an input-required task

There is no A2A `tasks/respond` operation. Send an ordinary user message through the version's send-message operation. Put the server-issued `taskId` on `message`; include the matching `contextId`, or omit it and let the server infer it from the task.

For a tool-permission response, use a standard A2A data part. The data payload is application data carried by A2A, while the operation, message, task, context, and lifecycle are all standard A2A:

```json
{
  "jsonrpc": "2.0",
  "id": "approve-1",
  "method": "message/send",
  "params": {
    "message": {
      "messageId": "client-message-2",
      "taskId": "server-task-id",
      "contextId": "server-context-id",
      "role": "user",
      "parts": [
        {
          "kind": "data",
          "data": {
            "inputKind": "tool_permission",
            "action": "approve",
            "permissionRequestId": "permission-id-from-input-required"
          }
        }
      ]
    }
  }
}
```

The preceding `input-required` status contains a `responseSchema` beside the permission metadata in its data part. `responseSchema` is Agent Composer application data, not a field defined by the A2A protocol. This is interoperable because A2A data parts are the standard container for arbitrary structured JSON.

For A2A 1.0, use `SendMessage`, send `A2A-Version: 1.0`, omit the 0.3 `kind` discriminators, and use the 1.0 role enum (`ROLE_USER`). A2A 1.0 send responses and stream events use their specified oneof envelopes (`task`, `message`, `statusUpdate`, or `artifactUpdate`).

The listener rejects unknown task IDs, mismatched task/context pairs, and attempts to continue terminal tasks. Invalid permission decisions keep the task interrupted so the client can retry through another standard send-message request. Permission decisions are scoped to the exact task, context, and immutable request ID; concurrent resumes are serialized, and completed decisions are idempotently rejected by the terminal-task guard. Permission checkpoints survive restart and expire after the configurable **Tool Permission Wait Time** (15 minutes by default). The next task read, list, subscription, or continuation materializes an elapsed checkpoint as task state `expired` (`TASK_STATE_EXPIRED` on the 1.0 wire format).

Clarification replies follow the same operation and identifiers. They remain ordinary text parts rather than permission data.
