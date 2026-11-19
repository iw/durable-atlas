# UntypedActivityWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `UntypedActivityWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.UntypedActivityWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.UntypedActivityWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 4 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n71a16639e0["ReserveStock"]
    n87a398f39f["ReleaseStock"]
    nedd528afe0(["end"])
    n696bb56a3b --> n71a16639e0
    n71a16639e0 --> n87a398f39f
    n87a398f39f --> nedd528afe0
```
