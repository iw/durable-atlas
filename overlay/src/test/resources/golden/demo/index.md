# Workflow coverage

5 executions of 1 workflow type.

| Workflow | Executions | Nodes hit | Events mapped | Unmapped | Uncertain | Coverage | Diagram |
|---|---:|---:|---:|---:|---:|---|---|
| `OrderWorkflow` | 5 | 30 of 30 | 95 | 1 | 0 | [svg](OrderWorkflow.coverage.svg) · [mermaid](OrderWorkflow.coverage.md) · [json](OrderWorkflow.coverage.json) | [svg](OrderWorkflow.svg) |

## `OrderWorkflow`

| History | Workflow id | Status | Events mapped | Unmapped | Uncertain | Diagram |
|---|---|---|---:|---:|---:|---|
| `OrderWorkflow.cancelled.json` | – | failed | 16 | 0 | 0 | [svg](executions/OrderWorkflow.cancelled.svg) · [mermaid](executions/OrderWorkflow.cancelled.md) |
| `OrderWorkflow.continue-as-new.json` | – | continued as new | 21 | 0 | 0 | [svg](executions/OrderWorkflow.continue-as-new.svg) · [mermaid](executions/OrderWorkflow.continue-as-new.md) |
| `OrderWorkflow.paid.json` | – | completed | 22 | 0 | 0 | [svg](executions/OrderWorkflow.paid.svg) · [mermaid](executions/OrderWorkflow.paid.md) |
| `OrderWorkflow.promotion.json` | – | completed | 21 | 1 | 0 | [svg](executions/OrderWorkflow.promotion.svg) · [mermaid](executions/OrderWorkflow.promotion.md) |
| `OrderWorkflow.timeout.json` | – | failed | 15 | 0 | 0 | [svg](executions/OrderWorkflow.timeout.svg) · [mermaid](executions/OrderWorkflow.timeout.md) |

Events without a node:

- `OrderWorkflow.promotion.json`, event 68: `ActivityTaskScheduled` `LookUpPromotion`; may lie behind `n17-discount-policy`

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
