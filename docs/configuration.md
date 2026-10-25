# Global configuration

## General attributes

| Attribute | Required | Default | Meaning |
|---|---:|---:|---|
| LLM Provider | Yes | — | OpenAI, Anthropic, OpenAI Compatible, or Omni Gateway configuration. |
| Max Output Tokens | No | `1000` | Maximum tokens requested from each model response. |
| Temperature | No | `1.0` | Sampling temperature; omitted for models that do not support it. |
| HTTP Listener Config | No | `HTTP_Listener_config` | Existing Mule HTTP Listener used by Agent Listener. |
| Agent Path | No | `/agent` | A2A JSON-RPC endpoint. |
| Request Timeout | No | `300` seconds | How long Agent Listener waits for the Mule flow. |
| Traces Path | No | `/agent/traces` | HTML and JSON execution trace endpoint. |
| Tool Permission Wait Time (seconds) | No | `900` | How long an approval may remain pending before the A2A task becomes `expired`; `0` waits indefinitely. |

## Agent attributes

| Attribute | Default | Meaning |
|---|---:|---|
| Instructions | `${file::agent/instructions.txt}` | System instructions sent to the LLM. |
| MCP Servers | Empty | MCP endpoints available to tools and skills. |
| Memory (Object Store) | `_defaultPersistentObjectStore` | Stores traces, A2A tasks, optional conversation history, and solved responses. |
| Remember Conversation | `false` | Reuse history and solved responses. Disabled means normal calls start fresh. |
| Include MCP Tools as Skills | `false` | Advertise raw MCP tools in the A2A card. |

## OpenAI

```xml
<agent-composer:llm-provider>
  <agent-composer:open-ai modelName="gpt-4o" apiKey="${OPENAI_API_KEY}" />
</agent-composer:llm-provider>
```

`API Key` is required. `Model Name` is editable and offers API-key-backed suggestions when Studio can resolve the key; expressions remain supported.

## Anthropic

```xml
<agent-composer:llm-provider>
  <agent-composer:anthropic apiKey="${ANTHROPIC_API_KEY}" anthropicVersion="2023-06-01" />
</agent-composer:llm-provider>
```

`API Key` is required. `Anthropic API Version` controls the request header. The supported model is selected internally by the connector.

## OpenAI Compatible Endpoint

```xml
<agent-composer:llm-provider>
  <agent-composer:open-ai-compatible endpointUrl="https://gateway.example.com/v1/responses"
                                             modelName="gateway-model">
    <agent-composer:headers>
      <agent-composer:llm-http-header name="Authorization" value="Bearer ${GATEWAY_TOKEN}" />
    </agent-composer:headers>
  </agent-composer:open-ai-compatible>
</agent-composer:llm-provider>
```

The endpoint must implement the OpenAI Responses request/response shape. You may provide either the full POST endpoint (for example, `https://gateway.example.com/v1/responses`) or a base URL ending in `/`; the connector appends `v1/responses` to base URLs. Model Name is required and is sent in the request. Authentication and tenant metadata belong in Headers.

## Omni Gateway

```xml
<agent-composer:llm-provider>
  <agent-composer:omni-gateway endpointUrl="https://gateway.example.com/my-model-proxy"
                               modelName="openai/gpt-5.2">
    <agent-composer:headers>
      <agent-composer:llm-http-header name="client_id" value="${OMNI_CLIENT_ID}" />
      <agent-composer:llm-http-header name="client_secret" value="${OMNI_CLIENT_SECRET}" />
    </agent-composer:headers>
  </agent-composer:omni-gateway>
</agent-composer:llm-provider>
```

URL and Model Name are required. URL is the Omni Gateway public endpoint plus the Model Proxy base path; the connector appends `/responses`, unless it is already present. Model Name is the provider/model route, such as `openai/gpt-5.2`. Headers typically contain `client_id` and `client_secret`. Trace Request shows the resolved URL and headers with credential values redacted.

## MCP server

| Attribute | Required | Default | Meaning |
|---|---:|---:|---|
| Name | Yes | — | Server name used in logs, traces, and tool records. |
| Server URL | Yes | — | Streamable HTTP JSON-RPC endpoint. |
| Auth Token | No | — | Optional bearer token. |
| Tool Filter | No | Empty | Comma-separated allowlist; empty permits every tool. |
| Tools Requiring Permission | No | Empty | Comma-separated exact tool names that pause for approval before every invocation. |
| Retry Count | No | `0` | Retry count, clamped from 0 through 10. |

Successful non-empty discovery results are shared across the deployed application. Empty or failed discovery is not cached, allowing recovery on a later request.
