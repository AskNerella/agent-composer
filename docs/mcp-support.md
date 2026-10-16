# MCP support

## Protocol version

The connector requests MCP `2025-11-25`. If `initialize` negotiates another version—commonly `2025-06-18`—the negotiated version is used for later requests. For the broadest compatibility with current MuleSoft MCP servers, use a server that supports `2025-06-18` negotiation or later.

Stateful and stateless Streamable HTTP servers are supported for tool discovery and execution. When a server returns an MCP session ID, the connector sends it on subsequent requests in that session.

## Discovery and retries

Successful non-empty tool discovery is cached for the deployed application and reused by agent-card generation and agent execution. This avoids a health or discovery request for every agent call. Failed and empty discovery results are not cached, allowing a server to recover on a later request.

Each MCP server has an independent retry count with a default of `0`. Avoid enabling retries for tools with side effects unless the server provides idempotency guarantees.

## Tool permissions

**Tools Requiring Permission** accepts comma-separated exact tool names per MCP server. A matching direct or skill tool call pauses before execution and returns `inputKind: tool_permission` with the exact server, tool, arguments, permission request ID, and a `responseSchema`. Direct Execute Agent callers resume with the same conversation ID and a schema-compliant object such as `{"inputKind":"tool_permission","action":"approve","permissionRequestId":"<id>"}` or the equivalent `deny` action. A2A callers resume the interrupted task through a normal `message/send` (0.3) or `SendMessage` (1.0) request using the same task ID; put that decision object in a standard data part. Each invocation requires its own decision.

`responseSchema` is not a predefined A2A field. A2A intentionally permits arbitrary structured JSON in a message data part, so Agent Composer includes the schema inside the same input-required data part as application-level guidance. The surrounding task, status, message, and data part remain standard A2A objects.
