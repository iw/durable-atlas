# Contributing to Durable Atlas

The most useful contributions are workflows that Durable Atlas draws wrongly or not at all,
each as the smallest Kotlin source that shows it; fixes that come with a test; and support for
parts of the Temporal Java SDK that the analysis does not yet read.

## Getting started

You need a JDK, 17 or later, and git. Gradle and Kotlin come with the build:
[docs/install.md](docs/install.md) has the details. Then, from a clone:

```console
./gradlew installDist
cli/build/install/durable-atlas/bin/durable-atlas run --repo demo --commit HEAD --out build/demo
```

draws the [demo](demo/README.md)'s workflows and prints where it wrote them.
[README.md](README.md) introduces Durable Atlas, [docs/architecture.md](docs/architecture.md)
its modules, [docs/ir.md](docs/ir.md) the format the stages share, and
[docs/decisions.md](docs/decisions.md) what is drawn and why.

## Before you open a pull request

Run from the repository root:

```console
./gradlew build installDist
```

and, if you changed `fixtures/sample-monorepo/` or `demo/`, `./gradlew build` in that
directory too. The build compiles with warnings as errors and runs every test; the
integration tests start Gradle builds of their own and take several minutes.

- A change to what is drawn comes with a test: a workflow in the tests' sources or in
  `fixtures/sample-monorepo/`, and the IR or the diagram expected for it.
- Golden files are compared byte for byte. Rewrite them with
  `./gradlew build -Ddurable-atlas.updateGolden=true` only for a change you mean, and say in
  the pull request why each one changed.
- A change to the IR keeps `schemaVersion` 1 and older files valid; [AGENTS.md](AGENTS.md)
  lists what comes with it.
- The tool builds and runs on JDK 17 and on JDK 25. CI checks both.
- Docs describe the current state, in British spelling.

## Pull requests

- Open them against `main`. CI must pass.
- Keep each commit to one coherent change, and say why in its message.
- If a coding agent helped, credit it with a `Co-Authored-By` or `Assisted-by` trailer,
  as [AGENTS.md](AGENTS.md) sets out. Agents working here follow AGENTS.md, and Claude
  Code also [CLAUDE.md](CLAUDE.md).

## Bugs and vulnerabilities

Report a bug as an issue with the command you ran, what it printed on stderr, and the
smallest workflow source that shows the problem. Leave out anything you would not publish:
the tool's output quotes your code, and event histories hold the payloads of your workflows.
Report a vulnerability privately, as [SECURITY.md](SECURITY.md) describes.

## Licence

By contributing, you agree that your contributions are licensed under the
[Apache License, Version 2.0](LICENSE), the licence of this repository.
