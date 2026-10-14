# Trace operations

## Retrieve Execution Trace

`Execution ID` selects the redacted trace returned by Execute Agent.

## List Execution Traces

`Max Results` defaults to 50 and is bounded to 1–200. Results are newest first.

## Render Execution Trace Report

`Execution ID` selects a report. The same UI is served at Traces Path and contains a Mermaid loop diagram, waterfall, span table, MCP inventory, inputs/outputs, metrics, and Stop Task while work is running.

Stop Task interrupts the in-process execution. It does not roll back external side effects that already completed.
