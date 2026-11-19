# SignalExternalWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `SignalExternalWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.SignalExternalWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.SignalExternalWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 3 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n974866628f["signal external#58; approve"]
    nedd528afe0(["end"])
    n696bb56a3b --> n974866628f
    n974866628f --> nedd528afe0
```
