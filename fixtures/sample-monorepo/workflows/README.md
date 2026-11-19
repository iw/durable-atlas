# workflows

Temporal workflows written in Kotlin against the Temporal Java SDK's blocking `Workflow.*` API.
The module exists to be analysed and diagrammed, so each workflow isolates one thing, and this
file records what a static analysis of each workflow is expected to find.

## Layout

| Package (under `com.example.fixture.workflows`) | Contents |
|---|---|
| `primitives` | One workflow per Temporal primitive |
| `controlflow` | One workflow per Kotlin control-flow construct |
| `handlers` | `ApprovalWorkflow`: signal, query, update and update-validator handlers |
| `inlining` | `HelperInliningWorkflow`: calls into the `helpers` module that can all be followed |
| `order` | `OrderWorkflow` (the composite) and its child, `Shipment` |
| `hardcases` | Four workflows that a static analysis cannot fully resolve |
| `nexus` | Handler for the Nexus service used by `NexusOperationWorkflow` (not a workflow) |
| `worker` | `FixtureWorkers.kt`: registers everything on the task queues `fixture`, `orders`, `shipping`. `ServerRun.kt`: runs the workflows on a Temporal server (see "Running on a server"); it is no workflow code |

Other modules: `activities` (activity interfaces and implementations), `helpers` (library the
workflows call through), `nexus-api` (the `GreetingService` Nexus interface; imports `io.nexusrpc`
only), `unrelated` (no Temporal code at all).

Each workflow file holds the `@WorkflowInterface` and its implementation, except `ApprovalWorkflow`
and `OrderWorkflow`, whose implementations are in `...Impl.kt` next to the interface.

## Names

These are the names the SDK registers and writes into event histories.

### Workflow types

The workflow type is the interface's simple name, with one exception:
`ShipmentWorkflow` declares `@WorkflowMethod(name = "Shipment")`, so its type is `Shipment`.

### Activity types

| Interface | Method | Activity type | Rule |
|---|---|---|---|
| `InventoryActivities` | `reserveStock` | `ReserveStock` | method name, first letter capitalised |
| `InventoryActivities` | `release` | `ReleaseStock` | `@ActivityMethod(name = ...)` |
| `InventoryActivities` | `checkAvailability` | `CheckAvailability` | default |
| `PaymentActivities` (`namePrefix = "Payment"`) | `charge` | `PaymentCharge` | prefix + capitalised method name |
| `PaymentActivities` | `refund` | `PaymentRefund` | prefix + capitalised method name |
| `PaymentActivities` | `capture` | `CapturePayment` | `@ActivityMethod(name = ...)` replaces the prefix too |
| `ShippingActivities` | `shipItem` | `ShipItem` | default |
| `ShippingActivities` | `shipExpress` | `ShipExpress` | default |
| `ShippingActivities` | `quoteShipping` | `QuoteShipping` | default |
| `NotificationActivities` (`namePrefix = "Notify"`) | `email` | `NotifyEmail` | prefix + capitalised method name |
| `NotificationActivities` | `sms` | `NotifySms` | prefix + capitalised method name |
| `NotificationActivities` | `audit` | `NotifyAudit` | prefix + capitalised method name |

### Handlers

| Workflow | Method | Kind | Name | Reads | Writes |
|---|---|---|---|---|---|
| `AwaitWorkflow` | `release` | signal | `release` | | `released` |
| `ApprovalWorkflow` | `onApprove` | signal | `approve` (explicit) | `approvals` | `approvals` |
| `ApprovalWorkflow` | `reject` | signal | `reject` | | `rejectionReason` |
| `ApprovalWorkflow` | `currentStatus` | query | `status` (explicit) | `approvals`, `rejectionReason`, `requiredApprovals` | |
| `ApprovalWorkflow` | `approvalCount` | query | `approvalCount` | `approvals` | |
| `ApprovalWorkflow` | `setRequiredApprovals` | update | `set-required-approvals` (explicit) | `requiredApprovals` | `requiredApprovals` |
| `ApprovalWorkflow` | `validateRequiredApprovals` | update validator | `set-required-approvals` | | |
| `ApprovalWorkflow` | `addNote` | update | `addNote` | `lastNote` | `lastNote` |
| `OrderWorkflow` | `paymentReceived` | signal | `payment-received` (explicit) | | `paymentReference` |
| `OrderWorkflow` | `cancelOrder` | signal | `cancelOrder` | | `cancelReason` |
| `OrderWorkflow` | `status` | query | `order-status` (explicit) | `status` | |
| `OrderWorkflow` | `changeAddress` | update | `change-address` (explicit) | `shippingAddress` | `shippingAddress` |
| `OrderWorkflow` | `validateAddress` | update validator | `change-address` | `status` | |

Reads and writes are properties of the workflow class. `approvals += 1` reads what it writes. An
activity stub is no state: `addNote` calls one and does not read it.

Default signal, query and update names are the method names, unchanged.

### Nexus

Service `GreetingService` (default: the interface's simple name), operations `greet` (default: the
method name) and `say-goodbye` (`@Operation(name = ...)` on `farewell`).

## Expected analysis results

Every workflow has a `start` and an `end` node; the tables list everything else. Durations are
written as ISO-8601. "Dropped" means the construct holds no Temporal primitive and should leave no
node behind. No workflow outside the hard cases should produce an `unknown` node.

Every handler is an entry point with a graph of its own, which has its own `start` and `end`. A
handler that holds no primitive is just those two nodes. A signal or update handler that writes a
property an `await` reads has a `wakes` edge to that `await`.

A call of a function whose source is in this repository is followed, and what the function does
appears where it is called: its nodes point at the lines of the function, in whichever module
that is. A function that holds no primitive leaves nothing behind.

A `branch` made from an `if` is labelled with the condition, and its edges with `yes` and `no`; one
made from a `when` is labelled with the subject, and its edges with the conditions of the arms. A
`loop` is labelled `for each <variable>`, `while <condition>` or `do while <condition>`. An `if`
that only asks how an `await` ended or which version a `versionGate` found is no `branch`: its arms
leave the `await` or the `versionGate` on edges named after the outcome. `loop`, `parallel` and
`compensation` nodes are regions: the nodes inside name the region as their parent. An edge from a
node to the region it is in leads to the end of that region (the end of the round of a loop); none
of the workflows here needs one, because no branch inside a region has an arm that does nothing.

### Primitives

| Workflow type | Exercises | Expected nodes |
|---|---|---|
| `TypedActivityWorkflow` | Calls on typed activity stubs; all three activity naming rules | `activity` ×4 in sequence: `ReserveStock`, `PaymentCharge`, `CapturePayment`, `ReleaseStock` |
| `LocalActivityWorkflow` | Local and ordinary stubs of the same interface | `localActivity` `CheckAvailability`, then `activity` `ReserveStock`. The `if (available) 1 else 0` argument is dropped |
| `UntypedActivityWorkflow` | `ActivityStub.execute` with a string literal and with a `const val` | `activity` `ReserveStock`, then `activity` `ReleaseStock` |
| `ParentWorkflow` | Call on a child workflow stub | `childWorkflow` `GreetingChildWorkflow` |
| `GreetingChildWorkflow` | A workflow with no primitive | none: `start` connects straight to `end` |
| `SignalExternalWorkflow` | Signal method on an external workflow stub | `signalExternal` `approve` (the name comes from `ApprovalWorkflow.onApprove`) |
| `TimerWorkflow` | `Workflow.sleep(Duration)`, `Workflow.sleep(Long)`, `Workflow.newTimer` | `timer` ×3: `PT30S`, `PT1S`, `PT5M`. The `.get()` on the timer promise joins nothing |
| `AwaitWorkflow` | `Workflow.await` with and without timeout | `await` reading `released` (no timeout); `await` reading `released` with timeout `PT10M`; on its `timeout` edge a `timer` `PT1S`, on its `released` edge `end`. The `if (!releasedAgain)` is no `branch`. Handler `release` wakes both awaits |
| `ParallelWorkflow` | `Async.function`, `Async.procedure`, `Promise.allOf`, `Promise.anyOf`, `Promise.get` | `parallel` ×3 in sequence: {`ReserveStock`, `QuoteShipping`} joined by `allOf`; {`NotifyEmail`, `NotifySms`} joined by `anyOf`; {`NotifyAudit`} joined by `get`. Five `activity` nodes. The final `reservation.get()` reads an already joined promise |
| `VersionGateWorkflow` | `Workflow.getVersion` | `versionGate` with change id `express-shipping` (min `DEFAULT_VERSION` = -1, max 1); `activity` `ShipItem` on the `version == Workflow.DEFAULT_VERSION` side (edge `default`), `activity` `ShipExpress` on the other (edge `v1`). No `branch` |
| `ContinueAsNewWorkflow` | `Workflow.continueAsNew` and a continue-as-new stub call | `timer` `PT1S`; `branch` (`iteration >= limit`) to `end`; `branch` (`iteration % 2 == 0`) to `continueAsNew` (static form); otherwise `continueAsNew` (stub form). Two `continueAsNew` terminals |
| `SideEffectWorkflow` | `Workflow.sideEffect`, `Workflow.mutableSideEffect` | `sideEffect` ×2 (the second has id `batch-size`) |
| `NexusOperationWorkflow` | Nexus service stub call, and `Workflow.startNexusOperation` | `nexusOperation` ×2: `GreetingService` / `greet`, then `GreetingService` / `say-goodbye` |
| `SagaWorkflow` | `Saga.addCompensation`, `Saga.compensate` | `activity` ×3 in sequence: `ReserveStock`, `PaymentCharge`, `ShipItem`. The two `addCompensation` calls are no nodes. An `exception` edge `ActivityFailure` from each of the three to one `compensation` region (the `compensate()` call), then `fail` `ActivityFailure` (rethrow). The region holds what was registered, last first: `activity` `PaymentRefund`, then `activity` `ReleaseStock`. They must be present as `activity` nodes, because they are scheduled when compensation runs |
| `ApplicationFailureWorkflow` | `throw ApplicationFailure` | `branch` (`amountCents <= 0`) to `fail` `InvalidAmount` (the failure type); otherwise `activity` `PaymentCharge` |
| `MethodReferenceWorkflow` | `Async.function`, `Async.procedure` and `Saga.addCompensation` given a reference to a stub's method and its arguments (`inventory::reserveStock`) instead of a lambda | `parallel` {`ReserveStock`, `NotifyEmail`} joined by `allOf`; `activity` `PaymentCharge`. An `exception` edge `ActivityFailure` from each of the three activities to a `compensation` region holding `activity` `ReleaseStock`, then `fail` `ActivityFailure` (rethrow) |

### Control flow

| Workflow type | Exercises | Expected nodes |
|---|---|---|
| `BranchWorkflow` | `if`/`else`, `when` | `branch` (`urgent`): `activity` `NotifySms`, else `timer` `PT1M`. `branch` (`channel`): `"email"` to `activity` `NotifyEmail`, `"sms"` to `activity` `NotifySms`, `else` to nothing. The last `if` is dropped. Two `branch` nodes in total |
| `LoopWorkflow` | `for`, `while`, `do-while`, `forEach`, `map` | `loop` ×5, each with one child: `activity` `ReserveStock`; `timer` `PT10S`; `activity` `CheckAvailability`; `activity` `ShipItem`; `activity` `QuoteShipping`. The final `for` is dropped |
| `TryCatchWorkflow` | `try`/`catch`/`finally` with two catch clauses | `activity` `PaymentCharge`; `exception` edge `ActivityFailure` to `activity` `NotifyEmail`; `exception` edge `CanceledFailure` to `fail` (rethrow). The `finally` body, `activity` `NotifyAudit`, is on each of the three exit paths: one copy for the two paths that return, one for the rethrow |
| `EarlyReturnWorkflow` | Guard clause, early `return` | `branch` (`sku.isBlank()`) to `end`; `activity` `CheckAvailability`; `branch` (`!available`) to `activity` `NotifyEmail`, then `end`; `activity` `ReserveStock`. The guard clause has no primitive in its arm but must be kept, because it bypasses the rest of the flow |
| `ScopeFunctionWorkflow` | `run`, `let`, `also`, `with`, `apply`; activity calls on an implicit receiver | `activity` ×6 in sequence: `ReserveStock`, `PaymentCharge`, `CapturePayment`, `NotifyEmail`, `NotifySms`, `ShipItem`. No `branch` or `loop` |
| `DynamicFanOutWorkflow` | `Async.function` started inside `map`, joined with `Promise.allOf(list)` | One `loop` (the `map`) and, inside it, one `parallel` region around a single `activity` `QuoteShipping`, joined by `allOf`. The `sumOf { it.get() }` afterwards reads joined promises and adds nothing |

### Handlers

| Workflow type | Exercises | Expected nodes |
|---|---|---|
| `ApprovalWorkflow` | All four handler kinds, name overrides, handler writes read by an `await` | Main flow: `activity` `NotifyEmail`; `await` with timeout `PT24H` reading `approvals`, `requiredApprovals`, `rejectionReason`; on its `timeout` edge `activity` `NotifyEmail`, then `end`; on its `rejectionReason != null` edge `end`; on its `approvals >= requiredApprovals` edge `activity` `NotifyAudit`. Both `if`s only ask how the await ended, so neither is a `branch`. Seven handler entry points (see the table above). The `addNote` handler contains `activity` `NotifyAudit`; the others contain no primitive. `wakes` edges to the `await` from `approve`, `reject` and `set-required-approvals`, and none from `addNote` |

### Calls through the helper library

| Workflow type | Exercises | Expected nodes |
|---|---|---|
| `HelperInliningWorkflow` | Calls that can all be followed: top-level function, helper class, three-level chain, extension function, interface with one implementation, private member function | `loop` with `activity` `ReserveStock` (`reserveAll`); `activity` `PaymentCharge`, `activity` `CapturePayment` (`PaymentProcessor.chargeAndCapture`); `timer` `PT5S` (`coolDown`); `activity` `QuoteShipping` (`quoteViaChain`); `activity` `NotifyEmail` (`notifyOps`); `activity` `NotifyAudit` (`AuditTrail.record`, whose only implementation is `ActivityAuditTrail`); `activity` `NotifySms` (`alertWarehouse`). All but the last point at lines of the `helpers` module. Every stub was made with the default options, also the one handed to the constructor of `ActivityAuditTrail`, so every activity has the timeout `PT30S` |

### Composite

`OrderWorkflow` (`order/OrderWorkflowImpl.kt`) combines the above. Main flow, in order:

1. `loop` over `order.lines` containing `localActivity` `CheckAvailability`, a `branch`
   (`!localInventory.checkAvailability(line.sku)`) that throws, and `activity` `ReserveStock`. The
   `addCompensation` call (registers `ReleaseStock`) is no node.
2. `activity` `NotifyEmail`.
3. `await` with timeout `PT30M` reading `paymentReference` and `cancelReason`.
4. No `branch` for `if (!signalled || cancelReason != null)`: the await's `timeout` edge and its
   `cancelReason != null` edge lead to a `compensation` region (`compensate()`, holding `activity`
   `ReleaseStock`), `activity` `NotifyEmail`, then `end`; its `paymentReference != null` edge
   leads on.
5. `activity` `PaymentCharge` and `activity` `CapturePayment`, inlined from
   `PaymentProcessor.chargeAndCapture`. The `addCompensation` call that follows (registers
   `PaymentRefund`) is no node.
6. `versionGate` with change id `order-audit` (min -1, max 1); `activity` `NotifyAudit` when
   `version >= 1` (edge `v1`; edge `default` passes it by).
7. `parallel` with two branches, `activity` `NotifyEmail` and `activity` `NotifySms`, joined by
   `allOf`.
8. `childWorkflow` `Shipment` (task queue `shipping`), then `end`.

Around all of it, `catch (e: TemporalFailure)` leads to a second `compensation` region
(`compensate()`, holding `activity` `PaymentRefund`, then `activity` `ReleaseStock`) and a `fail`
(rethrow). The `ApplicationFailure` thrown in step 1 is caught by that clause, so it is an
`exception` edge into the catch path and not a terminal of its own.

Handlers: signals `payment-received` and `cancelOrder` each have a `wakes` edge to the `await`;
query `order-status`, update `change-address` and its validator have none.

`Shipment` (`order/ShipmentWorkflow.kt`): one `loop` containing `activity` `ShipItem`.

### Hard cases

Each of these must produce exactly one `unknown` node.

| Workflow type | Why it cannot be resolved | Expected nodes |
|---|---|---|
| `ComputedActivityNameWorkflow` | `ActivityStub.execute` is given a name built at run time | `unknown`, reason `computed-name`. No `activity` node |
| `DynamicDispatchWorkflow` | `Notifier.notify` is called through an interface with two implementations | `unknown`, reason `dynamic-dispatch`, candidates `com.example.fixture.helpers.EmailNotifier` and `com.example.fixture.helpers.SmsNotifier`. The `if` that picks the implementation is dropped, and neither implementation's activity appears |
| `RecursionWorkflow` | `waitForStock` in `helpers` calls itself | Inlined once: `activity` `CheckAvailability`; `branch` (returns `true`); `branch` (`attemptsLeft <= 0`, returns `false`); `timer` `PT30S`; then `unknown`, reason `recursion`, at the self-call. A `return` in the helper leads to what follows its call, here the `end` |
| `DeepHelperChainWorkflow` | `deepChainLevel1` to `deepChainLevel10` in `helpers`; only level 10 calls an activity | The depth counts the calls that are followed. With the default of 8, levels 1 to 8 are followed and the call of `deepChainLevel9` (in `DeepChain.kt`) is the `unknown`, reason `depth-limit`; there is no `activity` node. With a depth of 10 or more: `activity` `ReserveStock` and no `unknown`. With a depth of 0 the `unknown` is the call of `deepChainLevel1` in the workflow itself |

### What must not be reported

- `unrelated` declares its own annotation named `WorkflowInterface` and an interface
  `ReportWorkflow` that carries it. Neither has anything to do with Temporal; nothing in that
  module is a workflow, and no Temporal module depends on it.
- `nexus-api` has no `io.temporal` import, but `workflows` depends on it, so it is needed to
  resolve `NexusOperationWorkflow`.
- Activity implementations (`*ActivitiesImpl`) and `GreetingServiceImpl` are boundaries: calls
  are not followed into them.

### Method references

Kotlin method references passed to `Async.function`, `Async.procedure` or `Saga.addCompensation`
(for example `Async.function(inventory::reserveStock, sku, 1)`) are rejected by the SDK at run time
unless the separate `temporal-kotlin` artifact is on the classpath. This module depends on it for
that reason only; nothing here imports from it. `MethodReferenceWorkflow` uses such references,
every other asynchronous call and compensation is a lambda.

## Captured histories

The tests in `src/test` run every workflow type once in the SDK's in-memory, time-skipping test
environment, with fixed workflow ids and inputs, and save the event history as JSON
(`WorkflowExecutionHistory.toJson(true)`):

- `build/histories/<name>.json` is written on every test run.
- `../histories/<name>.json` is the checked-in copy. The tests fail when a fresh capture no longer
  contains the same events as the checked-in copy (ignoring ids, timestamps, workflow task events
  and the order of concurrent events).

To refresh the checked-in copies after changing a workflow:

```
./gradlew :workflows:test -Ddurable-atlas.updateHistories=true
```

`<name>` is the workflow type. `OrderWorkflow` has two further captures named
`OrderWorkflow.<scenario>.json`.

| File | Scenario | Events of note |
|---|---|---|
| `OrderWorkflow.json` | Two lines; update `change-address`, then signal `payment-received` while waiting for payment; completes | `LocalActivity` marker ×2, `ReserveStock` ×2, `NotifyEmail`, 30 minute timer started and cancelled, update accepted, signal, `PaymentCharge`, `CapturePayment`, `Version` marker, `NotifyAudit`, `NotifyEmail` and `NotifySms` in parallel, child `Shipment` |
| `OrderWorkflow.payment-timeout.json` | No signal; the 30 minute deadline passes; completes as cancelled | Timer fired, `ReleaseStock` ×2 (compensation), `NotifyEmail` |
| `OrderWorkflow.payment-declined.json` | One very expensive line; signal `payment-received`; the charge is declined; fails | `PaymentCharge` failed, `ReleaseStock` (compensation), workflow failed |
| `Shipment.json` | Child of the first `OrderWorkflow` capture | `ShipItem` ×2 |
| `TypedActivityWorkflow.json`, `UntypedActivityWorkflow.json`, `ScopeFunctionWorkflow.json`, `HelperInliningWorkflow.json` | Straight through | Activities only (`HelperInliningWorkflow` also one timer) |
| `LocalActivityWorkflow.json` | Straight through | `LocalActivity` marker, `ReserveStock` |
| `ParentWorkflow.json`, `GreetingChildWorkflow.json` | Parent and its child | Child initiated, started, completed |
| `SignalExternalWorkflow.json`, `ApprovalWorkflow.json` | The approval gets updates `set-required-approvals` (2) and `addNote`, one `approve` signal from `SignalExternalWorkflow` and one from a client; approved | Signal external initiated; two updates accepted, two signals, 24 hour timer cancelled |
| `TimerWorkflow.json` | Straight through | Three timers |
| `AwaitWorkflow.json` | One `release` signal; the second await times out | Signal, 10 minute timer fired, 1 second timer |
| `ParallelWorkflow.json`, `DynamicFanOutWorkflow.json` | Straight through (three SKUs for the fan-out) | The activities of each parallel region are scheduled together |
| `VersionGateWorkflow.json` | New code path (version 1) | `Version` marker, `ShipExpress` |
| `ContinueAsNewWorkflow.json` | First run of a chain of three | 1 second timer, continued as new |
| `SideEffectWorkflow.json` | Straight through | `SideEffect` and `MutableSideEffect` markers |
| `NexusOperationWorkflow.json` | Straight through | Two Nexus operations scheduled and completed |
| `SagaWorkflow.json` | The charge is declined; fails | `ReserveStock`, `PaymentCharge` failed, `ReleaseStock`, workflow failed |
| `MethodReferenceWorkflow.json` | The charge is declined; fails | `ReserveStock` and `NotifyEmail` in parallel, `PaymentCharge` failed, `ReleaseStock` (compensation), workflow failed |
| `ApplicationFailureWorkflow.json` | Amount 0; fails | Workflow failed, no activity |
| `BranchWorkflow.json` | `channel = "email"`, `urgent = true` | `NotifySms`, `NotifyEmail` |
| `LoopWorkflow.json` | Two SKUs | `ReserveStock` ×2, 10 second timer ×2, `CheckAvailability`, `ShipItem` ×2, `QuoteShipping` ×2 |
| `TryCatchWorkflow.json` | The charge is declined; completes with `charge-failed` | `PaymentCharge` failed, `NotifyEmail`, `NotifyAudit` |
| `EarlyReturnWorkflow.json` | Out-of-stock SKU | `CheckAvailability`, `NotifyEmail` |
| `ComputedActivityNameWorkflow.json` | `shippingMode = "express"` | `ShipExpress`, which has no node in the static result |
| `DynamicDispatchWorkflow.json` | `channel = "sms"` | `NotifySms`, which has no node in the static result |
| `RecursionWorkflow.json` | Out-of-stock SKU; all six attempts fail | `CheckAvailability` ×6, 30 second timer ×5 |
| `DeepHelperChainWorkflow.json` | Straight through | `ReserveStock`, which has no node in the static result at depth 8 |

## Running on a server

`worker/ServerRun.kt` runs the same executions on a real Temporal server, so that the server
holds histories of every workflow type: every path listed above, with the same inputs, signals
and updates.

```
./gradlew :workflows:runOnServer --args="--build-id build-a --build-id build-b"
```

- The server is the one at `TEMPORAL_ADDRESS` (by default `127.0.0.1:7233`), the namespace
  `TEMPORAL_NAMESPACE` (by default `default`). The namespace must exist. The Nexus endpoint
  `fixture-greeting-endpoint`, which `NexusOperationWorkflow` calls through, is created if it is
  missing.
- Each `--build-id` is one round. The workers of a round are a Worker Deployment Version of the
  deployment `sample-monorepo` (`--deployment` names another) with that build id, which is made
  the deployment's current version before the round starts. Workflows move with the deployment
  (`AUTO_UPGRADE`). Without a build id there is one round, run by workers without a version.
- A real server does not skip time. The first round runs everything and takes as long as the
  longest timer, the 30 minutes `OrderWorkflow` waits for a payment. Later rounds leave out the
  four executions that wait minutes for a timer (`TimerWorkflow`, `AwaitWorkflow`,
  `RecursionWorkflow` and the `OrderWorkflow` whose payment never arrives) and take about a
  minute; `--quick` leaves them out of the first round too.
- Workflow ids are those of the tests (`<WorkflowType>-1`; `order-1001` for the paid order), with
  `order-1002` for the order that is never paid and `order-1003` for the one whose payment is
  declined. Rounds reuse the ids; run ids tell the executions apart.
- Before each change of build id an `ApprovalWorkflow` is started and given its two updates
  (`ApprovalWorkflow-handover-<build id>`); its two `approve` signals are sent after the change.
  Its workflow tasks are therefore completed by workers of both versions.

One line is printed per execution: path, workflow id, run id and how it ended. The exit status
is 1 if an execution did not end the way its path should.
