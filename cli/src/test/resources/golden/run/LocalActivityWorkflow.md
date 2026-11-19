# LocalActivityWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `LocalActivityWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.LocalActivityWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.LocalActivityWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 4 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n17cb2909d5["CheckAvailability (local)"]
    nb1641f9a4b["ReserveStock"]
    nedd528afe0(["end"])
    n696bb56a3b --> n17cb2909d5
    n17cb2909d5 --> nb1641f9a4b
    nb1641f9a4b --> nedd528afe0
```
