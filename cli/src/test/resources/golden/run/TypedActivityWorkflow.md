# TypedActivityWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `TypedActivityWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.TypedActivityWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.TypedActivityWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 6 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n3c5d78278e["ReserveStock"]
    n3fdf75bb11["PaymentCharge"]
    n129648fc52["CapturePayment"]
    n0a5c8a347e["ReleaseStock"]
    nedd528afe0(["end"])
    n696bb56a3b --> n3c5d78278e
    n3c5d78278e --> n3fdf75bb11
    n3fdf75bb11 --> n129648fc52
    n129648fc52 --> n0a5c8a347e
    n0a5c8a347e --> nedd528afe0
```
