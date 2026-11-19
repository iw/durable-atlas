# Third-party notices

Durable Atlas's own code is licensed under the [Apache License, Version 2.0](LICENSE). This
repository holds none of the libraries below: Gradle fetches them when the tool is built. The
distribution that `./gradlew installDist` puts in `cli/build/install/durable-atlas` bundles them
as jars, unchanged, in three directories:

- `lib/`: the tool's own classpath;
- `lib/analysis/`: the Kotlin Analysis API and what it runs on, loaded by a class loader of
  its own;
- `lib/degraded/`: the classpath that stands in for a repository's own in degraded mode. The
  tool reads these jars and never runs them.

Whoever passes a built distribution on passes these libraries on with it, under their licences.
Libraries that only the tests use are not listed.

## Eclipse Layout Kernel

The SVG diagrams are arranged by the layered algorithm of the
[Eclipse Layout Kernel](https://github.com/eclipse-elk/elk) 0.12.1 (`org.eclipse.elk.alg.layered`,
`.alg.common`, `.core` and `.graph`), licensed under the
[Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/). It brings:

- the Eclipse Modeling Framework 2.12.0 (`org.eclipse.emf.common`, `.ecore`, `.ecore.xmi`),
  [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html);
- the Xbase run-time library 2.42.0 (`org.eclipse.xtext.xbase.lib`), Eclipse Public License 2.0;
- [Guava](https://github.com/google/guava) 33.8.0, with its `failureaccess`, `listenablefuture`
  and annotation companions (J2ObjC annotations, Error Prone annotations, JSpecify, JSR 305),
  all Apache-2.0.

The libraries are used as published. The sources of the Modeling Framework and of Xbase are
at <https://github.com/eclipse-emf/org.eclipse.emf> and <https://github.com/eclipse-xtext/xtext>.

## Inter

The text of the SVG diagrams is set in [Inter](https://github.com/rsms/inter) 4.1, copyright
(c) 2016 The Inter Project Authors, licensed under the
[SIL Open Font License 1.1](https://openfontlicense.org). The
font files come unchanged from `com.formdev:flatlaf-fonts-inter` 4.1, a packaging of Inter by
FormDev Software (the packaging is Apache-2.0); the licence travels inside that jar as
`com/formdev/flatlaf/fonts/inter/LICENSE.txt`.

How the output uses it:

- Every SVG diagram is measured with Inter's metrics and asks for the family by name
  (`font-family:Inter,system-ui,Arial,sans-serif`). By default the file holds no font: a viewer
  that has Inter draws with it, and any other viewer draws with its own typeface.
- With `--embed-font`, each SVG file carries the two weights the diagrams use, `Inter-SemiBold.otf`
  and `Inter-Bold.otf`, whole and unchanged, as `@font-face` data. Such a file redistributes
  Inter under the SIL Open Font License; the licence text is not copied into the file.

## Kotlin Analysis API and IntelliJ platform

`lib/analysis/` holds what reads Kotlin and Java sources:

- the standalone Kotlin Analysis API and the compiler modules it wraps, 2.4.21
  (`org.jetbrains.kotlin:*-for-ide`), Apache-2.0, from the
  [Kotlin repository](https://github.com/JetBrains/kotlin);
- modules of the IntelliJ platform, 251.27812.49 (`com.jetbrains.intellij.platform:*` and
  `com.jetbrains.intellij.java:*`), built from the
  [IntelliJ IDEA Community Edition](https://github.com/JetBrains/intellij-community) sources,
  which are licensed under Apache-2.0 (their published POMs name no licence);
- JetBrains' builds of other projects: `kotlinx-coroutines` 1.8.0-intellij-13 (Apache-2.0),
  `asm-all` 9.6.1 (a build of ASM, BSD-3-Clause), `intellij-deps-fastutil` 8.5.14-jb1 (a
  build of [fastutil](https://github.com/vigna/fastutil), Apache-2.0), JetBrains annotations
  24.0.0 (Apache-2.0);
- `kotlinx-serialization` 1.7.3, `kotlinx-collections-immutable` 0.5.1, Caffeine 2.9.3, Guava
  33.4.0 with its companions, Apache Commons Codec 1.17.1, Compress 1.26.1 and IO 2.16.1,
  Aalto XML 1.3.3, lz4-java 1.8.0, StreamEx 0.8.2, `log4j-over-slf4j` 1.7.36, OpenTelemetry
  1.47.0 (API, context, SDK) and its semantic conventions 1.30.0-rc.1: all Apache-2.0;
- [JNA](https://github.com/java-native-access/jna) 5.14.0 (`jna`, `jna-platform`), under
  Apache-2.0 or LGPL-2.1-or-later at the user's choice; it is used under Apache-2.0;
- Checker Framework qualifiers 3.19.0 (MIT), StAX2 API 4.2.2 (BSD-2-Clause), Jaxen 1.2.0
  (BSD-3-Clause), `dk.brics.automaton` 1.12-4 (BSD), Jakarta ORO 2.0.8 (Apache Software
  License 1.1).

## Temporal Java SDK

`lib/` holds the [Temporal Java SDK](https://github.com/temporalio/sdk-java) 1.41.1
(`temporal-sdk`, `temporal-serviceclient`, `temporal-envconfig`), Apache-2.0: `fetch` talks to a
server through it, and `overlay` reads event histories with it. `lib/degraded/` holds the SDK
again with its Kotlin extensions (`temporal-kotlin`), for the tool to resolve workflow code
against.

Durable Atlas is not affiliated with Temporal Technologies, Inc. "Temporal" is their trademark.

The SDK brings, in one directory or both:

- [gRPC Java](https://github.com/grpc/grpc-java) 1.76.0 (with Netty, shaded), Perfmark 0.27.0,
  Google's common protos 2.59.2, Gson, Jackson 2.15.4 (core, annotations, databind, TOML, JDK 8
  and JSR 310 modules), Micrometer 1.9.9, Guava and its companions, the Android annotations
  4.1.1.4: all Apache-2.0;
- [Protocol Buffers](https://github.com/protocolbuffers/protobuf) 3.25.8 (`protobuf-java`,
  `protobuf-java-util`), BSD-3-Clause;
- the [Nexus SDK](https://github.com/nexus-rpc/sdk-java) 0.6.0-alpha, Tally 0.13.0, SLF4J (API
  2.0.20 and 1.7.36, and the `slf4j-nop` binding 2.0.20), Animal Sniffer annotations 1.24: all
  MIT;
- HdrHistogram 2.1.12 (CC0 or BSD-2-Clause) and LatencyUtils 2.0.3 (CC0).

## Other libraries of the tool

- [Kotlin standard library](https://github.com/JetBrains/kotlin) 2.4.21 and
  [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) 1.13.0, Apache-2.0;
  JetBrains annotations 13.0, Apache-2.0.
- [Clikt](https://github.com/ajalt/clikt) 5.1.0 and
  [Mordant](https://github.com/ajalt/mordant) 3.0.2, Apache-2.0, with
  [Colormath](https://github.com/ajalt/colormath) 3.6.0, MIT, and JNA 5.14.0 (as above).
- [Jayway JsonPath](https://github.com/json-path/JsonPath) 2.9.0 with `json-smart` and
  `accessors-smart` 2.5.0, Apache-2.0, and ASM 9.3, BSD-3-Clause. The Temporal SDK needs
  JsonPath to read histories from JSON.
