# Installing and building

Durable Atlas has no release yet: you build it from a clone. The build needs a JDK and git;
everything else it fetches itself.

## Requirements

- **A JDK, 17 or later.** The build makes Java 17 class files with whichever JDK runs Gradle,
  and the tool runs on any JDK from 17 on. It is built and tested on JDK 17 and JDK 25.
- **git**, on the `PATH`. The tool reads the repositories it analyses through git, and the
  tests make repositories of their own.
- **Gradle and Kotlin do not have to be installed.** `./gradlew` downloads Gradle 9.9.1 on
  first use, and the build fetches the Kotlin 2.4.21 compiler itself.
- **Network access when building.** Dependencies come from Maven Central, the Gradle Plugin
  Portal and two JetBrains repositories that host the Kotlin Analysis API
  (`redirector.kotlinlang.org` and `www.jetbrains.com/intellij-repository`). Without it, see
  [Building without the public repositories](#building-without-the-public-repositories).
- **On Linux, `fontconfig` and one installed font.** The SVG renderer measures text with the
  typeface it bundles, through Java's font support, which on Linux relies on fontconfig and
  on at least one font being installed. Desktop systems have both; a minimal container image
  may need them added (on Debian or Ubuntu, `apt install fontconfig fonts-dejavu-core`).

## Building

```bash
./gradlew installDist
```

installs the tool to `cli/build/install/durable-atlas`, with its launcher at
`bin/durable-atlas` (`bin\durable-atlas.bat` on Windows). The directory is self-contained:
copy it wherever you like, or put its `bin` on your `PATH`.

```bash
./gradlew build
```

also compiles the tests and runs them. Some start Gradle builds of their own, of the sample
repository in `fixtures/sample-monorepo` and of the [demo](../demo/README.md), so the first
run takes several minutes.

The launcher passes the JVM options named in `JAVA_OPTS` and `DURABLE_ATLAS_OPTS`. The JVM
sizes its own heap; for a very large repository, set it there, as in
`DURABLE_ATLAS_OPTS=-Xmx4g`.

## Installing a JDK

The commands below install Eclipse Temurin 25 from
[Adoptium](https://adoptium.net/temurin/releases/?version=25). Any other JDK distribution, of
version 17 or later, works as well.

macOS, with [Homebrew](https://brew.sh):

```bash
brew install --cask temurin@25
```

Debian or Ubuntu:

```bash
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null
echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-25-jdk
```

Fedora, RHEL and other RPM-based systems: add the Adoptium repository as described in
[Adoptium's Linux instructions](https://adoptium.net/installation/linux/), then
`sudo dnf install temurin-25-jdk`.

Windows:

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

With [SDKMAN!](https://sdkman.io) on macOS or Linux, pick the newest `25.x-tem` entry that
`sdk list java` shows, for example:

```bash
sdk install java 25.0.5-tem
```

Without a package manager: download the archive for your system from
[Adoptium](https://adoptium.net/temurin/releases/?version=25), unpack it, and point `JAVA_HOME`
at it.

Then make sure Gradle finds it, and check the version:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS; elsewhere, the JDK's directory
java -version
```

## Installing Gradle (optional)

Not needed for building: use `./gradlew`. If you want Gradle on your path:

- SDKMAN!: `sdk install gradle 9.9.1`
- Homebrew: `brew install gradle`
- By hand: download
  [gradle-9.9.1-bin.zip](https://services.gradle.org/distributions/gradle-9.9.1-bin.zip),
  unpack it, and add its `bin` directory to `PATH`. Other versions are listed on
  [gradle.org](https://gradle.org/releases/).

## Installing Kotlin (optional)

Not needed for building: Gradle downloads the compiler. If you want the command-line compiler
`kotlinc`:

- SDKMAN!: `sdk install kotlin 2.4.21`
- Homebrew: `brew install kotlin`
- By hand: download `kotlin-compiler-2.4.21.zip` from the
  [Kotlin 2.4.21 release](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21), unpack it,
  and add `kotlinc/bin` to `PATH`.

## Building without the public repositories

On a machine that cannot reach Maven Central and the Gradle Plugin Portal, and takes its
libraries from a repository manager instead:

- `./gradlew` cannot download Gradle. Run an installed Gradle instead, 9.1 or later (`gradle`
  in place of `./gradlew` below).
- Name the repositories to use in place of Maven Central and the Gradle Plugin Portal in the
  Gradle property `durable-atlas.repositories`, separated by commas. Each library and plugin
  is looked for in them in the order given.
- The Kotlin Analysis API and the IntelliJ modules it needs still come only from the two
  JetBrains repositories above. If the machine reaches them through a proxy, give Gradle
  that proxy (below). If a repository manager mirrors them, name the mirrors in
  `durable-atlas.jetbrainsRepositories` instead; those modules are then taken from there and
  nowhere else.
- Plain `http` addresses are accepted.

Put the settings in `~/.gradle/gradle.properties`, for example:

```properties
durable-atlas.repositories=https://repo.example.com/maven-central,https://repo.example.com/releases
# JetBrains through a proxy, the other repositories directly:
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=8080
# or, where the repository manager mirrors JetBrains:
# durable-atlas.jetbrainsRepositories=https://repo.example.com/jetbrains-intellij-dependencies,https://repo.example.com/jetbrains-intellij-releases
```

The `https` proxy settings apply to every `https` address. When the repositories of
`durable-atlas.repositories` are `https` too and must not go through the proxy, add
`systemProp.http.nonProxyHosts=repo.example.com` (several hosts separated by `|`; Java reads
this one setting for both `http` and `https`). Then:

```bash
gradle installDist
```

`installDist` is enough to get the tool. `build` also runs tests that start builds of the
sample repository and the demo through their own `./gradlew`, which resolve their
dependencies from Maven Central and the Gradle Plugin Portal.

The tool itself needs no network to draw a repository in degraded mode: the classpath it
resolves sources against travels with the distribution. In Gradle mode the analysed
repository's build resolves its own dependencies, from wherever it is configured to; see
[usage.md](usage.md#gradle-mode-and-degraded-mode).
