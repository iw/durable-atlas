# Architecture

Durable Atlas is a pipeline of four stages, each a Gradle module with a file format between
it and the next. The command line strings them together.

```text
repository at a commit
   │  model-extractor   git, and the repository's Gradle build or none
   ▼
model: modules, source directories, classpaths, a copy of the sources and their configuration
   │  analyser          Kotlin Analysis API → discovery → flow → graph
   ▼
IR: one document per workflow type (docs/ir.md), an inventory
   │  render            layout → Mermaid text, or scene → arrangement → SVG
   ▼
diagrams: SVG, Mermaid pages, HTML pages, an index
   ▲
   │  overlay           history → occurrences → walk of the graph → coverage
event histories (fetched from a Temporal server, or exported)
```

## Modules

| Module | Owns | Depends on |
| --- | --- | --- |
| `ir` | The IR: its types, JSON form, schema, canonical order, validation, node ids. | kotlinx.serialization |
| `model-extractor` | The model of a commit: reading a repository through git, running its Gradle build with the init script, the degraded model, the model's files. | `ir` |
| `analyser` | From a model to IR: the backend that reads Kotlin, discovery of workflow types and activities, the flow of each entry point, its graph, the requests of each activity, the inventory. | `ir`, `model-extractor`, SnakeYAML Engine |
| `render` | From IR to diagrams: the Mermaid renderer, the SVG renderer and its layout, HTML pages, the index, the time limit. | `ir`, ELK, Inter |
| `overlay` | From IR and histories to coverage: reading histories, mapping events to nodes, coverage files, overlay diagrams, the version map, fetching from a server. | `ir`, `render`, the Temporal SDK |
| `cli` | The commands, their options, summaries, progress lines and exit statuses. | all of the above, Clikt |

`build-logic` holds the convention plugins every module applies (`durable-atlas.kotlin-jvm`
and its companions). `demo/` and `fixtures/sample-monorepo/` are Gradle builds of their own:
projects for the tool to analyse, and the inputs of its tests.

## Stage 1: the model

`ModelExtractor` resolves the commit, then builds the model in one of two ways
([usage.md](usage.md#gradle-mode-and-degraded-mode)):

- `SourceRepository` is the only code that touches the analysed repository, and only through
  git commands that read: `rev-parse`, `ls-tree`, `archive`, and a `clone --shared` into a
  scratch directory.
- `GradleModelRunner` starts the commit's own wrapper in that clone with
  `dump-model.init.gradle`, which adds the task `dumpAnalysisModel` to every Kotlin or Java
  module. `ModuleScoping` keeps the modules that mention `io.temporal` and what they depend
  on.
- `ModelBuilder.degraded` makes the one-module model of every main source directory, with
  the classpath `DegradedClasspath` finds next to the tool's own jars.

A model is built in a staging directory and moved into place in one step; its `index.json`
is written last, so a model directory with an index is complete. The sources in it are
exported from the commit by git, so a model does not depend on the working tree or on the
scratch directory. So are the configuration files of each module's main resources
(`application.yaml`, `application.yml`, `application.properties`), which the manifest lists
under `resources`: in Gradle mode those of the resource directories the init script reports,
in degraded mode those of `src/main/resources` beside each source directory.

## Stage 2: the analysis

**Reading Kotlin.** `SourceBackend` is the only way the tool looks at source code. It hands
out plain data: declarations, and the code of functions as a `Code` tree with every name
resolved. Its one implementation, in the source set `analyser/src/backend`, uses the
standalone Kotlin Analysis API. That API brings a core of the IntelliJ platform and its own
versions of common libraries, so the backend is packaged as a jar of its own and loaded by a
class loader of its own (`AnalysisRuntime`); the two sides share only the JDK, the Kotlin
standard library and the types of `analyser.code`. A failure inside the Analysis API leaves
a call unresolved and is reported; it does not end the analysis.

**Discovery.** `TemporalDiscovery` finds workflow interfaces, their entry points and the
classes that implement them, and activity interfaces with the activity types they register,
by resolved annotation types and by the naming rules of the Temporal Java SDK's own metadata
classes. The result is the inventory.

**Flow, then graph.** For each entry point of each implementation:

1. `FlowReader` reads the `Flow` of the function off its code: the steps in the order the
   code reaches them and the constructs that steer the flow between them. It follows calls
   into functions of the sources (`Dispatch`), up to `--max-depth`. `CallRecognizer` knows
   the calls of the Temporal API; `Stubs.kt`, `ActivityOptions.kt` and `BuilderChains.kt`
   trace a stub back to where it was made, and its options to their builder; `KotlinExtensions` rewrites calls of the
   SDK's Kotlin extensions to the SDK calls they wrap before anything reads them.
2. `StepProbe` decides which functions are worth following: those in which a step is
   certain. `FailureTypes` and `FailTypes` decide which exceptions fail the execution.
   `UnresolvedCalls` judges a call that does not resolve in a degraded model.
3. `GraphLayout` lays the flow out as nodes and edges, by the region convention of
   [ir.md](ir.md#regions-and-implied-edges). For an overview it lays out the same flow and
   passes over the calls behind which a step is only possible (`Overview.kt`), so that
   every node it draws is the node of the full detail.
4. `WorkflowGraphs` joins the graphs of a workflow's entry points into one document and adds
   the edges between them: `wakes`, from what writes a property to the awaits that read it
   (`State`), and `cancels`.

**Requests.** `ActivityRequests` (`analyser/requests`) finds, for each activity type, the
function of each class of the sources that implements it, and `RequestReader` reads it the way
`FlowReader` reads a workflow: following calls into functions of the sources up to
`--max-depth`, each argument bound to its parameter. It knows the `java.net.http` calls, and
the clients `--client` names (`ClientSpec`). `RequestValues` follows the URI or the operation
of a request back to what made it, through `ValueTracer`, and knows the three forms of
MicroProfile Config; `Configuration` reads a service's configuration files, with SnakeYAML
Engine for YAML, for the host of a key. The requests are put on the nodes that run the
activity type, and in the inventory.

Documents are written in canonical order (`IrOrdering.kt`), under the names `IrFiles` gives
them.

## Stage 3: drawing

`DiagramDirectory` reads and validates every document before the first file is written,
then draws each workflow in each format (`DiagramFormat`) and writes the index last. The
files of a workflow are written whole or not at all.

Both renderers start from `LayoutBuilder`, which works out a `DiagramLayout`: which nodes
belong to the main flow and to each handler, how regions nest, the order of everything, and
the implied edges. `NodeText` holds the words of the notation.

**Mermaid.** `MermaidRenderer` writes the layout as a `flowchart TD`; `MermaidFormat` wraps
it in a Markdown page.

**SVG.** The diagram is made in steps, each of which leaves the earlier ones alone:

1. `SceneBuilder` decides what every card says and how large it is. Text is measured with
   the bundled typeface (`FontMetricsMeasurer`), so sizes do not depend on the machine.
2. `ElkLayoutEngine` arranges each region, innermost first, and each row of the main flow,
   with the layered algorithm of the Eclipse Layout Kernel.
3. `DiagramComposer` arranges the whole: `RowWrapper` breaks the main flow into rows, the
   handlers go in a band above, and `RowLinks`, `WakeRouter` and `CancelRouter` draw the
   edges the engine does not route. `LabelTidier` then moves edge labels off whatever they
   came to lie on; a label that finds no place makes the composer give its step room and
   arrange again.
4. `SvgWriter` draws the positioned graph. It also records it, as JSON in the file's
   `<metadata>`.

That record is what makes overlays cheap and stable: `SvgFormat.restyle` reads the positions
back and redraws the same diagram with decorations, without arranging anything.

**The time limit.** `DrawingRunner` runs each SVG arrangement against a `Deadline`, which
the long-running steps ask. A drawing that runs out of time is tried again at the next
`Simplification.Level`.

## Overlay

`HistoryJson` reads a history with the Temporal SDK's own types. `Occurrences` reduces it
to what says something about the path of the workflow (`EventRoles` says which event types
do). `ExecutionMapper` walks the history and the document's `FlowGraph` together, keeping a
place for the workflow method, for each running handler and for each lane, and assigns each
occurrence to a node; where the history fits several nodes, each is counted as uncertain.
`CoverageBuilder` adds the traces of the executions up, and `OverlayDirectory` writes the
coverage files and draws the overlays as decorations (`DiagramDecorator`) of the plain
diagram.

`HistoryFetcher` is the only code that opens a network connection. It lists executions and
downloads histories through the SDK's service client.

## Where each decision lives

| Decision | Place |
| --- | --- |
| What a workflow type, a signal or an activity is called | `analyser/discovery` |
| Which SDK call is which kind of step | `analyser/graph/CallRecognizer`, `TemporalApi` |
| Whether a function is drawn from the inside | `analyser/graph/StepProbe` |
| Whether an exception fails the execution | `analyser/graph/FailureTypes`, `FailTypes` |
| What an unresolved call is taken for in a degraded model | `analyser/graph/UnresolvedCalls` |
| What a request is, and how its target, operation and host are read | `analyser/requests/RequestReader`, `RequestValues`, `Configuration` |
| What an overview leaves out, and what it has to keep | `analyser/graph/Overview`, `GraphLayout`; `OverviewContract` in the test fixtures of `ir` |
| How regions and their edges are written | `analyser/graph/GraphLayout`; read by `render/layout/LayoutBuilder` and `overlay/mapping/FlowGraph` |
| The order of nodes, edges and keys in a document | `ir/IrOrdering`, `ir/IrJson` |
| The words on nodes | `render/layout/NodeText`, `render/svg/scene/CardTexts`; for requests, `render/layout/RequestTexts` |
| Colours, icons and legend names of the SVG notation | `render/src/main/resources/…/svg/construct-styles.json` |
| Every measurement of the SVG notation | `render/svg/Geometry` |
| Where rows break | `render/svg/compose/RowWrapper` |
| Where a label may lie | `render/svg/compose/LabelTidier` |
| Which event belongs to which node | `overlay/mapping/ExecutionMapper` |
| Which event types say anything about the path | `overlay/history/EventRoles` |
| Exit statuses | `cli/Completion`, `cli/ExitCode` |

## Properties the design keeps

- **Deterministic output.** Documents are written in canonical order; node ids are derived
  from what a node is; text is measured with the bundled typeface; nothing reads the clock,
  the locale or the order of a directory into a file.
- **The repository is read, never run and never written.** Only the Gradle build of Gradle
  mode runs, in a scratch clone.
- **A diagram is a function of its document.** Renderers and the overlay take nothing from
  the sources or the model.
- **Layout and decoration are apart.** A layout does not depend on decorations, so a plain
  diagram and every overlay of it line up.
