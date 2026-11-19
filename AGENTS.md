# Durable Atlas contributor guidance

These instructions are for coding agents working in this repository, and apply to all of
it. Read [README.md](README.md) first, then [docs/architecture.md](docs/architecture.md)
and [docs/ir.md](docs/ir.md) before changing a module's boundaries or the IR.
[docs/decisions.md](docs/decisions.md) says what is drawn and why.

## What Durable Atlas is

Durable Atlas reads the Kotlin source of Temporal workflows, without running it, and draws
each workflow type as a diagram. It can lay recorded event histories over the diagrams to
show which paths executions took. Keep it that way:

- The analysis is static. Nothing of the analysed code is loaded or executed; the only
  thing of a repository that ever runs is its Gradle build, in Gradle mode, to learn its
  modules and classpaths.
- A diagram never claims more than the analysis knows. What cannot be told is an `unknown`
  node with a reason, a warning on stderr, or an optional field left out. It is never a
  guess drawn as a fact.
- The IR is the contract between the stages. The analyser writes it, both renderers and the
  overlay read it, and none of them reaches around it into another stage.
- Output is deterministic: the same commit and options give the same bytes.

## Sources of truth

- The Temporal Java SDK at the version pinned in `gradle/libs.versions.toml`. A rule about
  what the SDK does (how a workflow type is named, what an exception does to an execution,
  which options win) is settled by reading the SDK's source, and by a test that runs the
  SDK where one can.
- `ir/src/main/resources/io/github/iw/durableatlas/ir/workflow-ir.schema.json` for the IR.
  [docs/ir.md](docs/ir.md) explains it; the schema decides.
- `demo/expected/OrderWorkflow.ir.json` is the worked example: written by hand, and the
  analyser's tests hold the analysis of the demo to it.
- The `--help` of each command for its options. [docs/usage.md](docs/usage.md) follows it.

## Architecture boundaries

The Modules table in the README says what each module owns.

- `ir` depends on nothing of the tool. `render` and `overlay` depend on `ir`, never on
  `analyser` or `model-extractor`.
- Only `analyser/src/backend` touches the Kotlin Analysis API, the Kotlin PSI or the
  IntelliJ platform. It is loaded by a class loader of its own and hands out only the types
  of `io.github.iw.durableatlas.analyser.code`. Nothing of the Analysis API may reach the
  tool's own classpath.
- Only `overlay` and `cli` use the Temporal SDK at run time, to read histories and to fetch
  them. The analyser recognises the SDK by name and never links against it.
- `demo/` and `fixtures/sample-monorepo/` are separate Gradle builds with their own
  wrappers. They are inputs of the tests: a change to either changes what tests expect.
- Repositories are declared only in `settings.gradle.kts`, versions only in
  `gradle/libs.versions.toml` of each build, shared build logic only in the convention
  plugins of `build-logic` (`durable-atlas.*`). A new dependency is a design decision; pin
  it to a released version, and add what the distribution bundles to
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## IR changes

- `schemaVersion` stays 1. A new field, node kind or edge kind is allowed when something
  cannot be said truthfully with what exists; prefer an existing construct where it says
  it correctly.
- A new field is optional, so that IR files written before it stay valid. It goes into
  the schema with a description, into the KDoc of `ir`, and into [docs/ir.md](docs/ir.md).
- Every reader handles it: the Mermaid renderer, the SVG renderer, the overlay and the
  command line's summaries.
- Regions follow one convention, which [docs/ir.md](docs/ir.md#regions-and-implied-edges)
  sets out. Three places hold it and are tested to agree: the analyser (`GraphLayout`), the
  Mermaid layout (`LayoutBuilder.impliedLinks`) and the overlay (`FlowGraph.frame`). Change
  all three or none.
- `wakes` and `cancels` edges are no part of the flow (`EdgeKind.isFlow`). They never count
  for implied exits, for lanes or for which entry point owns a node.

## Drawing

- A drawing that finishes within its time limit is byte for byte what it is without a
  limit. Whatever makes drawing quicker leaves every drawing as it is, or is one of the
  simpler drawings that say so themselves.
- Long-running steps of the SVG arrangement ask the `Deadline` they are handed. A new one
  must too; the layout engine stops only between two of its phases.
- `RowSearchTest` holds the row search that every diagram uses (`RowSearch.NEAR`) to the
  arrangement of the search that places everything (`RowSearch.WHOLE`). If a change makes
  them differ, report it with the document and both drawings. Don't change the test to
  pass.
- A label must not lie on a card, another label, the border or header of a region or of
  the handlers' band, or the corner where a badge goes. A document in which one did goes
  into `render/src/test/resources/ir-labels/`, where every per-document SVG test draws it.

## Writing

- Docs, comments and messages describe the current state: no history of earlier versions,
  no dated anecdotes, no "since" or "used to". Rewrite the affected text rather than
  appending to it.
- British English spelling: behaviour, licence, analyse, colour.
- Describe a situation concretely, in plain words. Neutral wording everywhere: no company,
  customer or product names other than the libraries the tool uses.
- Comments explain why a decision is necessary, not what the next line does.
- After a change, recheck what the docs quote: options and their defaults, counts, file
  names, the output of the commands the README shows.

## Kotlin and build standards

- Kotlin 2.4.21, Gradle 9.9.1 through the wrapper (`./gradlew`), Temporal Java SDK 1.41.1,
  Clikt 5.1.0, kotlinx.serialization 1.13.0. The catalogue says why Clikt and the Xbase
  library are held back: later versions need Java 21. Don't change a pinned version as a
  side effect of other work.
- Class files are made for Java 17, against the Java 17 API (`jvmTarget` 17,
  `-Xjdk-release=17`, Java `release` 17), by whichever JDK runs Gradle. There is no
  `jvmToolchain`. The tool must build, pass its tests and run on JDK 17 and on JDK 25, and
  the launcher's JVM options must be accepted by both.
- Warnings are errors (`allWarningsAsErrors`).
- Idiomatic Kotlin in small, focused files, in packages under
  `io.github.iw.durableatlas.<module>`.
- Output is deterministic: stable sort orders, fixed key order, `\n` line endings, a
  trailing newline. Nothing that is written depends on the locale, the time zone, the
  clock, the file system's order or the machine's fonts.

## Tests

- JUnit 5 through `kotlin("test")` and `junit-jupiter`. `testFixtures(project(":ir"))` gives
  `JsonSchemaChecker` and `GoldenFiles.assertMatches`.
- Golden files live under `src/test/resources/golden/` of each module and are compared byte
  for byte. To rewrite them after a change you mean, run the tests with
  `-Ddurable-atlas.updateGolden=true`, then read the diff: a golden file that changed is a
  change of behaviour to account for.
- The demo's and the fixture's event histories are captured by their own tests. To replace
  them, run `./gradlew :orders:test -Ddurable-atlas.updateHistories=true` in `demo/`, or
  `./gradlew :workflows:test -Ddurable-atlas.updateHistories=true` in
  `fixtures/sample-monorepo/`.
- Tests of speed count work (layouts, nodes handed to the layout engine, arrangements, bytes
  asked for), never time. Don't sleep in tests, and don't assert on durations.
- No network in tests, apart from Gradle resolving dependencies. The tests that need a
  Temporal server carry the tag `server` and run only through `./gradlew :cli:serverTest`.
- Nothing in the build or the tests may depend on the operating system: no absolute paths,
  no file names that differ only in case, no fonts other than the bundled one.

## Verification

Run from the repository root before asking for a commit, on JDK 17 or later:

```console
./gradlew build installDist
cli/build/install/durable-atlas/bin/durable-atlas --help
```

and, if you touched `fixtures/sample-monorepo/` or `demo/`, in that directory:

```console
./gradlew build
```

CI runs the build, the fixture's build and the installed tool on JDK 17 and JDK 25, on
every push to `main` and every pull request. The build runs some 2,900 tests; the
integration tests start Gradle builds of the fixture and take several minutes. A quiet
build is not a stalled one.

A change to the Kotlin target, the launcher or a dependency is checked on both JDKs.

## Data and safety

- Event histories hold the payloads of real workflows. Only the histories that the demo's
  and the fixture's own tests capture belong in this repository. Never commit a history
  fetched from a server, and never paste one into an issue, a test or a doc.
- Never put credentials, real cluster names, hosts, namespaces or personal paths in
  fixtures, golden files, screenshots, docs or code. An example host is `example.com`.
- Gradle mode runs the analysed repository's build. Run the tool only on code you would
  build yourself, and use `--mode degraded` for anything else.
- The analysed repository is never written to. Keep it so: read it through `git`, and work
  in the scratch directory.
- Ask before downloading anything (a clone, a JDK, a release binary), saying what, from
  where and how large. To read another repository at a revision, archive it into a scratch
  directory; never change someone's existing checkout.

## Git

- Treat uncommitted changes you didn't make as someone's work. Don't discard, overwrite
  or fold them in without approval.
- Commit and push only when the maintainer asks, and keep each commit to one coherent
  change.
- Never rewrite published history without the maintainer's approval of the exact
  operation.

## Commit attribution (required)

The maintainer is the Git author. Agents are credited with trailers at the end of the
message, after a blank line:

- `Co-Authored-By: <Agent> <email>` for every agent that authored part of the change:
  code, tests, golden files or documentation.
- `Assisted-by: <Agent> <email>` for every agent that assisted without authoring: review,
  verification or research.

Claude's identity is `Claude <noreply@anthropic.com>`, and its trailers here name the
model, as in `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Pull requests
name the agents that took part.

## Definition of done

A change is done when the build and its tests pass on the JDKs it can affect, the golden
files that changed are accounted for, the schema, the KDoc and the docs describe the
current state, the output is still deterministic, and agent attribution is recorded.
