# Workflow diagrams

4 workflows, from commit `3f786850e387550fdab836ed7e6dc881de23001b`.

> **Warning:** diagrams marked `degraded` were made without the build of the analysed repository, so calls into other modules and libraries may be missing or unresolved.

| Workflow | Implementation | Model | Nodes | Unknown | Diagram |
|---|---|---|---:|---:|---|
| `FulfilmentWorkflow` | `com.example.fulfilment.FulfilmentWorkflowImpl` | gradle | 34 | 2 | [mermaid](FulfilmentWorkflow.md) |
| `InvoiceWorkflow` | `com.example.billing.InvoiceWorkflowImpl` | `degraded` | 4 | 1 | [mermaid](InvoiceWorkflow.md) |
| `` Odd\|Workflow<T> #1 *beta* `v2` `` | ``com.example.odd.`Odd Workflow`.Impl<T>`` | gradle | 22 | 1 | [mermaid](odd-workflow.md) |
| `OrderWorkflow` | `com.example.orders.OrderWorkflowImpl` | gradle | 23 | 1 | [mermaid](OrderWorkflow.md) |

## Mermaid notation

- **Rounded ends**: where a flow starts and where it stops (`start`, `end`, `continueAsNew`, `fail`).
- **Rectangle**: an activity, by its activity type; `(local)` marks a local activity. A prefix names other
  steps: `nexus:`, `signal external:`, `side effect:`, `compensation:`.
- **Rectangle with double sides**: a child workflow. Its own diagram is linked below the diagram.
- **Hexagon**: the workflow waits, for a timer or until the listed properties change (`await`).
- **Diamond**: a branch, or a version gate with its change id. Conditions are on the arrows.
- **Box**: a loop, with its `loop` node and an arrow back to it; a `parallel` section, with one inner box per
  branch between `fork` and `join`; the steps a compensation runs; or the signal, query and update handlers.
- **Dashed, coloured rectangle**: a call that could not be resolved, with the reason. The page lists
  where each one is.
- **Dotted arrow**: a handler changes a property that the step it points to is waiting on.
- **Labelled arrow**: a condition, the type of a caught exception, or `timeout`.
