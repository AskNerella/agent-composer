# Trace operations

## Retrieve Execution Trace

`Execution ID` selects the redacted trace returned by Execute Agent.

## List Execution Traces

`Max Results` defaults to 50 and is bounded to 1–200. Results are newest first.

## Render Execution Trace Report

`Execution ID` selects a report. The same UI is served at Traces Path and contains a Mermaid loop diagram, waterfall, span table, inputs/outputs, metrics, and Stop Task while work is running. The header opens a searchable MCP server/tool drawer. Input-required permission spans expose Approve and Deny buttons that continue the task through standard A2A `message/send`; the index groups resumed executions by correlation ID, filters as text is entered, shows the user request, and can clear all retained traces.

Stop Task interrupts the in-process execution. It does not roll back external side effects that already completed.
