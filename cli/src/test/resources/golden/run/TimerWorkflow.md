# TimerWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `TimerWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.TimerWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.TimerWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 5 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    neb81874bc3{{"timer 30s"}}
    n4b6607a4c0{{"timer 1s"}}
    n7eca90a3ef{{"timer 5m"}}
    nedd528afe0(["end"])
    n696bb56a3b --> neb81874bc3
    neb81874bc3 --> n4b6607a4c0
    n4b6607a4c0 --> n7eca90a3ef
    n7eca90a3ef --> nedd528afe0
```
