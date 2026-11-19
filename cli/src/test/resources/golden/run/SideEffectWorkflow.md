# SideEffectWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `SideEffectWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.SideEffectWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.SideEffectWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 4 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n849a51754f["side effect#58; region"]
    na8cc404c35["side effect#58; batch-size"]
    nedd528afe0(["end"])
    n696bb56a3b --> n849a51754f
    n849a51754f --> na8cc404c35
    na8cc404c35 --> nedd528afe0
```
