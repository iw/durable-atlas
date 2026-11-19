# ParentWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `ParentWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.ParentWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.ParentWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 3 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n2124e32d35[["GreetingChildWorkflow"]]
    nedd528afe0(["end"])
    n696bb56a3b --> n2124e32d35
    n2124e32d35 --> nedd528afe0
```

## Child workflows

- `GreetingChildWorkflow`: [GreetingChildWorkflow.md](GreetingChildWorkflow.md)
