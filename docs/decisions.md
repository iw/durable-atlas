# Decisions and known limitations

What Durable Atlas draws, why, and where it stops. [ir.md](ir.md) has the format these
decisions are written in; [architecture.md](architecture.md) says where each one lives in
the code.

## The principle

A diagram says what the code can do, read from the code alone. Where the analysis cannot
tell, the diagram says that it cannot: an `unknown` node with a reason, an optional field left
out, a warning on stderr. It never draws a guess as a fact, and it prefers saying less to
saying something wrong. The overview, which is the default detail, leaves out the calls
behind which a step is only possible; it counts and lists them, and
[the full detail](#overview-and-full-detail) draws each.

## What is a node

- **Steps**: what leaves an event in a workflow's history, or decides its path. Activities
  and local activities, child workflows, signals and cancel requests to other workflows,
  Nexus operations, timers, waits, side effects, version gates, upserts of search attributes
  and of the memo, continue-as-new, failures.
- **Constructs that steer the flow between steps**: an `if` or `when` (a `branch`), a loop, a
  `try` with its clauses (as `exception` edges), work started side by side (a `parallel`
  region), a saga's compensation.
- **Nothing else.** Straight-line code that is no step leaves nothing, and neither does a
  branch, a loop or a `try` that holds no step and ends no path.

Every entry point has a graph of its own: the workflow method, and each signal, query and
update handler with its validator. A handler whose body has no step is drawn as its card
alone. What making the workflow object runs (property initialisers, `init` blocks, the
constructor) belongs to the workflow method.

A node is called by its `label` when it has one, else by its `name`. The start of a workflow
reads "Start": start options are no part of the IR.

## Folding decisions into steps

An `if` or `when` that only tests the outcome of a wait is not a node of its own. It becomes
the edges that leave the await: `paid`, `cancelled`, and a `timeout` edge. The same holds
for a test of `Workflow.getVersion`'s result, which becomes the gate's edges `v1`, `v2`.
Folds chain (a second `if` on the same outcome folds too), but only while no arm with
content is left without an outcome. Version 0 is left out when the gate's minimum is the
default version.

`a ?: throw`, `a && b` and `a || b` are branches when the right-hand side holds a step or
ends the path.

## Functions the workflow calls

A workflow rarely holds all its steps in one function. Calls into functions of the analysed
sources are followed, up to `--max-depth` calls deep (8 by default), and what they hold is
drawn where they are called.

**A function is drawn from the inside only where a step is certain to lie in it or behind
it**: a call that is a node of its own, a compensation registered or run, a handler
registered. A function that only computes is not worth a region of the diagram.

A call of a function that is not drawn leaves nothing, with two exceptions:

- **It may throw something that matters.** If the function may throw what fails the
  execution, or what a `catch` clause that draws anything may take, the call becomes a
  `branch` labelled `<Function> throws?`, with `exception` edges to those clauses, a `no`
  edge onward and, where a failure can get past the clauses, a `yes` edge to a `fail` node.
  What a `try` inside the function is sure to catch does not count. A call that never
  returns is the `fail` node without a branch.
- **It runs code that cannot be seen.** If it calls a function without a body that no class
  of the sources implements, an `unknown` node with the reason `unseen-code` stands at the
  call.

A function that is handed code holding a step (a lambda) is drawn, and the code stands where
the function runs it.

A function that is not drawn is still read for what it does to the workflow's state: the
properties it writes, the activity options it sets. `reads`, `writes` and `wakes` edges do
not depend on what is drawn.

## What fails a workflow

In the Temporal Java SDK an exception that leaves workflow code does one of two things. A
`TemporalFailure` (an `ApplicationFailure`, say), or an exception of a type the worker was
told to fail workflows on, **fails the execution**. Any other exception **fails the workflow
task**: the task is retried until the code is fixed, and the execution neither fails nor
goes on.

A `fail` node says the first: the execution fails. So:

- A `throw` leads to a `fail` node if what it throws is a `TemporalFailure`, is of a type
  handed to `setFailWorkflowExceptionTypes`, may be such a type (a variable of type
  `Exception`), or is of a type the analysis does not know.
- The fail types are read wherever the sources set them, in Kotlin or Java: the builder of
  `WorkflowImplementationOptions`, its Kotlin DSL, its constructor. They hold for every
  workflow of the repository alike. Fail types set outside the analysed sources are not
  seen. An argument that is no class literal makes every exception a failure, with a
  warning.
- A way that only leads to a failure of the workflow task, with nothing drawn on it, is not
  drawn: the `throw`, the `throws?` branch, the clause that does nothing else. `error()`,
  `TODO()` and `throw RuntimeException()` are such ways.
- A node has to lead somewhere. Where a step has no other way to go than into a failure of
  the workflow task, the way stays as it was read, `fail` node included.
- In an update handler a `fail` node says that the update fails. A query and an update
  validator keep a `fail` for whatever they throw.
- `exception` edges go from every step that can raise to each clause that catches, labelled
  with the caught type, whatever the type. Copies of a `finally` block are shared by paths
  with the same continuation.

## Work that runs side by side

- Work started with `Async.function` and joined (`Promise.allOf`, `get()`) is a `parallel`
  region whose lanes are the connected groups of its steps.
- Lanes joined by `Promise.anyOf` and nothing else are a region with `join: first`.
- Work whose promise nothing waits on is not a joined region: one step is drawn in the flow
  with `waitsFor: nothing`, several as a `parallel` region with `waitsFor: nothing`. A
  promise that goes anywhere the analysis does not follow is taken to be waited for.
- A child workflow whose start alone is waited for has `waitsFor: start`; one whose stub
  options say `ABANDON` has `parentClosePolicy: abandon`.
- What is chained to a promise (`thenApply`, `thenCompose`, `handle`, `exceptionally`)
  follows the promise's step in its lane.
- `Workflow.retry` and `Async.retry` are loops marked `retry`.

## Waits and what ends them

An `await` is any place where the workflow waits for something other than a step:
`Workflow.await`, a `take` from a `WorkflowQueue` or a `put` into a full one, a promise the
workflow completes itself, a `WorkflowLock` or `WorkflowSemaphore`. Its `reads` name the
workflow properties it waits on.

A `wakes` edge leads to an await from every handler that writes a property the await reads.
The workflow method wakes a handler's await when its code changes what that await reads, and
an await of its own only from another lane that is still running.

## Cancellation

A cancellation scope holds the steps laid out for its code, where the scope stands. Where
the scope is cancelled, a `cancelScope` node points at them with `cancels` edges, leaving
out steps the flow has already waited for. A scope that cannot be told gives a warning and a
node without edges. A compensation run in a detached scope is labelled `detached`.

## Options and names

- Activity nodes get `timeout` and `taskQueue` as the SDK merges options: the stub's own,
  over the defaults in force when the stub was made, over the options registered for the
  activity type. What is not certain to have run leaves the field unset, without a warning.
- A builder is read from `build()` back to its start, through chained setters and the
  lambdas of `apply` and `also`. A setter that may not run leaves its field unset.
- The Kotlin extensions of the SDK (`io.temporal:temporal-kotlin`) are read as the SDK calls
  they wrap.
- Workflow types, signals, queries, updates and activity types are named by the SDK's own
  rules, from resolved annotations, never from simple names.

## Unknown nodes

An `unknown` node stands where a step is certain or possible and the analysis cannot say
which: the call lies too deep, recurses, may run one of several implementations, has a name
computed at run time, did not resolve, or leads into code that is not there to read
([the reasons](ir.md#unknown-nodes)). `depth-limit`, `recursion` and `dynamic-dispatch` with
candidates stand only where a step is certain behind the call; a call behind which nothing
can be a step is not drawn at all.

`--max-unresolved-pct` turns the share of unknown nodes into an exit status, for a build
that wants to notice when a workflow becomes unreadable.

## Overview and full detail

Most `unknown` nodes do not say that a step is there. A call of a library function around
a block says that nothing is known of whether, when and how often the block runs; a helper
that calls an interface no class implements says that a step may lie behind it. In a
service that wraps its steps in request contexts and tracing, and maps and stores around
them, such nodes outnumber the steps, and each brings branches that exist only to lead to
it. The diagram then shows where the analysis ran out, not what the workflow does.

So an analysis has two details, and the overview is the default:

- **An overview shows what the workflow does.** It has no node for a call behind which a
  step is only possible. Code handed to a call that is not modelled is read as run once,
  where the call is made: that is what a wrapper does, and the full detail says where it
  was assumed. A call into code that is not there is taken to hold no step.
- **What is certain stays.** A call that is too deep, recurses, runs one of several classes
  that hold a step, has a computed name or did not resolve and may be a step keeps its
  node, and the node names the function called.
- **Nothing else moves.** The flow is cleared and laid out as for the full detail, and what
  is left out is passed over where the full detail draws it. Every node that stays has the
  id and the place it has there; a branch, a loop or a lane is gone only where it holds
  nothing else. A clause, or a copy of a `finally` block, that only a failure of a call
  which is left out reaches stays reachable, from where the flow stood at the call.
- **Nothing is dropped silently.** The document lists every call it left out, the diagram
  counts them, and `--detail full` draws each.
- **A loop that the flow never gets past is not emptied.** Without it, what stands after
  it would look as if it were reached; it stays with its `unknown` nodes.

The overview is a reading, not a proof: a wrapper may run its block twice or not at all,
and a step may lie behind a call that was left out. An overlay says so where an event of a
history fits no node.

## Degraded mode

A degraded model has the commit's main Kotlin and Java sources and a classpath of the
Temporal SDK, its Kotlin extensions and the Kotlin standard library. Calls into any other
library do not resolve. In Gradle mode every unresolved call is an `unknown` node; in a
degraded model most of them are calls into libraries, and drawing each would bury the
workflow. So an unresolved call is judged:

- It keeps a node where it may be a step: made on a stub, naming the Temporal API, made on
  what a missing library gave back for a stub interface, or naming several functions of the
  sources of which one may hold a step.
- Everything else is read as the call of a library function: no node, its arguments still
  evaluated, lambdas handed to it still read. It is listed in the document's `skippedCalls`,
  so nothing is dropped silently.
- A call through a stub of an interface that did not resolve is `unknown`, labelled
  `Interface.function`: a step whose name cannot be read is never drawn as its kind.

A class, function or property that the sources declare more than once under one name is read
from one declaration, and a use that may mean another is `unknown`, in both modes. That is
safe rather than right.

Only Maven Central artifacts go into the degraded classpath.

## Requests of activities

An activity that calls another service says so: the requests that its implementation makes
are read from the code and drawn with the activity. The tool stays generic.

- **What the JDK and MicroProfile give is recognised by itself.** An HTTP request built with
  `java.net.http.HttpRequest` and sent with `HttpClient.send` or `sendAsync` is a request. A
  value read from configuration, in the three forms MicroProfile Config has
  (`@ConfigProperty`, `@ConfigMapping`, `Config.getValue`), is known by its key.
- **A client of a repository's own is recognised only when the user names it**, with
  `--client [Type.]method[:parameter]`. Nothing specific to one organisation is built in:
  how a team calls its services is theirs to say. The form is a dotted name as Kotlin
  writes a member, with the parameter after a colon, so that it needs no quotes in a shell.
- **What is read is the code of the sources.** The class of the sources that implements an
  activity type is read from its function for that type, following calls into functions of
  the sources as for workflows, with each argument bound to its parameter. Where several
  classes implement it, each request names its class. An activity type that no class of the
  sources implements has no requests, and nothing is said of it.
- **A request is counted where it is sent.** A request that is built and never sent is none.
  One that is sent but was built where the code does not show, such as one handed in, is
  listed by what is sent, so that the call is not lost. A builder kept in a variable may be
  told more in statements that are not read, so its request gets no method that its chain
  does not give.
- **What cannot be told is shown as written.** A part of a URL or an operation that is
  computed is shown by the name it is written with, `{id}`; one from configuration by its
  key, `{services.inventory.url}`, with the host the configuration files give beside it and
  never instead of it. A value that cannot be read at all is left out, not guessed.
- **Requests change nothing else.** A document without requests is drawn byte for byte as
  before, and `--no-requests` draws one with requests as if it had none. `--detail` has no
  bearing on them.

## Drawing

- **Two notations.** SVG is the diagram to read: one self-contained file, no script, the
  same bytes for the same document and options. Mermaid is the diagram to keep in a
  repository: text that Git hosts draw in place and that diffs line by line.
- **Regions are boxes in SVG and subgraphs in Mermaid.** `compensation` is a region whose
  children are its compensating activities, in the order they run.
- **Handlers sit in a band above the main flow** in SVG, each a card; a handler that runs
  steps of its own has them drawn below its card.
- **Durations** are shown short, in days when whole: `14D`, `30M`.
- **Large workflows.** A document with more nodes than `--max-nodes` has its regions drawn
  as single nodes. The main flow of an SVG diagram is broken into rows at `--wrap-width`.
- **Rows.** Where the rows break is found with only the steps near each row placed. The
  search that places everything gives the same arrangement on every document the two have
  been compared on, and takes minutes on a `try` around a hundred steps. A test holds the
  two together on a set of documents. A diagram nobody has compared may have a row end
  elsewhere; it is still correct: every node and edge drawn once, nothing on top of
  anything else.
- **The time limit.** Every SVG diagram has one (`--svg-timeout`), and a diagram that
  misses it is drawn more simply rather than not at all
  ([usage.md](usage.md#the-time-limit-and-simpler-drawings)). A drawing that finishes in
  time is byte for byte what it is without a limit.
- **Labels and badges.** An edge label must lie beside its own edge and on nothing: no card,
  no other label, no border or header of a region or of the band, and not in the top right
  corner of a card, where an overlay puts its hit count. The label between two cards drawn
  one below the other has no room of its own; where it would lie on something, that pair is
  given room and the diagram is arranged again, up to four times within the diagram's one
  time limit.
- **Overlays never move anything.** An overlay is the plain diagram redrawn with
  decorations from the positions the file records, so a plain diagram and its overlays line
  up.

## Overlays

- An event is assigned to a node by its kind and by what it runs (an activity type, a
  signal name, a change id). Where the history fits several nodes or several ways through
  the graph, each is counted and marked uncertain.
- An event that fits no node is unmapped: a finding about the diagram, not an error. The
  coverage names the `unknown` nodes it may lie behind.
- Events that say what was done to an execution from outside (a request to cancel it, a
  pause, changed options) are counted apart, not as steps.
- A recorded failure that the document does not draw, because the fail type was set where
  the analysis cannot see it, leaves the execution failed without a node it ends at.
- The nodes of query handlers are never "never hit": queries leave no event.
- With a version map, a history is laid over the IR of the commit its worker version was
  built from, so executions of old code are not held against the diagram of new code.

## Known limitations

- **Kotlin workflows on the Temporal Java SDK's blocking API.** Workflows written in Java
  are listed in the inventory and not drawn. The analysis is written and tested against SDK
  1.41.1.
- **Static analysis.** What is chosen at run time (an implementation, a computed activity
  name, reflection) is an `unknown` node, not a step.
- **Fail types and options set outside the analysed sources** are not seen.
- **Requests made inside libraries are not seen**: a REST client generated from an
  interface, an HTTP library other than `java.net.http`, a client the user does not name.
  Of a client that is named, only the call is seen, not what it sends.
- **A workflow type whose implementing class is not in the sources** has no document; a
  warning names it.
- **Module scoping is by mention.** In Gradle mode a module is analysed if its sources
  contain `io.temporal`, or a kept module depends on it.
- **Edge labels in three places** are set without room of their own and can still lie on
  something in a crowded diagram: paired edges in regions that flow downwards, the connector
  between two rows, and the curve of a `cancels` edge.
- **Histories of code the diagram was not drawn from** map poorly unless a version map says
  which commit ran them.
- **Linux is what CI builds on; Windows is not tested.** The tool has launchers for both.
- **No releases yet.** The command line, the IR and the file layout can still change.
