# Orders demo

A small order-processing service written in Kotlin against the Temporal Java SDK's blocking
`Workflow.*` API. It exists to be diagrammed: its main workflow, `OrderWorkflow`, uses most of what
a workflow can do, once each.

It is a separate Gradle build with its own wrapper, not part of the tool's build.

## Build

Requires JDK 17.

```
./gradlew build
```

The tests run `OrderWorkflow` in the SDK's in-memory, time-skipping test environment. No Temporal
service and no network are needed, apart from Gradle resolving dependencies.

## Layout

| Path | Contents |
|---|---|
| `orders/` | `OrderWorkflow` and its implementation, the child `ShipmentWorkflow`, the contract of the external `Warehouse` workflow, activities, the discount policies and the worker |
| `loyalty/` | The Nexus service `Loyalty` and its handler |
| `expected/` | `OrderWorkflow.ir.json`: the graph a static analysis of `OrderWorkflow` is expected to give |
| `histories/` | Event histories of `OrderWorkflow`, one per path, as written by the tests |

## From code to diagram

All of these are in `orders/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt`, in this order.

| Code | Diagram element |
|---|---|
| `processOrder` | Start |
| `validation.validateOrder(order)` on a local activity stub | Local activity `ValidateOrder` |
| `Workflow.getVersion("fraud-check-v2", 1, 2)` | Version gate `fraud-check-v2`, with an edge for each version: `v1`, `v2` |
| `payments.fraudCheck(order)`, on version 2 only | Activity `FraudCheck` |
| Two `Async.function` calls joined by `Promise.allOf` | Parallel region with the activities `ReserveStock` and `AuthorisePayment` |
| `Workflow.await(Duration.ofMinutes(30)) { paid \|\| cancelled }` | Await `paid or cancelled` with a timeout of 30 minutes, and the edges `paid`, `cancelled` and `timeout` |
| `saga.compensate()` inside `Workflow.newDetachedCancellationScope` | Compensation region, detached, with the activities `ReleaseStock` then `VoidPayment` (the reverse of the order in which `saga.addCompensation` registered them) |
| `throw ApplicationFailure.newFailure(..., "OrderCancelled")` | Fail `OrderCancelled` |
| `for (shipment in order.shipments)` | Loop region, for each shipment |
| `shipmentWorkflow.ship(...)` on a child workflow stub | Child workflow `ShipmentWorkflow` |
| `warehouse.shipmentReady(...)` on an external workflow stub | Signal external `Warehouse.shipmentReady` |
| `val invoiceNumber = Workflow.sideEffect(...)` | Side effect, invoice number |
| `loyalty.awardPoints(...)` on a Nexus service stub | Nexus operation `Loyalty.awardPoints` |
| `discountPolicy.discountCents(order)`, where `DiscountPolicy` has two implementations and the one used is chosen while the workflow runs | Unknown call, `dynamic-dispatch` |
| `Workflow.sleep(Duration.ofDays(14), ...)` with the summary `returns window` | Timer of 14 days, returns window |
| `if (Workflow.getInfo().isContinueAsNewSuggested)` | Branch, continue-as-new suggested? |
| `Workflow.continueAsNew(order)` | Terminal, continue-as-new (`yes`) |
| `return OrderResult(...)` | Terminal, complete (`no`) |
| `changeAddress` and `validateChangeAddress` | Update handler `changeAddress` with a validator |
| `getStatus` | Query handler `getStatus` |
| `approvePayment`, which sets `paid` | Signal handler `approvePayment`; wakes the await |
| `cancelOrder`, which sets `cancelled` | Signal handler `cancelOrder`; wakes the await |

## Histories

| File | Path taken |
|---|---|
| `OrderWorkflow.paid.json` | Address changed, payment approved, two shipments, completed after the returns window |
| `OrderWorkflow.cancelled.json` | Cancelled while waiting for the payment; compensated; failed with `OrderCancelled` |
| `OrderWorkflow.timeout.json` | No payment within 30 minutes; compensated; failed with `OrderCancelled` |
| `OrderWorkflow.promotion.json` | As the paid order, with a promotion code: the discount policy chosen for it runs the activity `LookUpPromotion`, which a static analysis cannot place |
| `OrderWorkflow.continue-as-new.json` | As the paid order, ending in continue-as-new. The test environment never suggests continue-as-new by itself, so the test sets the suggestion on the workflow tasks on their way to the worker; the stored events do not carry it |

The version 1 path of the version gate is not among them: a new execution always records the
latest version.

Every test run writes fresh histories to `orders/build/histories` and fails if one no longer
contains the same events as its copy in `histories/` (ids, timestamps, workflow task events and the
order of concurrent events aside). To replace the copies after changing the workflow:

```
./gradlew :orders:test -Ddurable-atlas.updateHistories=true
```

## Diagram

From the root of the tool's repository, once the demo is committed and the tool is installed:

```
durable-atlas run --repo demo --commit HEAD --out build/demo
```

This draws `OrderWorkflow` and `ShipmentWorkflow` as the commit has them; changes that are not
committed are not seen. It warns that no class implements `WarehouseWorkflow`, as the demo holds only
the contract of that workflow, and the one unknown node of `OrderWorkflow` is the call to the
discount policy. Everything is written to directories named after the full SHA of the commit:

| Path | Contents |
|---|---|
| `build/demo/diagrams/<sha>` | An SVG diagram and a Mermaid page per workflow type, and `index.md`, which links them |
| `build/demo/ir/<sha>` | One IR file per workflow type |
| `build/demo/model/<sha>` | The model of the commit, which the next run on it reuses |

To lay the histories over the diagram of `OrderWorkflow`:

```
durable-atlas overlay --ir "build/demo/ir/$(git rev-parse HEAD)" --history demo/histories --out build/demo/coverage
```

`build/demo/coverage/OrderWorkflow.coverage.svg` shows the paths of all five executions, and
`build/demo/coverage/executions` holds a diagram of each. One event has no node: the activity
`LookUpPromotion` in `OrderWorkflow.promotion.json`.
