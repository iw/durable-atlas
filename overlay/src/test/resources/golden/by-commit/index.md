# Workflow coverage by commit

5 executions laid over the workflows of 2 commits.

| Commit | Worker versions | Workflow types | Executions | Events mapped | Unmapped | Coverage |
|---|---|---:|---:|---:|---:|---|
| `4f2a9c1` | `orders:build-a`, no version | 2 | 2 | 31 | 0 | [index](4f2a9c1/index.md) |
| `9b7d3e0` | `billing:build-c`, `orders:build-b` | 2 | 3 | 48 | 0 | [index](9b7d3e0/index.md) |

## Histories without a diagram

- `await-x.json`, workflow type `AwaitWorkflow`: worker version orders:build-x is not in the version map
- `timer-b.json`, workflow type `TimerWorkflow`: no workflow document for this workflow type
