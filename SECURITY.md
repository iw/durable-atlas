# Security policy

## Reporting a vulnerability

Please report a vulnerability privately, through this repository's
[private vulnerability reporting](https://github.com/iw/durable-atlas/security/advisories/new),
not in a public issue. Say what you found, how to reproduce it, and the commit you tested. The
report stays private while a fix is prepared.

## Supported versions

Durable Atlas has no releases yet. Fixes land on `main`.

## Deployment model

Durable Atlas is a command-line tool that runs as you, on your machine, and stops when its
command is done. It has no server, no account and no telemetry.

- **Gradle mode runs the analysed repository's build.** To learn a commit's modules and
  classpaths, `run` and `model` clone the commit into a scratch directory and start *its own*
  Gradle wrapper there with an init script that adds one task. Starting a build runs whatever
  the commit's wrapper, settings, build scripts and plugins say, with your rights and your
  environment variables, and lets it download what it asks for. This is the default: without
  `--mode`, Gradle is tried first. **Point the tool only at code you would build yourself.**
- **Degraded mode runs none of it.** With `--mode degraded` the tool reads the commit through
  `git` (`rev-parse`, `ls-tree`, `archive`) and resolves the sources against the jars it ships.
  No build script, wrapper or plugin of the repository is started. The repository's own Git
  configuration applies to those `git` commands, as it does to any you run in it.
- **Analysis reads, and never runs, workflow code.** Sources and the jars on the classpath are
  parsed and resolved in the tool's own process; no class of the analysed code is loaded or
  executed.
- **The repository is never written to.** The clone borrows the repository's objects; the
  working tree, the index and `.git` stay as they are. The scratch directory lies in the
  system's temporary directory and is removed when the command ends, unless `--keep-scratch`
  is given.
- **What is written.** Only below the `--out` directory of each command, apart from the scratch
  directory and what the analysed build itself writes (Gradle's caches in your home directory,
  and a Gradle daemon that may stay running). An output directory holds more than diagrams:
  `model/<commit>/source` is a copy of the commit's main sources, the model's manifests name
  jars by their absolute paths on your machine, and IR files and diagrams quote names,
  conditions and file paths from the code. Read an output directory before you publish it.
- **`fetch` talks to the Temporal server you name**, and to nothing else. It checks that the
  server answers, lists executions of one workflow type with a visibility query, and downloads
  their event histories; it changes nothing on the server. It sends your API key or client
  certificate if you configure one, from the options, the `TEMPORAL_*` environment variables
  or a profile of `temporal.toml`. **The histories it writes are complete**: they hold the
  payloads of your workflows (inputs, results, signal and activity arguments, failure
  messages) as the server holds them. Keep them as you keep production data. `overlay` copies
  no payload into what it writes: coverage files and pages name history files, workflow and run
  ids and statuses, and events by their type and by the names the server records for them
  (activity types, signal names and the like).
- **What leaves the machine.** Apart from `fetch`, the tool opens no network connection of its
  own. Building the tool downloads Gradle and its dependencies from the repositories its
  settings name. In Gradle mode, the analysed build downloads its own Gradle distribution and
  dependencies from wherever it is configured to.
- **The diagrams.** SVG files and HTML pages carry no script and load nothing. Their only
  links lead to the index, to the diagrams of child workflows and, with `--source-base-url`, to
  source lines under the address you gave.
