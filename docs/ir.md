# The workflow IR

`analyse` writes one IR document per workflow type, `<workflow>.ir.json`. It is the graph of
that workflow as the analysis read it, and the only thing the renderers and the overlay know
of the workflow: a diagram shows what its document says, and nothing else.

The format is defined by a JSON Schema (draft 2020-12),
[`workflow-ir.schema.json`](../ir/src/main/resources/io/github/iw/durableatlas/ir/workflow-ir.schema.json),
which describes every field. This page explains how the pieces fit together. The worked
example is [`demo/expected/OrderWorkflow.ir.json`](../demo/expected/OrderWorkflow.ir.json),
the document of the [demo](../demo/README.md)'s `OrderWorkflow`: it is written by hand, and
the analyser's tests hold what the analysis makes of the demo to it.

## A document

```json
{
  "schemaVersion": 1,
  "commit": "0000000000000000000000000000000000000000",
  "modelMode": "gradle",
  "workflowType": "OrderWorkflow",
  "interface": "com.example.orders.OrderWorkflow",
  "implementation": "com.example.orders.OrderWorkflowImpl",
  "module": ":orders",
  "entryPoints": [ … ],
  "nodes": [ … ],
  "edges": [ … ],
  "stats": { "nodes": 32, "unknown": 1 }
}
```

- `schemaVersion` is 1.
- `commit` is the full SHA of the analysed commit (the hand-written example has zeros), and
  `modelMode` says how its model was obtained: `gradle` or `degraded`.
- `workflowType` is the name Temporal knows the workflow by. A document describes the type
  as one class implements it: `interface` and `implementation` are fully qualified names,
  `module` the Gradle path of the module that holds the class (`:` in a degraded model, whose
  one module stands for the whole repository). A class that implements the SDK's
  `DynamicWorkflow` has no type of its own; its document is called `dynamic-` and the simple
  name of the class.
- `stats` counts the nodes, and those of kind `unknown`.
- `skippedCalls`, only in a document of a degraded model and only where there are any, lists
  the calls that did not resolve and were taken for calls into libraries the model lacks:
  `{ "loc": { "file", "line" }, "text" }` each. No node stands for such a call.
- `detail`, after `modelMode`, says how much of what the analysis could not read the
  document shows. `"overview"`: the document has no node for a call behind which a step is
  only possible ([Overviews](#overviews)). Absent, or `"full"`: every such call has its
  `unknown` node. The analyser writes the field only into an overview; the worked example is
  a full document.
- `leftOutCalls`, only in an overview and only where there are any, lists the calls it has no
  node for: `{ "loc": { "file", "line" }, "reason", "label", "candidates" }` each, what the
  `unknown` node of the full document says, without its id. `candidates` is there for
  `unseen-code` alone.

A document is flat: a list of nodes and a list of edges. Nesting is said by a node's
`parent`, and each entry point's graph begins at its `start` node.

Documents are written in a canonical order (entry points by kind and id, nodes by id, edges
by `from`, `to`, kind and label, lists of names sorted), with two spaces of indentation and
a trailing newline, so equal graphs are equal files.

## Entry points

A workflow has one graph for its workflow method and one for each handler.

```json
{ "id": "main", "kind": "workflowMethod", "name": "OrderWorkflow", "start": "n00-start" },
{ "id": "signal-approvePayment", "kind": "signal", "name": "approvePayment",
  "start": "signal-approvePayment.start", "writes": ["paid"] }
```

| Kind | What it is |
| --- | --- |
| `workflowMethod` | The workflow method. `name` is the workflow type. |
| `signal`, `query`, `update` | A handler, by the name Temporal knows it by. `(any name)` for a handler that takes every signal, query or update without one of its own. |
| `updateValidator` | The validator of the update of the same name. |

`reads` and `writes` name the workflow properties the handler reads and writes, itself or in
the functions it calls. They are how the diagram knows which handler can end which wait.
Every entry point keeps its `start` and `end` nodes, also a handler whose body has no step.

## Nodes

Every node has an `id` and a `kind`. Ids are derived from what a node is (its entry point,
its place in the source, the calls that were followed to reach it), not from the order of
discovery, so an unchanged workflow keeps its ids from one commit to the next. The analyser
writes ids like `n1f4259515a`; any non-empty string is valid, and the example uses readable
ones.

| Kind | What it is | Fields it carries |
| --- | --- | --- |
| `start`, `end` | Where an entry point's graph begins, and where it completes. | |
| `activity`, `localActivity` | An activity call through a stub. | `name` (the activity type), `timeout`, `taskQueue` |
| `childWorkflow` | A child workflow. | `name` (the workflow type), `interface` where the type alone is ambiguous, `waitsFor`, `parentClosePolicy` |
| `signalExternal` | A signal to another workflow. | `name` (the signal) |
| `cancelExternal` | A request to another workflow to cancel. | `name` (its workflow type, where the stub says it) |
| `nexusOperation` | A Nexus operation. | `service`, `name` (the operation), `timeout` |
| `timer` | `Workflow.sleep`, a timer. | `duration` |
| `await` | A wait: for a condition, for a workflow queue or a promise to be given something, for a lock or semaphore to be free. | `reads` (what it waits on), `timeout` |
| `sideEffect` | A side effect. | |
| `versionGate` | `Workflow.getVersion`. | `changeId` |
| `upsertSearchAttributes`, `upsertMemo` | The workflow sets or removes search attributes of its own, or entries of its memo. | `keys`, `otherKeys` |
| `branch` | A place where the flow takes one of several ways: an `if`, a `when`, a call that may throw. | |
| `loop` | A region: what it holds runs repeatedly. | `retry` |
| `parallel` | A region: its lanes run side by side. | `waitsFor`, `join` |
| `compensation` | A region: the steps a saga runs to compensate. | |
| `cancelScope` | The place where a cancellation scope is cancelled. | |
| `continueAsNew` | The execution continues as new. | `name` (the workflow type) |
| `fail` | The execution fails; in an update handler, the update fails. | `name` (what the failure is called) |
| `unknown` | A call the analysis could not read. | `reason`, `candidates` |

Any node may have a `label`, which is its display name when set (otherwise `name` is); a
`parent`, the id of the region that holds it; and `loc`, the file (relative to the root of
the analysed repository) and line it was read from.

Durations (`duration`, `timeout`) are ISO-8601, as `java.time.Duration` prints them:
`PT30M`, `PT336H`. Renderers show a short form (`30M`, `14D`).

### Optional fields

A field is written only where it says something; its absence is the common case.

| Field | On | Says |
| --- | --- | --- |
| `waitsFor` | a step, a `parallel` region | The flow does not wait for this work to end: `nothing`, it goes on at once; `start`, it goes on once the child workflow has started. |
| `join` | a `parallel` region | `first`: the flow goes on when the first lane has ended, as with `Promise.anyOf`. The other lanes run on. |
| `parentClosePolicy` | `childWorkflow` | `abandon`: the child outlives this workflow. |
| `interface` | `childWorkflow` | The workflow interface the stub was made for, where another interface of the sources registers the same type. |
| `keys`, `otherKeys` | the two upsert kinds | The names written, in code order. `otherKeys: true` says they are not all: the step writes further names the analysis could not tell. No `keys` means no name is known. |
| `retry` | `loop` | `true`: the loop is a retry. What it holds runs once, and again while it fails and the retry options allow. |
| `reason`, `candidates` | `unknown` | Why the call could not be read, and what it may run. |

### Unknown nodes

An `unknown` node marks a call behind which a step is certain or possible and which the
analysis could not follow. Its `reason` says why:

| Reason | Meaning |
| --- | --- |
| `depth-limit` | The call lies deeper than calls are followed (`--max-depth`), and a step lies behind it. |
| `recursion` | The call leads back into a function that is already being followed and holds a step. |
| `dynamic-dispatch` | The call may run one of several functions of which one holds a step (`candidates` names the classes), or a function that no class of the sources implements. |
| `computed-name` | The name of the step is only known at run time. |
| `unsupported` | The construct is not modelled. |
| `unresolved` | The call did not resolve to a declaration. `candidates` names the functions of the sources the compiler could not choose among. |
| `unseen-code` | The call leads to a function without a body that no class of the sources implements, so what runs there cannot be seen. `candidates` names such functions. |

### Overviews

A document whose `detail` is `overview` leaves out the calls behind which a step is only
possible, and lists them under `leftOutCalls`:

- `unsupported`, where the call was handed code. What the code holds stands in the flow where
  the call is made, once, in the order of the code. A full document has the node in front of
  it, and an edge from the node past each block.
- `unseen-code`, and `dynamic-dispatch` without `candidates`.

So the `unknown` nodes of an overview have the reasons `depth-limit`, `recursion`,
`computed-name`, `unresolved`, `dynamic-dispatch` with `candidates`, and `unsupported` only
for a workflow method or a handler without a Kotlin body. Each has a `label` that names the
function called; in a full document a `dynamic-dispatch` node has none. One exception keeps a
node of the other reasons: a loop that the flow never gets past, and that would be left
empty, stays as the full document has it.

A `branch` none of whose ways holds anything else is gone with what it held, and so is a
`loop` or a `parallel` region. Every other node of an overview is a node of the full
document of the same sources, with the same `id` and fields, and no way leads from one of
them to the next that the full document does not have. A clause or a copy of a `finally`
block that only a failure of a call which is left out reaches is still there: its
`exception` edge starts where the flow stood at the call.

## Edges

```json
{ "from": "n07-await-payment", "to": "n12-shipments", "label": "paid" },
{ "from": "n07-await-payment", "to": "n08-compensation", "kind": "timeout" },
{ "from": "signal-approvePayment", "to": "n07-await-payment", "kind": "wakes" }
```

An edge has `from`, `to`, a `kind` (`flow` when absent) and an optional `label`: the outcome
or condition that takes it, or the type of failure caught.

| Kind | What it is |
| --- | --- |
| `flow` | A way the flow takes. |
| `exception` | The way a failure takes from a step to the clause that catches it; `label` is the caught type. |
| `timeout` | The way an await takes when its time is up. |
| `wakes` | Not a way of the flow. It leads from an entry point (its id, not a node) to an await it can end: from a handler that writes what the await reads, or from the workflow method where its own code ends the wait from another lane. |
| `cancels` | Not a way of the flow. It leads from a `cancelScope` node to what that scope holds: a step, or a region for all it holds. |

`wakes` and `cancels` edges may join any two nodes, also across entry points. They never
count for the rules below.

An `if` or `when` that only tests the outcome of a wait is not a `branch` of its own: it is
folded into the edges that leave the await. In the example the await `paid or cancelled`
has three ways out, `paid`, `cancelled` and a `timeout` edge. In the same way the versions of
a version gate are its edges `v1` and `v2`.

## Regions and implied edges

A `loop`, a `parallel` section and a `compensation` are regions: nodes whose children name
them as `parent`. A region is **one step of the flow around it**, and four rules say how its
edges read. Every producer and reader of the IR follows them.

1. **Into and out of a region.** An edge into a region ends at the region's node. An edge
   that leaves the region once it is done starts at the region's node.

   ```json
   { "from": "n07-await-payment", "to": "n12-shipments", "label": "paid" },
   { "from": "n12-shipments", "to": "n15-invoice-number" }
   ```

2. **Inside a region, only edges between its children are written.** Entering and leaving
   are implied:

   - a child that no sibling leads to is where the region is entered;
   - a child that does not end the flow goes on to the end of the region, unless one of its
     own edges says where it goes. In a loop the end of the region is the end of a round:
     back to the loop's head. In a parallel region it is the end of the lane: the join. In a
     compensation it is the end of the chain.

   The example's loop holds one edge, `n13-shipment-workflow → n14-shipment-ready`. That the
   child workflow is the first step of a round and that the signal ends the round is implied.

   What stops the implied way out is an edge of **any kind** to a sibling, to the child's own
   region or to a region further out, or an edge of kind **`flow`** to a node outside the
   region. An `exception` or `timeout` edge to a node outside does not stop it: the step can
   still complete and go on.

3. **An edge from a child straight to a node outside its region is written out**: an early
   `return`, a `break`, a `throw` that fails the workflow.

4. **An edge from a node to a region that holds it**, its own or one further out, means "to
   the end of that region", leaving every region in between: a `continue`, a branch arm that
   skips the rest of a round, the end of a lane. It keeps its kind and label. To the node's
   own region it is written only where the implied way out would not say it (it has a label,
   is no plain flow edge, or the node has another edge that stops the implied one); to a
   region further out it is always written. It is the only kind of edge between a region and
   a node inside it.

The lanes of a `parallel` region are the connected groups of its children: in the example,
`n05-reserve-stock` and `n06-authorise-payment` have no edge between them and are two lanes.

A `parallel` region with `waitsFor` is not waited for: its lanes are started, the flow goes
on with the edges that leave the region, and nothing follows where the lanes end. One with
`join: first` is left when its first lane has ended.

Renderers draw the implied edges where their notation needs them (Mermaid draws an arrow from
a loop's last step back to its head; SVG lets the box say it), and the overlay counts them.

## Checking a document

`IrDocument.validate()` in the `ir` module checks what the schema cannot: that ids are
unique, that every entry point starts at a `start` node, that every edge and parent names a
node that exists and every parent is a region, that optional fields stand only on the kinds
they belong to, and that `stats` counts what the nodes give. `render` and `overlay` refuse a
document that contradicts itself.

## Changing the format

`schemaVersion` stays 1 while older documents remain valid. A new field is optional, a new
node or edge kind is added only where nothing that exists says the thing truthfully, and
each addition is described in the schema. [AGENTS.md](../AGENTS.md#ir-changes) lists what a
change has to come with.
