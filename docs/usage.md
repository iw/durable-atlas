# Using Durable Atlas

`durable-atlas` below is the launcher that `./gradlew installDist` puts in
`cli/build/install/durable-atlas/bin`; see [install.md](install.md). Every command has
`--help`, and this page follows it.

```text
durable-atlas run      draw every workflow of a repository at a commit
durable-atlas model    stage 1 of run: the modules, sources and classpaths of a commit
durable-atlas analyse  stage 2: an inventory and one IR file per workflow type
durable-atlas render   stage 3: diagrams and an index page from IR files
durable-atlas fetch    download event histories of a workflow type from a Temporal server
durable-atlas overlay  lay event histories over the diagrams
```

A command writes what it made on stdout: a table and the path of the page to open, or a JSON
summary with `--json`. What it did, its warnings and its progress go to stderr, each line
starting with the command's name.

## run

```bash
durable-atlas run --repo path/to/repo --commit HEAD --out .durable-atlas
```

`run` does `model`, `analyse` and `render` in turn. It reads the commit, not the working
tree: changes that are not committed are not seen. The repository is never modified.

| Option | Meaning |
| --- | --- |
| `--repo <path>` | The repository, or a directory inside one: the directory that holds the Gradle build. |
| `--commit <sha>` | Whatever git resolves to a commit: a SHA, a tag, a branch, `HEAD`. |
| `--out <dir>` | Where everything goes; see [the output directory](#the-output-directory). |
| `--mode gradle\|degraded` | How to obtain the model; see [Gradle mode and degraded mode](#gradle-mode-and-degraded-mode). By default Gradle is tried first. |
| `--force` | Extract the model again even if one for the commit exists. |
| `--jdk-home <dir>` | The JDK whose classes the sources are resolved against. By default, the JDK that runs the tool. |
| `--max-depth <n>` | How many calls deep to follow the functions a workflow calls, 8 by default. A step that lies deeper shows as an `unknown` node at the call. |
| `--detail overview\|full` | How much of what the analysis could not read to show; see [Overview and full detail](#overview-and-full-detail). `overview` is the default. |
| `--max-unresolved-pct <n>` | Exit with status 2 if more than this percentage of a workflow's nodes are `unknown`. The calls an overview left out are no nodes and do not count. |
| `--json` | Print a machine-readable summary on stdout. |

and the [diagram options](#diagram-options).

### The output directory

Each kind of output has a directory named after the full SHA of the commit, however
`--commit` named it:

```text
<out>/model/<sha>/index.json          the model: which modules, in which mode
<out>/model/<sha>/modules/*.json      one manifest per module: source directories, classpath
<out>/model/<sha>/source/             the modules' sources, as of the commit
<out>/ir/<sha>/inventory.json         every workflow type and activity interface the commit declares
<out>/ir/<sha>/<workflow>.ir.json     the IR of one workflow type
<out>/ir/<sha>/standalone-activities.json   activity types no workflow calls
<out>/diagrams/<sha>/<workflow>.svg   the SVG diagram
<out>/diagrams/<sha>/<workflow>.md    a Markdown page with the Mermaid diagram
<out>/diagrams/<sha>/<workflow>.html  with --html: a page that holds the SVG diagram
<out>/diagrams/<sha>/index.md         links every diagram, and explains the Mermaid notation
```

Runs on other commits can share `<out>`; its `ir` directory is then what
`overlay --version-map` reads. A run on a commit that is already there reuses its model
(unless `--force`) and replaces its IR files and diagrams; files of the earlier run that this
one does not write again are removed.

`<workflow>` is the workflow type, with characters other than letters, digits, `.`, `_` and
`-` replaced by `_`. Where several classes implement one workflow type, or two types differ
only in case, each file is named `<workflow type>--<implementing class>` instead.

The model directory holds a copy of the commit's main sources and names jars by their
absolute paths. Read [SECURITY.md](../SECURITY.md) before you publish an output directory.

### Gradle mode and degraded mode

The analysis needs to know which source directories belong together and what is on their
classpath. There are two ways to learn it.

**Gradle mode** asks the repository's own build. The commit is cloned into a scratch
directory, and its Gradle wrapper is started there with an init script that adds a task,
`dumpAnalysisModel`, to every Kotlin or Java module; the task writes the module's source
directories and compile classpath and compiles nothing. Of the modules, those whose sources
mention `io.temporal` are kept, with every module they depend on. Sources are then resolved
against the real classpath, so calls into the repository's own libraries resolve.

Starting the build runs the commit's wrapper, settings, build scripts and plugins, with your
rights. Use Gradle mode only on code you would build yourself.

**Degraded mode** asks nobody. The model is one module made of every `src/main/kotlin` and
`src/main/java` directory of the commit, and its classpath is the one that travels with the
tool: the Temporal Java SDK, its Kotlin extensions and the Kotlin standard library. Only git
is used, nothing of the repository runs, and no network is needed. What the sources take from
other libraries does not resolve:

- A call that does not resolve and cannot be a step of the workflow (it is not made through
  a stub, does not name the Temporal API and names no function of the sources) is taken for
  a call into a missing library. It is not drawn. It is counted in the summary's `Skipped`
  column, listed on the workflow's Markdown page and in the IR under `skippedCalls`, and
  noted below its SVG diagram. If it is handed code that holds a step, that code is drawn
  as for any call that is not modelled: in place in an overview, behind an `unknown` node
  with `--detail full`.
- A call that does not resolve and may be a step is an `unknown` node with the reason
  `unresolved`.
- A class, function or property that the sources declare more than once under one name, as
  two modules of a repository may, is read from one declaration, with a warning.

Without `--mode`, Gradle mode is tried first. If it fails (no wrapper in the commit, a build
that does not configure, dependencies that cannot be resolved), a degraded model is built
instead, stderr says why, and the exit status is 3. `--mode gradle` fails instead of falling
back; `--mode degraded` never starts the build.

A degraded model names the jars of the distribution it was made with. After the tool has
been moved or built elsewhere, pass `--force` to make the model again.

A model whose sources hold Java files and no Kotlin file gives an inventory and no diagram:
workflows are read from Kotlin. Java sources beside Kotlin ones are read for what they
declare.

### Overview and full detail

Where the analysis cannot read a call, a diagram says so with an `unknown` node. Behind some
of these calls a step of the workflow is certain. Behind most a step is only possible: they
say where the analysis ran out, not what the workflow does, and a workflow that calls much
such code is hard to read for them. `--detail` says which of them are drawn.

**`--detail overview`**, the default, draws the workflow without the calls behind which a
step is only possible:

- A call that is not modelled and is handed code, such as a wrapper of a library around a
  block (reason `unsupported`), has no node. What the block holds is drawn where the call is
  made, as if the call ran it once; there is no way past the block. Where a block never
  completes, what follows it is still reached past it.
- A call that leads to a function without a body that no class of the sources implements
  (reasons `unseen-code`, and `dynamic-dispatch` without candidates) is left out.
- A branch whose ways held nothing else is no branch, and a loop or a parallel lane that
  held nothing else is not drawn. A branch that keeps a step on one way stays, and its other
  ways lead on.
- The `unknown` nodes behind which a step is certain stay: `depth-limit`, `recursion`,
  `computed-name`, `dynamic-dispatch` with candidates, `unresolved`, and a workflow method or
  handler without a Kotlin body. Each says which function was called.
- A loop that never ends by itself and holds nothing but such calls stays as it is, with its
  nodes: nothing after it is reached, and the calls are all that says why.

Every other node is the node of the full diagram, with the same id, so the overlay of a
history counts the same steps in either detail. What was left out is not lost: the summary
has a `Left out` column, stderr says how many calls there were, each IR file lists its own
under `leftOutCalls` with reason, function and place, the workflow's Markdown page lists them
under "Calls left out", and a note below its SVG diagram says how many there are and why.

**`--detail full`** draws every such call as an `unknown` node, each block behind its node
with a way past it, and every branch that leads to one. Use it to see where the analysis
stops, or to find the call a history's unmapped event may lie behind:

```bash
durable-atlas run --repo path/to/repo --commit HEAD --out .durable-atlas --detail full
```

An IR file says `"detail": "overview"` if it is an overview. The diagrams are drawn from the
IR files, so `render` and `overlay` show whichever detail the files they are given have.

### Exit status

| Status | Meaning |
| --- | --- |
| 0 | The command did its work. Warnings, `unknown` nodes and events without a node are findings, not failures. |
| 1 | Bad arguments, a stage that failed (a commit that does not exist, a Gradle run that failed under `--mode gradle`, a file that cannot be read), or a crash. |
| 2 | The work was done, but a limit was exceeded: `--max-unresolved-pct`, `--max-unmapped-pct`, or the time allowed for a diagram, so that a workflow has no SVG diagram. |
| 3 | `run` or `model` completed with a degraded model although no `--mode` was given. It takes precedence over 2. |

## Diagram options

`run`, `render` and `overlay` share these.

| Option | Meaning |
| --- | --- |
| `--format svg\|mermaid\|all` | Which formats to write. `all`, the default, writes both. |
| `--html` | Also write an HTML page per workflow that holds its SVG diagram. In a page, unlike in an image, the links of the diagram work. The page has no script. |
| `--max-nodes <n>` | Draw the regions of a workflow with more nodes than this, 150 by default, as single nodes. |
| `--wrap-width <n>` | Break the main flow of an SVG diagram into rows when it is wider than this, 1700 by default; `0` keeps it in one row. |
| `--legend present\|full\|none` | What the legend below an SVG diagram explains: the constructs it shows (the default), all of them, or nothing. |
| `--source-base-url <url>` | Link each card of an SVG diagram to `<url>/<file>#L<line>`. `{commit}` in the URL stands for the commit, as in `https://git.example.com/orders/blob/{commit}`. |
| `--background paper\|transparent` | Background of the SVG diagrams. |
| `--embed-font` | Put the typeface into each SVG file, so that it looks the same everywhere. Adds over a megabyte per file. |
| `--svg-timeout <seconds>` | The time allowed for arranging one SVG diagram, 30 by default; `0` sets no limit. |

### What the diagrams show

An SVG diagram draws every construct as a card with its own icon and colour, regions (loops,
parallel sections, compensations) as boxes around their steps, the signal, query and update
handlers in a band above the main flow, and a legend. Dotted green edges lead from a handler
to the awaits it can end. Below the drawing it says what is worth knowing about it: calls
that were skipped or left out of an overview, regions drawn as single nodes, a drawing made
more simply.

The Mermaid diagram of the same workflow is a `flowchart TD` in a fenced block, which Git
hosts draw in place. Its page also lists the workflow's properties, links the diagrams of its
child workflows, says where each `unknown` node is, and lists the calls that were skipped or
left out of an overview.

The same commit and options always give the same bytes.

### The time limit and simpler drawings

Arranging the SVG diagram of a very large workflow can take long. Each diagram has
`--svg-timeout` seconds. One that is not arranged in that time is drawn again more simply,
each try with the same time:

1. with each row of the main flow ended where it is full, instead of the breaks that make
   the diagram lowest and most even;
2. with that, and every region drawn as a single node.

A simpler drawing shows nothing wrong: it says less, or is arranged with less care. It says
so below the drawing, the index says so, and the exit status stays as it was. If neither is
done in time, the workflow has no SVG diagram; its other files and the index say so, and the
exit status is 2. A drawing that finishes within the limit is byte for byte what it is
without one.

While it draws, the command reports on stderr: one line per workflow when it is done
(`run: 3/12 OrderWorkflow: 32 nodes, 0.6 s`), "still drawing" while one takes long, and a
line for each drawing given up.

## model, analyse and render

The three stages of `run`, for when you want one alone: to keep the IR of a commit, to draw
it again with other options, or to draw IR files you got from elsewhere.

```bash
durable-atlas model --repo path/to/repo --commit HEAD --out out/model
durable-atlas analyse --model out/model/<sha> --out out/ir
durable-atlas render --ir out/ir --out out/diagrams
```

- `model` takes `--repo`, `--commit`, `--out`, `--mode` and `--force` as `run` does, and
  `--keep-scratch`, which keeps the scratch checkout for you to look at. The model goes to
  `<out>/<sha>`.
- `analyse` takes `--model` (the directory that holds the model's `index.json`), `--out`,
  `--max-depth`, `--detail`, `--max-unresolved-pct` and `--jdk-home` as `run` does, and
  `--workflow <type>`, which writes the IR of that workflow type only. The inventory always
  covers everything.
- `render` takes `--ir`, `--out` and the diagram options.

## fetch

```bash
durable-atlas fetch --workflow-type OrderWorkflow --limit 50 --out histories
```

downloads the event histories of the most recent executions of one workflow type from a
Temporal server, one JSON file per execution, named
`<workflow type>.<workflow id>.<run id>.json`, in the form `temporal workflow show --output
json` prints. It only reads from the server.

| Option | Meaning |
| --- | --- |
| `--workflow-type <type>` | The workflow type to list. |
| `--limit <n>` | The most executions to fetch. The server lists the most recent first. |
| `--out <dir>` | Where the histories go. |
| `--since <time>`, `--until <time>` | Only executions that started at or after, or before, a date (`2026-11-17`, midnight UTC) or a date and time with an offset (`2026-11-17T09:30:00Z`). |
| `--address <host:port>` | The Temporal frontend. `TEMPORAL_ADDRESS`; by default `127.0.0.1:7233`. |
| `--namespace <name>` | `TEMPORAL_NAMESPACE`; by default `default`. |
| `--api-key <key>` | `TEMPORAL_API_KEY`. An API key turns TLS on. |
| `--tls`, `--no-tls` | `TEMPORAL_TLS`. |
| `--tls-cert-path`, `--tls-key-path` | Client certificate and its key, in PEM form. `TEMPORAL_TLS_CLIENT_CERT_PATH`, `TEMPORAL_TLS_CLIENT_KEY_PATH`. |
| `--tls-ca-path`, `--tls-server-name` | What the server's certificate is checked against. `TEMPORAL_TLS_SERVER_CA_CERT_PATH`, `TEMPORAL_TLS_SERVER_NAME`. |
| `--profile <name>` | A profile of Temporal's configuration file (`temporal.toml`, or the file `TEMPORAL_CONFIG_FILE` names). `TEMPORAL_PROFILE`. |
| `--connect-timeout <seconds>` | How long to wait for the server to answer at all. |

Every connection setting is taken from its option, else from its environment variable, else
from the profile, else from the default: the same variables and file as Temporal's own
tools read.

The files hold the histories whole, payloads included. Keep them as you keep production
data; see [SECURITY.md](../SECURITY.md).

Histories from elsewhere work as well: `temporal workflow show --output json`, or the
download of the Temporal UI.

## overlay

```bash
durable-atlas overlay --ir out/ir/<sha> --history histories --out coverage
```

lays histories over the IR of their workflow types: it walks each history and the graph
together, and counts which nodes and edges the execution reached. `--history` is one file or
a directory of them.

```text
<out>/index.md                         every workflow type and every execution, with links
<out>/<workflow>.coverage.svg          the diagram with a hit count on every node; what never ran is grey
<out>/<workflow>.coverage.md           the same as a Mermaid page
<out>/<workflow>.coverage.json         the counts, and the events that have no node
<out>/<workflow>.svg                   the plain diagram
<out>/executions/<history>.svg, .md    the path of one execution
```

Every SVG overlay is the plain diagram redrawn where it stands, not arranged again, so the
overlays of a workflow line up with each other. If `<out>` already holds the workflow's
`<workflow>.svg`, written by this tool for the same document, that file is kept and decides
the arrangement; otherwise the diagram is drawn first.

An event of a history that fits no node is counted as unmapped and listed in the coverage
file, with the nodes it may lie behind: a step behind an `unknown` call, for one. Where the
IR is an overview that left calls out, the overlay says that the step may lie behind one of
them; an analysis with `--detail full` gives each such call its node. That is a
finding, and the exit status stays 0 unless `--max-unmapped-pct <n>` sets a limit. Events
that say what was done to an execution from outside (a request to cancel it, changed
options) are counted apart. A history whose workflow type has no IR is reported and left
out.

### Histories from several versions of the code

When the histories come from workers of several versions, run each version's commit into
the same output directory, and say in a version map which commit each worker version was
built from:

```json
{
  "schemaVersion": 1,
  "versions": [
    { "deployment": "orders", "buildId": "2026.11.3", "commit": "4f2a9c1" },
    { "buildId": "2026.11.4", "commit": "9b7d3e0" }
  ],
  "unversioned": "4f2a9c1"
}
```

```bash
durable-atlas run --repo path/to/repo --commit 4f2a9c1 --out out
durable-atlas run --repo path/to/repo --commit 9b7d3e0 --out out
durable-atlas overlay --version-map versions.json --ir out/ir --history histories --out coverage
```

`overlay` then lays each history over the IR of the commit its worker version was built
from, and writes one directory per commit to `coverage`. An entry without `deployment`
stands for its build id in any deployment; `unversioned` is the commit for histories that
name no worker version. A commit's SHA may be abbreviated to its first four or more
characters, as long as only one directory under `out/ir` begins with them.

## JSON summaries and schemas

With `--json`, a command prints one JSON object on stdout in place of its table: the
directories and files it wrote and an entry per workflow or module. `run` and `overlay`,
which can end with status 2 or 3, also give the `exitCode`. stderr is as without it.

The files the tool writes are described by JSON Schemas (draft 2020-12) in its sources:

| File | Schema |
| --- | --- |
| `<workflow>.ir.json` | [`workflow-ir.schema.json`](../ir/src/main/resources/io/github/iw/durableatlas/ir/workflow-ir.schema.json), explained in [ir.md](ir.md) |
| `inventory.json` | [`workflow-inventory.schema.json`](../analyser/src/main/resources/io/github/iw/durableatlas/analyser/inventory/workflow-inventory.schema.json) |
| `standalone-activities.json` | [`standalone-activities.schema.json`](../analyser/src/main/resources/io/github/iw/durableatlas/analyser/inventory/standalone-activities.schema.json) |
| `model/<sha>/index.json`, `modules/*.json` | [`model-index.schema.json`](../model-extractor/src/main/resources/io/github/iw/durableatlas/modelextractor/model-index.schema.json), [`module-manifest.schema.json`](../model-extractor/src/main/resources/io/github/iw/durableatlas/modelextractor/module-manifest.schema.json) |
| `<workflow>.coverage.json` | [`workflow-coverage.schema.json`](../overlay/src/main/resources/io/github/iw/durableatlas/overlay/workflow-coverage.schema.json) |
| a version map | [`version-map.schema.json`](../overlay/src/main/resources/io/github/iw/durableatlas/overlay/version-map.schema.json) |
