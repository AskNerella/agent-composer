# ReAct engine

```mermaid
flowchart TD
    S[Start] --> M{Remember conversation?}
    M -->|Yes| H[Load history or solved cache]
    M -->|No| N[Fresh history]
    H --> L[LLM reasoning]
    N --> L
    L --> F{Final answer?}
    F -->|Yes| A[Return answer]
    F -->|No| C[Execute tools or skills]
    C --> O[Append observations]
    O --> L
```

Independent calls in a model turn execute concurrently while results are collected in original LLM order. Skills run in a bounded nested loop that shares the configured iteration budget.

Tool schemas are shared per server configuration for the life of the deployment. A changed name, URL, filter, or authentication identity creates a new discovery key.
