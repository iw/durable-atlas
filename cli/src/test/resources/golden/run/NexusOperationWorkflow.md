# NexusOperationWorkflow

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `NexusOperationWorkflow` |
| Interface | `com.example.fixture.workflows.primitives.NexusOperationWorkflow` |
| Implementation | `com.example.fixture.workflows.primitives.NexusOperationWorkflowImpl` |
| Module | `:workflows` |
| Commit | `0000000000000000000000000000000000000000` |
| Model mode | gradle |
| Nodes | 4 |
| Unknown nodes | 0 |

```mermaid
flowchart TD
    n696bb56a3b(["start"])
    n6f54b25be9["nexus#58; GreetingService.greet"]
    nc47f317ae4["nexus#58; GreetingService.say-goodbye"]
    nedd528afe0(["end"])
    n696bb56a3b --> n6f54b25be9
    n6f54b25be9 --> nc47f317ae4
    nc47f317ae4 --> nedd528afe0
```
