# InvoiceWorkflow

[All workflows](index.md)

> **Warning:** this diagram comes from a degraded model. The build of the analysed repository was not used, so calls into other modules and libraries may be missing or unresolved.

| Property | Value |
|---|---|
| Workflow type | `InvoiceWorkflow` |
| Interface | `com.example.billing.InvoiceWorkflow` |
| Implementation | `com.example.billing.InvoiceWorkflowImpl` |
| Module | `:` |
| Commit | `3f786850e387550fdab836ed7e6dc881de23001b` |
| Model mode | degraded |
| Nodes | 4 |
| Unknown nodes | 1 |

```mermaid
flowchart TD
    n0(["start"])
    n1["CreateInvoice"]
    n2["unknown#58; unresolved-call<br/>taxRules.apply(invoice)"]
    n3(["end"])
    n0 --> n1
    n1 --> n2
    n2 --> n3
    classDef unknown fill:#fff7e6,stroke:#b45309,color:#7c2d12,stroke-width:2px,stroke-dasharray:6 4
    class n2 unknown
```

## Unknown nodes

- `unresolved-call`: `taxRules.apply(invoice)` at `billing/src/main/kotlin/com/example/billing/InvoiceWorkflowImpl.kt:19`
