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
| `--client <spec>` | A client of the repository's own whose calls are requests of the activities that make them, written `[Type.]method[:parameter]`; see [Requests of activities](#requests-of-activities). Repeat it for several clients. |
| `--max-unresolved-pct <n>` | Exit with status 2 if more than this percentage of a workflow's nodes are `unknown`. The calls an overview left out are no nodes and do not count. |
| `--json` | Print a machine-readable summary on stdout. |

and the [diagram options](#diagram-options).

### The output directory

Each kind of output has a directory named after the full SHA of the commit, however
`--commit` named it:

```text
<out>/model/<sha>/index.json          the model: which modules, in which mode
<out>/model/<sha>/modules/*.json      one manifest per module: source directories, configuration files, classpath
<out>/model/<sha>/source/             the modules' sources and configuration files, as of the commit
<out>/ir/<sha>/inventory.json         every workflow type and activity interface the commit declares
<out>/ir/<sha>/<workflow>.ir.json     the IR of one workflow type
<out>/ir/<sha>/standalone-activities.json   activity types no workflow calls
<out>/diagrams/<sha>/<workflow>.svg   the SVG diagram
<out>/diagrams/<sha>/<workflow>.md    a Markdown page with the Mermaid diagram
<out>/diagrams/<sha>/<workflow>.html  with --html: a page that holds the SVG diagram
<out>/diagrams/<sha>/index.md         links every diagram, and explains the Mermaid notation
<out>/diagrams/<sha>/endpoints/index.html            the endpoints page, with the SVG diagrams
<out>/diagrams/<sha>/endpoints/index.md              the endpoints page in Markdown, with the Mermaid diagrams
<out>/diagrams/<sha>/endpoints/<service>.openapi.json   what the activities call of one service, as OpenAPI 3.1
```

The `endpoints` directory is written only where an activity makes a request (see
[The endpoints page](#the-endpoints-page)).

Runs on other commits can share `<out>`; its `ir` directory is then what
`overlay --version-map` reads. A run on a commit that is already there reuses its model
(unless `--force`) and replaces its IR files and diagrams; files of the earlier run that this
one does not write again are removed.

`<workflow>` is the workflow type, with characters other than letters, digits, `.`, `_` and
`-` replaced by `_`. Where several classes implement one workflow type, or two types differ
only in case, each file is named `<workflow type>--<implementing class>` instead.

The model directory holds a copy of the commit's main sources and of the configuration files
beside them, and names jars by their absolute paths. Read [SECURITY.md](../SECURITY.md) before you publish an output directory.

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

### Requests of activities

For each activity type, the analysis reads which requests to other services its
implementation makes: the activity's function in each class of the sources that implements
the activity interface, and the functions it calls, as deep as `--max-depth` allows, each
argument bound to its parameter. Each request is listed once, in code order, on every node
that runs the activity type (`requests`, see [the IR format](ir.md#requests)) and in
`inventory.json`, and drawn in the diagrams. stderr says how many there are.

Two kinds of request are read:

- **HTTP requests sent with `java.net.http`**, through `HttpClient.send` and `sendAsync`,
  are read without being asked for. The request is read from the chain that builds it, from
  `HttpRequest.newBuilder(…)` to `build()`: the method from `GET()`, `POST(…)`, `PUT(…)`,
  `DELETE()`, `HEAD()` or `method("PATCH", …)`, `GET` where none is called; the URI from
  `newBuilder(uri)` or `uri(…)`, made with `URI.create`, `URI(…)`, a string template, `+`,
  `resolve` or `String.format`. The response type is the `T` of `HttpResponse<T>`; a body
  that is thrown away (`Void`) gives none.
- **Calls of a client of the repository's own** are read only for a client that `--client`
  names. Many repositories wrap their requests in a client class of their own, and nothing in
  such a call says that it is one.

A request is written with its constant parts as the code has them, a part that comes from a
configuration property as `{key}`, and any other part the code computes as `{name}`, by the
name the code gives it: `PUT {billing.url}/v1/charges/{orderId}`. Each request is also named
after its service: an HTTP request after the first label of its host (`billing` for
`http://billing.example.com`), with more labels where two hosts would share a name, or after
its key where it has no host; a client after its class.

#### Naming a client

```bash
durable-atlas run --repo path/to/repo --commit HEAD --out .durable-atlas \
  --client ServiceClient.invoke:functionName
```

`--client` takes `[Type.]method[:parameter]`:

- `ServiceClient` is the class of the client, by its simple or its fully qualified name; a
  call on an object of a subclass counts too. Without it, a call of the function on any
  object counts.
- `invoke` is the function called. `client<Long>(…)` counts as a call of `invoke`.
- `functionName` names the parameter whose argument says what is asked for: the argument
  given for it by name, or in its place where the call resolves, or, where the call is handed
  an object, the argument of that name to its constructor or the value given to a builder
  function of that name (`functionName(…)`, `setFunctionName(…)`, `withFunctionName(…)`). A
  number names the argument by its place, from 1: `ServiceClient.invoke:2`. Without `:`, the
  request says which function was called but not what it asks for.

The response type is the call's type argument (`invoke<Long>(…)`), or else the type the code
expects the result to have.

The form names what a reader sees at the call: the class, the function and its parameter. It
is one word for the shell, needs no quotes, and keeps any particular framework out of the
tool. A place serves where the name of the parameter is not known: in a degraded model whose
jars do not hold the client, an argument given without its name cannot be matched with its
parameter, so `ServiceClient.invoke:2` reads it where `:functionName` does not.

#### What a request sends and reads

Of each request the analysis also reads what it sends, what the code reads of the answer,
the parameters of its target and the arguments of a client's call, each with its type
(`sends`, `receives`, `parameters` and `arguments` in [the IR format](ir.md#requests)):

- **The body.** `noBody()` sends none. `ofString(…)` and `ofByteArray(…)` of what
  `writeValueAsString` or `writeValueAsBytes` of a Jackson `ObjectMapper`, or `encodeToString`
  of kotlinx.serialization, write send the static type of the object written. Other text
  sends text, with the media type of the `Content-Type` header where the chain sets one. A
  body the code reads from elsewhere, such as a file, is unknown. A helper that sends no body
  for an empty text and the text otherwise sends the text: the output of a writer is never
  empty.
- **The answer** is followed forward from the call that sends it: into the variables it is
  kept in, into the functions of the sources it is handed to, and back out of a helper that
  returns it into the code that called the helper, as deep as `--max-depth` allows, into one
  function only and never into one it is already in. It is read as the type that `readValue`
  of Jackson (with a class, a `TypeReference`, or the reified form of the Kotlin module), or
  `decodeFromString` of kotlinx.serialization (with a serializer or a type argument), reads
  it as, also from `ofInputStream()` and `ofByteArray()`. An answer kept or returned as text
  is text; one that is thrown away, or whose body is never asked for, is not read; one that
  goes where it cannot be followed is unknown. The answer of a client is its type argument,
  or the type the code expects of the result.
- **Parameters.** Each `{…}` of the path and each parameter of the query has the static type
  of the expression that gives it.
- **Arguments.** Each argument of a client's call has the static type of its expression;
  the argument that names the operation is not one of them. Where a constant names the
  operation, the request says which (`constant`).

**Models.** Each type a request sends or reads, and each type those reach, is in the
`types` table of `inventory.json`, by its fully qualified name, with its source: a class
with the properties of its primary constructor and its public properties with a field,
without those annotated `@JsonIgnore`, `@Transient` (Kotlin's or kotlinx.serialization's);
nullable where its type is, optional where it has a default; an enum with its values; a
sealed class with `@JsonTypeInfo` and `@JsonSubTypes`, or one of kotlinx.serialization, as
the alternatives it stands for, with the property that tells them apart. A property's name
on the wire comes from `@JsonProperty` on any use site, `@SerialName`, `@JsonNaming` with a
standard strategy, or `quarkus.jackson.property-naming-strategy` in the configuration of the
module that makes the request. Generic types keep their type parameters, which each use
fills in. A type of a jar is read from the jar; one the model does not have is named and
marked as not read. A type that holds itself is listed once. Standard types have no entry;
the OpenAPI documents write them so:

| Kotlin or Java type | OpenAPI `type` and `format` |
| --- | --- |
| `String`, `Char`, `CharSequence` | `string` |
| `Boolean` | `boolean` |
| `Byte`, `Short`, `Int`, `Long` | `integer`, `int8`, `int16`, `int32`, `int64` |
| `UByte`, `UShort`, `UInt`, `ULong` | `integer`, `uint8`, `uint16`, `uint32`, `uint64` |
| `Float`, `Double`, `BigDecimal` | `number`, `float`, `double`, `decimal` |
| `BigInteger` | `integer` |
| `UUID` (Java's and Kotlin's) | `string`, `uuid` |
| `URI`, `URL` | `string`, `uri` |
| `ByteArray` | `string` with `contentEncoding: base64` |
| `Instant`, `OffsetDateTime`, `ZonedDateTime`, `Date` (and the `Instant` of `kotlin.time` and `kotlinx.datetime`) | `string`, `date-time` |
| `LocalDate` (Java's and `kotlinx.datetime`'s) | `string`, `date` |
| `OffsetTime` | `string`, `time` |
| `Duration` (Java's and Kotlin's) | `string`, `duration` |
| `LocalDateTime`, `LocalTime`, `Year`, `YearMonth`, `MonthDay`, `Period`, `ZoneId`, `ZoneOffset`, `DayOfWeek`, `Month`, `Currency`, `Locale` | `string` |
| `List`, `Collection`, `Iterable`, `Array` and their Java and mutable kinds, the primitive arrays | `array` of the type argument |
| `Set` and its kinds | `array` with `uniqueItems` |
| `Map` and its kinds | `object` with `additionalProperties` of the value type |
| `Any`, `JsonElement`, `JsonNode` | any value |
| `JsonObject`, `ObjectNode` | `object` |

In a degraded model, annotations and calls of Jackson and kotlinx.serialization that do not
resolve are read by the names they are written with.

#### Hosts

A key is read in each of the three forms MicroProfile Config has:

- `@ConfigProperty(name = "billing.url")` on a constructor parameter or a property;
- `ConfigProvider.getConfig().getValue("billing.url", …)` and `getOptionalValue`, and the
  same on an injected `Config`;
- an accessor of an interface annotated `@ConfigMapping(prefix = "billing")`: `url()` reads
  `billing.url`, `baseUrl()` reads `billing.base-url`, and `@WithName` gives a name of its own.

In a degraded model, which lacks the library that declares these annotations, they are read by
the names they are written with, and are not among the annotations stderr warns did not
resolve.

The value of a key comes from the `application.yaml`, `application.yml` and
`application.properties` of the main resources of the module that holds the implementing
class; in a degraded model, from those beside its source directory (`src/main/resources` for
a class under `src/main/kotlin`). The value without a profile counts, else that of `%prod`;
other profiles do not. `application.yaml` counts before `application.yml`, and both before
`application.properties`. A `${…}` expression is kept as written. Where no file gives a value,
the default the code gives counts: `defaultValue`, `@WithDefault`, `orElse(…)`.

The host is the value of the key a URI starts with. The diagrams and the IR show it beside the
key, not in its place: `{billing.url}/v1/charges` with host `http://billing.example.com`.
`hosts` lists the value without a profile and that of `%prod`, each with its file and
profile; the endpoints page and the OpenAPI documents give each as a server.

The model holds the configuration files. One made by an earlier version of the tool holds
none: its requests keep their keys without hosts, and `analyse` says once that `--force`
makes the model again.

#### What is not read

What cannot be read is said, not guessed:

- A request built where the code that is read does not show, such as one the activity is
  handed, is listed by what is sent, `HTTP {request}`, without a method. A request that is
  built and never sent is no request.
- A builder kept in a variable may be told more in statements of their own, which are not
  read. Its request has the method and URI its chain gives, and no method is taken for
  granted: `HTTP {builder.build()}` where the chain gives neither.
- A computed part shows as `{name}`, and so does a computed method.
- Requests made inside libraries, in calls that may run one of several functions, and with
  other HTTP clients are not seen, unless `--client` names the client.
- The services' own code, their `@Path` annotations and their own OpenAPI documents are not
  read: the operations are those the activities call, as their code calls them. Neither are
  REST Client interfaces, JSON written by hand (it is text), or an `ObjectMapper` configured
  in code.

#### An example

`billing/src/main/kotlin/com/example/billing/BillingActivitiesImpl.kt`:

```kotlin
package com.example.billing

import com.example.clients.ServiceClient
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class BillingActivitiesImpl(
    @ConfigProperty(name = "billing.url") private val billingUrl: String,
    private val client: ServiceClient,
) : BillingActivities {
    private val http = HttpClient.newHttpClient()

    override fun charge(orderId: String): String {
        val request = HttpRequest.newBuilder(URI.create("$billingUrl/v1/charges/$orderId"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()
        val receipt = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        client.invoke<Long>(functionName = "pricing-quote", payload = receipt)
        return receipt
    }
}
```

With `billing.url: http://billing.example.com` in `billing/src/main/resources/application.yaml`
and `--client ServiceClient.invoke:functionName`, the card of `Charge` reads
`PUT billing +1` below its name and links to its entry on the endpoints page, its tooltip has
both requests whole, and the workflow's page has this table, whose requests link to their
operations on the page:

| Activity | Request | Host | Response | Source |
|---|---|---|---|---|
| `Charge` | [`PUT {billing.url}/v1/charges/{orderId}`](endpoints/index.md#put-billing-v1chargesorderid) | `http://billing.example.com` | `String` | `billing/src/main/kotlin/com/example/billing/BillingActivitiesImpl.kt:20` |
| `Charge` | [`ServiceClient · pricing-quote`](endpoints/index.md#fn-pricing-quote) |  | `Long` | `billing/src/main/kotlin/com/example/billing/BillingActivitiesImpl.kt:21` |

The request sends nothing and the code keeps the answer as text; the client's call has the
argument `payload: String` and returns `Long`.

### The endpoints page

Where an activity makes a request, `run` and `render` write a page of every operation the
activities call, next to the diagrams in `endpoints/`: `index.html` with the SVG diagrams,
`index.md` with the Mermaid diagrams, both with `--format all`. It is drawn from the IR files
and the `inventory.json` beside them, so activity types that no workflow runs are on it too.

- At the top, the services and operations counted, then a table of which workflows reach
  which services, and every activity type with the operations it calls.
- A section per service: its servers (the key, its file and its profile, a `${…}` expression
  kept as written), a small map of the activities that call it and its operations, then each
  operation: its method and path, or its signature for a client
  (`inventory-stock(payload: String) → List<Stock>`), where the name of a client's operation
  comes from, its parameters, what it sends and what is read of its answer, field by field
  with what is nullable and optional, and the activities that call it, with their workflows,
  their class where several implement one, and the line of the source.
- The models, each with its fields or values, the operations that send or read it, and the
  types it is part of. A part that was not read says so.

The HTML page holds everything it shows: it has no script and fetches nothing, and names the
typeface as the diagrams' pages do (carried in the file only with `--embed-font`). Its links
go to the diagrams of the same output, and to the sources with `--source-base-url`. The
Markdown page links to headings whose anchors are the same on GitHub and GitLab, and to the
Markdown pages of the workflows.

Next to them is an OpenAPI 3.1 document per service, `<service>.openapi.json`, for other
tools: its servers from the hosts (`x-config-key`, `x-config-file`, `x-profile`; a second key
in a target is a server variable), its paths, parameters, bodies and responses as the code has
them (an answer that is not read is a response without content), `x-used-by` on each
operation with the activities, interface, class, workflows and source that call it, and the
models in `components.schemas` with their names on the wire (`x-kotlin-type` and
`x-kotlin-name` where the Kotlin name differs). A client is a document with
`x-durable-atlas: {kind: client, …}` and one `post` path per operation, whose request body is
the call's arguments. These documents describe what the activities call, not the services
themselves.

The card of an activity that makes one request links to its operation on the HTML page; one
that makes several links to its entry, which lists them. `--no-requests` writes neither page
nor document. A diagram drawn by `overlay` links to neither.

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
| `--source-base-url <url>` | Link each card of an SVG diagram that links nowhere else, and each caller on the endpoints page, to `<url>/<file>#L<line>`. `{commit}` in the URL stands for the commit, as in `https://git.example.com/orders/blob/{commit}`. |
| `--background paper\|transparent` | Background of the SVG diagrams. |
| `--embed-font` | Put the typeface into each SVG file, so that it looks the same everywhere. Adds over a megabyte per file. |
| `--svg-timeout <seconds>` | The time allowed for arranging one SVG diagram, 30 by default; `0` sets no limit. |
| `--no-requests` | Draw activities without what they ask of other services: no line for it on a card, nothing in its tooltip, no table on the page, no endpoints page and no OpenAPI documents. The IR files keep the requests. |

### What the diagrams show

An SVG diagram draws every construct as a card with its own icon and colour, regions (loops,
parallel sections, compensations) as boxes around their steps, the signal, query and update
handlers in a band above the main flow, and a legend. Dotted green edges lead from a handler
to the awaits it can end. Below the drawing it says what is worth knowing about it: calls
that were skipped or left out of an overview, regions drawn as single nodes, a drawing made
more simply. The card of an activity that makes requests of other services names the first
below the activity, briefly, by its method and service (`POST billing`) or by `FN` and the
operation of a client (`FN pricing-quote`), and how many more there are (`+2`); it links to
the endpoints page. Its tooltip lists them all in full, each with its host, what it sends and
reads, its response type and, where several classes implement the activity, the class that
makes it.

The Mermaid diagram of the same workflow is a `flowchart TD` in a fenced block, which Git
hosts draw in place. Its page also lists the workflow's properties, links the diagrams of its
child workflows, says where each `unknown` node is, lists the calls that were skipped or
left out of an overview, and has a table of the requests of its activities: activity,
request, host, response type and source, each request linked to the endpoints page. A
Mermaid node of an activity names its first request as the card does.

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
  `--max-depth`, `--detail`, `--max-unresolved-pct`, `--jdk-home` and `--client` as `run` does, and
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
which can end with status 2 or 3, also give the `exitCode`. Where `run` or `render` wrote an
endpoints page, `endpoints` lists its `pages` and the `openapi` documents, and counts the
`services`, `operations` and `models`. stderr is as without it.

The files the tool writes are described by JSON Schemas (draft 2020-12) in its sources:

| File | Schema |
| --- | --- |
| `<workflow>.ir.json` | [`workflow-ir.schema.json`](../ir/src/main/resources/io/github/iw/durableatlas/ir/workflow-ir.schema.json), explained in [ir.md](ir.md) |
| `inventory.json` | [`workflow-inventory.schema.json`](../analyser/src/main/resources/io/github/iw/durableatlas/analyser/inventory/workflow-inventory.schema.json) |
| `standalone-activities.json` | [`standalone-activities.schema.json`](../analyser/src/main/resources/io/github/iw/durableatlas/analyser/inventory/standalone-activities.schema.json) |
| `model/<sha>/index.json`, `modules/*.json` | [`model-index.schema.json`](../model-extractor/src/main/resources/io/github/iw/durableatlas/modelextractor/model-index.schema.json), [`module-manifest.schema.json`](../model-extractor/src/main/resources/io/github/iw/durableatlas/modelextractor/module-manifest.schema.json) |
| `<workflow>.coverage.json` | [`workflow-coverage.schema.json`](../overlay/src/main/resources/io/github/iw/durableatlas/overlay/workflow-coverage.schema.json) |
| a version map | [`version-map.schema.json`](../overlay/src/main/resources/io/github/iw/durableatlas/overlay/version-map.schema.json) |
