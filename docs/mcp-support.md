# MCP support

## Protocol version

The connector requests MCP `2025-11-25`. If `initialize` negotiates another version—commonly `2025-06-18`—the negotiated version is used for later requests. For the broadest compatibility with current MuleSoft MCP servers, use a server that supports `2025-06-18` negotiation or later.

Stateful and stateless Streamable HTTP servers are supported for tool discovery and execution. When a server returns an MCP session ID, the connector sends it on subsequent requests in that session.

## MCP Apps / MCP UI

Supported patterns:

- Tool-definition `_meta.ui.resourceUri` followed by `resources/read`.
- Result-level `_meta.ui.resourceUri` fallback.
- Legacy embedded HTML resource content.

UI URI, HTML, and MIME type are included in tool records and applicable A2A artifacts.

## Discovery and retries

Successful non-empty tool discovery is cached for the deployed application and reused by agent-card generation and agent execution. This avoids a health or discovery request for every agent call. Failed and empty discovery results are not cached, allowing a server to recover on a later request.

Each MCP server has an independent retry count with a default of `0`. Avoid enabling retries for tools with side effects unless the server provides idempotency guarantees.
