package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.ConfiguredValue
import io.github.iw.durableatlas.ir.ModelKind
import io.github.iw.durableatlas.ir.ParameterLocation
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.TypeName
import io.github.iw.durableatlas.ir.WireNames
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Context
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Operation
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Service
import io.github.iw.durableatlas.render.endpoints.EndpointSet.ServiceKind
import io.github.iw.durableatlas.render.svg.Links
import io.github.iw.durableatlas.render.svg.Xml
import io.github.iw.durableatlas.render.svg.text.FontWeight
import io.github.iw.durableatlas.render.svg.text.InterFont
import java.util.Base64

/**
 * The endpoints page as one HTML file that holds everything it shows: no script, nothing fetched,
 * the typeface named as the HTML pages of the diagrams name it and carried in the file only where
 * the diagrams carry theirs. Folding long parts is left to `<details>`.
 *
 * @param workflowFile the diagram of a workflow type, relative to the page; `null` where none was written
 * @param indexFile the index of the diagrams, relative to the page
 * @param sourceLink the link to a line of the sources, where a base URL for them was given
 */
internal class EndpointsHtml(
    private val set: EndpointSet,
    private val workflowFile: (String) -> String?,
    private val indexFile: String?,
    private val sourceLink: (String, Int) -> String?,
    private val embedFont: Boolean,
) {
    private val shapes = Shapes(set.types)
    private val out = StringBuilder()

    private fun line(text: String) {
        out.append(text).append('\n')
    }

    fun render(): String {
        out.setLength(0)
        line("<!doctype html>")
        line("<html lang=\"en\">")
        line("<head>")
        line("<meta charset=\"utf-8\">")
        line("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        line("<title>Endpoints</title>")
        line("<style>")
        if (embedFont) {
            for (weight in FontWeight.entries) {
                val data = Base64.getEncoder().encodeToString(InterFont.bytes(weight))
                line("@font-face{font-family:${InterFont.FAMILY};font-style:normal;font-weight:${weight.css};src:url(data:font/otf;base64,$data) format(\"opentype\")}")
            }
        }
        out.append(STYLE)
        line("</style>")
        line("</head>")
        line("<body>")
        line("<div class=\"wrap\">")
        header()
        line("<div class=\"layout\">")
        index()
        line("<main>")
        overview()
        activities()
        set.services.forEach(::service)
        models()
        note()
        line("</main>")
        line("</div>")
        line("</div>")
        line("</body>")
        line("</html>")
        return out.toString()
    }

    // ---- head of the page

    private fun header() {
        val operations = set.operations
        line("<header class=\"top\">")
        indexFile?.let { line("<p class=\"eyebrow\"><a href=\"${attr(Links.encodePath(it))}\">All workflows</a></p>") }
        line("<h1>Endpoints</h1>")
        line(
            "<p class=\"lede\">Every service operation the activities call, what it sends and gets back, and the workflows that depend on it, " +
                "as the code of ${commits()} shows them. Each service is also an OpenAPI&nbsp;3.1 document next to this page.</p>",
        )
        line("<dl class=\"stats\">")
        val stats = listOf(
            "Services" to set.services.size,
            "Operations" to operations.size,
            "Calls" to operations.sumOf { it.callers.size },
            "Models" to set.models.size,
            "Workflows" to set.workflows.size,
        )
        for ((name, count) in stats) line("<div><dt>$name</dt><dd>$count</dd></div>")
        line("</dl>")
        line(
            "<p class=\"files\"><span class=\"label\">OpenAPI</span> " +
                set.services.joinToString(" ") { "<a href=\"${attr(Links.encodePath(it.file))}\"><code>${text(it.file)}</code></a>" } + "</p>",
        )
        line("</header>")
    }

    private fun commits(): String =
        when (set.commits.size) {
            0 -> "the analysed sources"
            1 -> "commit <code>${text(set.commits.single())}</code>"
            else -> "commits " + set.commits.joinToString(", ") { "<code>${text(it)}</code>" }
        }

    private fun index() {
        line("<nav class=\"index\" aria-label=\"Index\">")
        line("<p class=\"index-h\">Page</p>")
        line("<ul><li><a href=\"#who-calls-what\">Who calls what</a></li><li><a href=\"#activities\">Activities</a></li>" + (if (set.models.isNotEmpty()) "<li><a href=\"#models\">Models</a></li>" else "") + "</ul>")
        line("<p class=\"index-h\">Services</p>")
        line("<ul>")
        for (service in set.services) {
            line(
                "<li><a href=\"#${attr(service.id)}\"><span class=\"dot${if (service.kind == ServiceKind.CLIENT) " dot-client" else ""}\"></span>" +
                    "${text(service.name)}<span class=\"n\">${service.operations.size}</span></a></li>",
            )
        }
        line("</ul>")
        line("</nav>")
    }

    // ---- who calls what

    private fun overview() {
        line("<section class=\"block\" id=\"who-calls-what\">")
        line("<div class=\"block-head\"><h2>Who calls what</h2>")
        line(
            "<p>Each workflow against each service its activities call: the number is how many of the service's operations " +
                "the workflow reaches. A service's own section draws its callers.</p></div>",
        )
        val rows = set.workflows.map { it as String? } + (if (set.activities.any { it.workflows.isEmpty() }) listOf(null) else emptyList())
        line("<div class=\"scroll\"><table class=\"matrix\">")
        line("<thead><tr><th class=\"corner\">Workflow</th>" + set.services.joinToString("") { "<th class=\"col\"><a href=\"#${attr(it.id)}\" title=\"${attr(it.name)}\"><span>${text(it.name)}</span></a></th>" } + "</tr></thead>")
        line("<tbody>")
        for (workflow in rows) {
            val activities = set.activities.filter { if (workflow == null) it.workflows.isEmpty() else workflow in it.workflows }
            val head = if (workflow == null) "<span class=\"muted\">No workflow</span>" else workflowLink(workflow)
            val cells = set.services.joinToString("") { service ->
                val reached = activities.flatMap { it.calls }.map { it.operation }.filter { it.service == service.name }.distinct().size
                if (reached == 0) {
                    "<td></td>"
                } else {
                    val who = workflow ?: "Activities no workflow runs"
                    "<td><a href=\"#${attr(service.id)}\" title=\"${attr("$who → ${service.name}: ${count(reached, "operation")}")}\">$reached</a></td>"
                }
            }
            line("<tr><th>$head</th>$cells</tr>")
        }
        line("</tbody></table></div>")
        line("</section>")
    }

    // ---- activities

    private fun activities() {
        line("<section class=\"block\" id=\"activities\">")
        line("<div class=\"block-head\"><h2>Activities</h2><p>Each activity type that calls another service, the operations it calls in the order of its code, and the workflows that run it.</p></div>")
        line("<div class=\"scroll\"><table class=\"acts\">")
        line("<thead><tr><th>Activity</th><th>Calls</th><th>Workflows</th></tr></thead>")
        line("<tbody>")
        for (activity in set.activities) {
            val interfaces = activity.interfaces.joinToString(", ") { "<code>${text(it.substringAfterLast('.'))}</code>" }
            val calls = activity.calls.joinToString("") { call ->
                val operation = call.operation
                "<li><a href=\"#${attr(operation.id)}\">${badge(operation)} ${text(shortTitle(operation))}</a>" +
                    (call.request.className?.let { " <span class=\"muted\">· ${text(it.substringAfterLast('.'))}</span>" } ?: "") + "</li>"
            }
            val workflows = activity.workflows.joinToString(", ") { workflowLink(it) }.ifEmpty { "<span class=\"muted\">none of these sources</span>" }
            line(
                "<tr id=\"${attr(activity.id)}\"><th>${chip(activity.name, null)}" + (if (interfaces.isEmpty()) "" else "<div class=\"iface\">$interfaces</div>") +
                    "</th><td><ul class=\"calls\">$calls</ul></td><td>$workflows</td></tr>",
            )
        }
        line("</tbody></table></div>")
        line("</section>")
    }

    // ---- services

    private fun service(service: Service) {
        val kind = if (service.kind == ServiceKind.CLIENT) "Client" else "HTTP service"
        line("<section class=\"service\" id=\"${attr(service.id)}\">")
        line("<div class=\"svc-head\"><span class=\"svc-kind\">$kind · ${count(service.operations.size, "operation")}</span>")
        line("<h2>${text(service.name)}</h2>")
        line("<a class=\"oas\" href=\"${attr(Links.encodePath(service.file))}\">OpenAPI <code>${text(service.file)}</code></a>")
        servers(service)
        line("</div>")
        val activityIds = set.activities.associate { it.name to it.id }
        line("<div class=\"map-scroll\">${ServiceMaps.svg(service, activityIds)}</div>")
        line("<div class=\"ops\">")
        service.operations.forEach { operation(service, it) }
        line("</div>")
        line("</section>")
    }

    private fun servers(service: Service) {
        if (service.kind == ServiceKind.CLIENT) {
            val call = "${service.client ?: "client"}.${service.call ?: "invoke"}(…)"
            line("<p class=\"server\"><span>Called as <code>${text(call)}</code>, one operation per name it is asked for</span></p>")
            return
        }
        for (server in service.servers) {
            when {
                server.key != null && server.values.isEmpty() ->
                    line("<p class=\"server\"><span><code class=\"key\">{${text(server.key)}}</code></span><span class=\"muted\">no configuration file of the module gives it</span>${variable(server.variable)}</p>")
                server.key != null -> for (value in server.values) {
                    line("<p class=\"server\"><span><code>${text(value.value)}</code>${server.variable?.let { "<code class=\"var\">{${text(it.key)}}</code>" }.orEmpty()}</span>${origin(value)}${variable(server.variable, value.profile)}</p>")
                }
                server.literal != null -> line("<p class=\"server\"><span><code>${text(server.literal)}</code></span><span class=\"muted\">written in the code</span></p>")
                server.computed != null -> line("<p class=\"server\"><span><code class=\"key\">${text(server.computed)}</code></span><span class=\"muted\">computed in the code</span></p>")
            }
        }
    }

    private fun origin(value: ConfiguredValue): String =
        "<span>from <code class=\"key\">${text(value.key)}</code>${value.file?.let { " in <code>${text(it)}</code>" } ?: ", the default the code gives"}</span>" +
            (value.profile?.let { "<span class=\"profile\">profile <code>${text(it)}</code></span>" } ?: "")

    private fun variable(variable: EndpointSet.Variable?, profile: String? = null): String {
        if (variable == null) return ""
        val value = variable.values.firstOrNull { it.profile == profile } ?: variable.values.firstOrNull { it.profile == null } ?: variable.values.firstOrNull()
        return "<span><code class=\"var\">{${text(variable.key)}}</code> = " +
            (value?.let { "<code>${text(it.value)}</code>${it.file?.let { file -> " in <code>${text(file)}</code>" } ?: ", the default the code gives"}" } ?: "<span class=\"muted\">not configured</span>") +
            "</span>"
    }

    private fun operation(service: Service, operation: Operation) {
        line("<article class=\"op\" id=\"${attr(operation.id)}\">")
        val head = if (service.kind == ServiceKind.CLIENT) signature(operation) else "<code class=\"path\">${pathHtml(operation.path)}</code>"
        line("<div class=\"op-line\">${badge(operation)}$head</div>")
        if (operation.method != null && operation.badge == EndpointSet.HTTP) {
            line("<p class=\"const\">The method is computed in the code as <code>${text(operation.method)}</code>.</p>")
        } else if (operation.method == null && service.kind == ServiceKind.HTTP) {
            line("<p class=\"const\">The code that sets the method is not seen.</p>")
        }
        if (operation.constants.isNotEmpty()) {
            line("<p class=\"const\">Operation name from " + operation.constants.joinToString(", ") { "<code>${text(it)}</code>" } + "</p>")
        }
        if (operation.parameters.isNotEmpty()) {
            line("<div class=\"params\"><span class=\"label\">Parameters</span>" + operation.parameters.joinToString("") { parameter ->
                "<span class=\"param\"><span>${text(parameter.name)}</span><span class=\"in\">${if (parameter.location == ParameterLocation.PATH) "path" else "query"}</span>" +
                    (parameter.types.joinToString(" | ") { typeHtml(TypeName.parse(it)) }.ifEmpty { "<span class=\"t-any\">type not known</span>" }) +
                    (parameter.values.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " = ") { "<span class=\"val\">${text(it)}</span>" } ?: "") +
                    "</span>"
            } + "</div>")
        }
        line("<div class=\"panels\">")
        if (service.kind == ServiceKind.CLIENT) {
            argumentsPanel(operation)
            panel("Returns", operation.receives, receiving = true)
        } else {
            panel("Request", operation.sends, receiving = false)
            panel("Response", operation.receives, receiving = true)
        }
        line("</div>")
        callers(operation)
        line("</article>")
    }

    private fun signature(operation: Operation): String {
        val arguments = operation.arguments.joinToString(", ") { argument ->
            "<span class=\"sp\">${text(argument.label)}</span>: " + argument.types.joinToString(" | ") { typeHtml(TypeName.parse(it)) }.ifEmpty { "<span class=\"t-any\">?</span>" }
        }
        val answers = operation.receives.joinToString(" | ") { payload ->
            if (payload.kind == PayloadKind.TYPE) typeHtml(TypeName.parse(payload.type.orEmpty())) else "<span class=\"t-any\">${text(Shapes.describe(payload).lowercase())}</span>"
        }.ifEmpty { "<span class=\"t-any\">not seen</span>" }
        return "<code class=\"sig\"><span class=\"sn\">${pathHtml(operation.path)}</span>($arguments) → $answers</code>"
    }

    private fun argumentsPanel(operation: Operation) {
        line("<div class=\"panel\"><div class=\"label\">Arguments</div>")
        if (operation.arguments.isEmpty()) {
            line("<div class=\"panel-head\"><span class=\"none\">None besides the operation's name</span></div>")
        }
        for (argument in operation.arguments) {
            for (type in argument.types.ifEmpty { listOf("") }) {
                if (type.isEmpty()) {
                    line("<div class=\"panel-head\"><span class=\"sp\">${text(argument.label)}</span> <span class=\"none\">type not known</span></div>")
                    continue
                }
                val name = TypeName.parse(type)
                line("<div class=\"panel-head\"><span class=\"sp\">${text(argument.label)}</span>: <span class=\"type\">${typeHtml(name)}</span></div>")
                shapeHtml(name, Context(null, null))
            }
        }
        line("</div>")
    }

    private fun panel(label: String, payloads: List<Payload>, receiving: Boolean) {
        line("<div class=\"panel\"><div class=\"label\">$label</div>")
        if (payloads.isEmpty()) line("<div class=\"panel-head\"><span class=\"none\">${if (receiving) "Not seen" else "Not seen: the code that builds the request is not read"}</span></div>")
        for (payload in payloads) {
            val facts = Shapes.facts(payload).joinToString("") { "<span class=\"media\">${text(it)}</span>" }
            if (payload.kind == PayloadKind.TYPE) {
                val type = TypeName.parse(payload.type.orEmpty())
                line("<div class=\"panel-head\"><span class=\"type\">${typeHtml(type)}</span>$facts</div>")
                shapeHtml(type, Context(payload.format, payload.naming))
            } else {
                line("<div class=\"panel-head\"><span class=\"state s-${payload.kind.name.lowercase().replace('_', '-')}\">${text(Shapes.describe(payload))}</span>$facts</div>")
                Shapes.explain(payload, receiving)?.let { line("<p class=\"why\">${text(it)}</p>") }
            }
        }
        line("</div>")
    }

    private fun shapeHtml(type: TypeName, context: Context) {
        when (val shape = shapes.of(type, context)) {
            is Shapes.Shape.Object -> fields(shape.fields)
            is Shapes.Shape.Enum -> line("<ul class=\"values\">" + shape.values.joinToString("") { "<li>${text(it)}</li>" } + "</ul>")
            is Shapes.Shape.Alternatives -> alternatives(shape)
            is Shapes.Shape.NotRead -> line("<p class=\"why\">Not read: the analysis did not have the source of <code>${text(shape.model)}</code>, so its fields are not known.</p>")
            null -> Unit
        }
    }

    private fun fields(fields: List<Shapes.Field>) {
        if (fields.isEmpty()) {
            line("<p class=\"why\">No properties.</p>")
            return
        }
        line(
            "<ul class=\"fields\">" + fields.joinToString("") { field ->
                val kotlin = if (field.kotlinName != field.name) " title=\"${attr("Kotlin: ${field.kotlinName}")}\"" else ""
                "<li><span class=\"f-name\"$kotlin>${text(field.name)}</span><span class=\"f-type\">${typeHtml(field.type)}" +
                    (if (field.optional) "<span class=\"opt\">optional</span>" else "") + "</span></li>"
            } + "</ul>",
        )
    }

    private fun alternatives(shape: Shapes.Shape.Alternatives) {
        val discriminator = shape.discriminator?.let { "<span class=\"muted\">told apart by </span><code>${text(it)}</code>" } ?: "<span class=\"muted\">no discriminator written</span>"
        line(
            "<ul class=\"fields\"><li class=\"one\">One of · $discriminator</li>" + shape.subtypes.joinToString("") { (type, value) ->
                "<li><span class=\"f-name\">${text(value)}</span><span class=\"f-type\">${typeHtml(TypeName(type))}</span></li>"
            } + "</ul>",
        )
    }

    private fun callers(operation: Operation) {
        line("<div class=\"usedby\"><span class=\"label\">Called by</span><ul>")
        for (caller in operation.callers) {
            val activity = set.activity(caller.activity)
            // The workflows are one item of the row, so that the commas between them stay with them.
            val workflows = if (caller.workflows.isEmpty()) "<span class=\"muted\">in no workflow of these sources</span>" else "<span class=\"muted\">in</span> <span>" + caller.workflows.joinToString(", ") { workflowLink(it) } + "</span>"
            val type = caller.className?.let { " <span class=\"muted\">· ${text(it.substringAfterLast('.'))}</span>" } ?: ""
            val place = "${caller.loc.file}:${caller.loc.line}"
            val source = sourceLink(caller.loc.file, caller.loc.line)?.let { "<a class=\"src\" href=\"${attr(it)}\">${text(place)}</a>" } ?: "<span class=\"src\">${text(place)}</span>"
            line("<li>${chip(caller.activity, activity?.id)} $workflows$type$source</li>")
        }
        line("</ul></div>")
    }

    // ---- models

    private fun models() {
        if (set.models.isEmpty()) return
        line("<section class=\"block\" id=\"models\">")
        line("<div class=\"block-head\"><h2>Models</h2><p>Every type a request sends or reads its answer as, and the types those reach. Names are those the library writes; a type whose source is not in the model is named but not read.</p></div>")
        line("<div class=\"model-grid\">")
        for (model in set.models) {
            val kind = when (model.type.kind) {
                ModelKind.OBJECT -> "object"
                ModelKind.ENUM -> "enum"
                ModelKind.ALTERNATIVES -> "one of"
                ModelKind.NOT_READ -> "not read"
            }
            val generic = model.type.typeParameters.takeIf { it.isNotEmpty() }?.joinToString(", ", "&lt;", "&gt;") { text(it) }.orEmpty()
            line("<article class=\"model\" id=\"${attr(model.id)}\">")
            line("<header><h3>${text(model.simpleName)}$generic</h3><span class=\"m-kind\">$kind</span></header>")
            line("<p class=\"m-svc\"><code>${text(model.name)}</code>${model.type.loc?.let { " · ${text("${it.file}:${it.line}")}" }.orEmpty()}</p>")
            modelBody(model)
            val uses = model.sentBy.map { it to "sends it" } + model.returnedBy.map { it to "reads it" }
            if (uses.isNotEmpty() || model.partOf.isNotEmpty()) {
                line("<div class=\"m-use\">")
                for ((operation, how) in uses) line("<div><a href=\"#${attr(operation.id)}\">${badge(operation)}${text(shortTitle(operation))}</a> <span>$how</span></div>")
                if (model.partOf.isNotEmpty()) {
                    line("<div>Part of " + model.partOf.joinToString(", ") { name -> set.model(name)?.let { "<a href=\"#${attr(it.id)}\">${text(it.simpleName)}</a>" } ?: text(name) } + "</div>")
                }
                line("</div>")
            }
            line("</article>")
        }
        line("</div>")
        line("</section>")
    }

    private fun modelBody(model: EndpointSet.Model) {
        val type = model.type
        when (type.kind) {
            ModelKind.OBJECT -> {
                if (type.properties.isEmpty()) {
                    line("<p class=\"why\">No properties.</p>")
                    return
                }
                line(
                    "<ul class=\"fields\">" + type.properties.joinToString("") { property ->
                        val names = model.contexts.map { WireNames.property(type, property, it.format, it.naming) }.distinct()
                        val shown = names.joinToString(" / ")
                        val kotlin = if (names != listOf(property.name)) " title=\"${attr("Kotlin: ${property.name}")}\"" else ""
                        "<li><span class=\"f-name\"$kotlin>${text(shown)}</span><span class=\"f-type\">${typeHtml(TypeName.parse(property.type))}" +
                            (if (property.optional) "<span class=\"opt\">optional</span>" else "") + "</span></li>"
                    } + "</ul>",
                )
            }
            ModelKind.ENUM -> line(
                "<ul class=\"values\">" + type.values.joinToString("") { value ->
                    "<li>${text(model.contexts.map { WireNames.value(value, it.format) }.distinct().joinToString(" / "))}</li>"
                } + "</ul>",
            )
            ModelKind.ALTERNATIVES -> {
                val context = model.contexts.firstOrNull() ?: Context(null, null)
                (shapes.of(TypeName(model.name), context) as? Shapes.Shape.Alternatives)?.let(::alternatives)
            }
            ModelKind.NOT_READ -> line("<p class=\"why\">Not read: the analysis did not have its source, which is in a library or not in the model, so its fields are not known.</p>")
        }
    }

    // ---- foot

    private fun note() {
        line("<footer class=\"note\">")
        line(
            "<p>An HTTP service is named after the first label of its host, or more labels where two hosts would share a name, " +
                "or after the configuration key its targets start with where no host is configured. A client is one service per class " +
                "named with <code>--client</code>, with one operation per name it is asked for.</p>",
        )
        line(
            "<p>What is read: the body a request sends and how it is made, the type the answer is read as and with which library, " +
                "the parameters of the target with the types of what gives them, the arguments of a client's call, and the shape of " +
                "each model in the sources. What is not: the services' own code and descriptions, an <code>ObjectMapper</code> " +
                "configured in code, and JSON written by hand, which is shown as text.</p>",
        )
        line("</footer>")
    }

    // ---- pieces

    private fun chip(activity: String, id: String?): String {
        val inner = "<svg viewBox=\"0 0 20 20\" aria-hidden=\"true\">${ACTIVITY_ICON}</svg>${text(activity)}"
        return if (id == null) "<span class=\"act-chip\">$inner</span>" else "<a class=\"act-chip\" href=\"#${attr(id)}\">$inner</a>"
    }

    private fun badge(operation: Operation): String = "<span class=\"m m-${operation.badge.lowercase()}\">${text(operation.badge)}</span>"

    /** An operation by its service and path, or its name for a client: `billing /v1/charges`, `pricing-quote`. */
    private fun shortTitle(operation: Operation): String =
        if (operation.kind == ServiceKind.CLIENT) operation.path else "${operation.service} ${operation.path}"

    private fun workflowLink(workflow: String): String =
        workflowFile(workflow)?.let { "<a class=\"wf-name\" href=\"${attr(Links.encodePath(it))}\">${text(workflow)}</a>" } ?: "<span class=\"wf-name\">${text(workflow)}</span>"

    private fun pathHtml(path: String): String =
        Targets.pieces(path, emptySet()).joinToString("") { piece ->
            when (piece) {
                is Targets.Piece.Text -> text(piece.text)
                is Targets.Piece.Slot -> "<span class=\"p\">{${text(piece.name)}}</span>"
            }
        }

    /** A type with simple names, each model linked to its box. */
    private fun typeHtml(type: TypeName): String {
        if (type.isStar) return "*"
        val model = set.model(type.name)
        val name = if (model != null) "<a class=\"t-ref\" href=\"#${attr(model.id)}\">${text(type.simpleName)}</a>" else text(type.simpleName)
        val arguments = if (type.arguments.isEmpty()) "" else type.arguments.joinToString(", ", "&lt;", "&gt;") { typeHtml(it) }
        return name + arguments + (if (type.nullable) "<span class=\"t-null\" title=\"may be null\">?</span>" else "")
    }

    private fun count(count: Int, what: String): String = if (count == 1) "1 $what" else "$count ${what}s"

    private fun text(value: String): String = Xml.text(value)

    private fun attr(value: String): String = Xml.attribute(value)

    companion object {
        /** The icon of an activity in the diagrams, drawn in the colour of the text around it. */
        private const val ACTIVITY_ICON = "<path d=\"M11.5 2L4.5 11h4.6l-1 7 7.4-9.4h-4.8z\" fill=\"currentColor\"/>"

        private val STYLE = """
:root{--paper:#F6F4EE;--surface:#FFFFFF;--sunk:#F0EDE5;--ink:#101820;--ink-2:#344054;--muted:#5B6472;--rule:#D9D4C7;--rule-soft:#E7E3D9;--edge:#B9B3A4;
--activity:#2563EB;--activity-ink:#1D4ED8;--activity-tint:#EAF1FE;--on-activity:#FFFFFF;--key:#9D174D;
--get:#166534;--get-bg:#E3F2E7;--post:#1D4ED8;--post-bg:#E6EEFD;--put:#9A4A07;--put-bg:#FBEEDA;--patch:#6D28D9;--patch-bg:#EFE8FE;
--delete:#B42318;--delete-bg:#FCE8EB;--fn:#0F766E;--fn-bg:#DFF2EE;--other:#475569;--other-bg:#EEF0F4;
--sans:Inter,system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,"Liberation Mono",monospace}
@media (prefers-color-scheme:dark){:root{--paper:#0F1216;--surface:#171B21;--sunk:#1D222A;--ink:#E8EAED;--ink-2:#C3C9D2;--muted:#98A2B3;--rule:#2B323C;--rule-soft:#242A33;--edge:#3D4652;
--activity:#3B76F0;--activity-ink:#8DB2FF;--activity-tint:#172440;--key:#F28BB8;
--get:#7AD49A;--get-bg:#15291D;--post:#8DB2FF;--post-bg:#172540;--put:#F0B163;--put-bg:#2E2213;--patch:#BBA2FA;--patch-bg:#241B3A;
--delete:#F4978E;--delete-bg:#341B1E;--fn:#5FD0C0;--fn-bg:#11292A;--other:#C3C9D2;--other-bg:#242A33;color-scheme:dark}}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--paper);color:var(--ink);font:400 14px/1.55 var(--sans);padding:28px 16px 56px}
a{color:var(--activity-ink)}
code{font-family:var(--mono);font-size:.92em}
.wrap{max-width:1240px;margin:0 auto}
.top{display:grid;gap:10px;padding-bottom:24px;border-bottom:1px solid var(--rule);margin-bottom:28px}
.eyebrow{margin:0;font:600 12px/1 var(--sans)}
.top h1{margin:0;font:700 34px/1.1 var(--sans);letter-spacing:-.02em}
.lede{margin:0;max-width:72ch;font-size:15px;color:var(--ink-2)}
.stats{display:flex;flex-wrap:wrap;gap:6px 22px;margin:4px 0 0}
.stats div{display:flex;align-items:baseline;gap:6px}
.stats dt{order:2;color:var(--muted);font-size:13px}
.stats dd{order:1;margin:0;font:600 18px/1 var(--sans);font-variant-numeric:tabular-nums}
.files{margin:0;font-size:13px;display:flex;flex-wrap:wrap;gap:4px 10px;align-items:baseline}
.layout{display:grid;grid-template-columns:196px minmax(0,1fr);gap:40px;align-items:start}
.index{position:sticky;top:20px;max-height:calc(100vh - 40px);overflow:auto;display:grid;gap:6px;font-size:13px}
.index-h{margin:12px 0 2px;font:600 10.5px/1 var(--sans);letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.index-h:first-child{margin-top:0}
.index ul{list-style:none;margin:0;padding:0;display:grid;gap:1px}
.index a{display:flex;align-items:center;gap:8px;padding:3px 8px;margin-left:-8px;border-radius:6px;color:var(--ink);text-decoration:none}
.index .n{margin-left:auto;color:var(--muted);font-variant-numeric:tabular-nums;font-size:12px}
.dot{width:8px;height:8px;border-radius:2px;flex:none;background:var(--post)}
.dot-client{background:var(--fn);border-radius:50%}
main{min-width:0;display:grid;gap:8px}
.block{display:grid;gap:14px;padding-bottom:28px}
.block-head{display:grid;gap:4px}
.block-head h2{margin:0;font:650 21px/1.2 var(--sans);letter-spacing:-.01em}
.block-head p{margin:0;color:var(--muted);max-width:76ch}
.scroll,.map-scroll{overflow-x:auto;background:var(--surface);border:1px solid var(--rule);border-radius:10px}
.map-scroll{padding:4px}
.muted{color:var(--muted)}
.label{font:600 10.5px/1 var(--sans);letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.matrix{border-collapse:collapse;font-size:12.5px}
.matrix th,.matrix td{border-bottom:1px solid var(--rule-soft);padding:3px 6px}
.matrix thead th{vertical-align:bottom;border-bottom:1px solid var(--rule)}
.matrix th.col{padding:6px 2px}
.matrix th.col a{writing-mode:vertical-rl;transform:rotate(180deg);display:inline-block;max-height:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--ink);text-decoration:none;font:500 12px var(--mono)}
.matrix tbody th{text-align:left;font-weight:600;white-space:nowrap;position:sticky;left:0;background:var(--surface)}
.matrix td{text-align:center;min-width:20px;font-variant-numeric:tabular-nums}
.matrix td a{display:inline-block;min-width:18px;border-radius:4px;background:var(--activity-tint);text-decoration:none;font-weight:600}
.matrix .corner{text-align:left;color:var(--muted);font:600 10.5px var(--sans);letter-spacing:.1em;text-transform:uppercase}
.acts{border-collapse:collapse;width:100%;font-size:13px}
.acts th,.acts td{text-align:left;vertical-align:top;padding:7px 10px;border-bottom:1px solid var(--rule-soft)}
.acts thead th{font:600 10.5px var(--sans);letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.acts tr:target{background:var(--activity-tint)}
.acts .iface{margin-top:3px;font-size:12px;color:var(--muted)}
.calls{list-style:none;margin:0;padding:0;display:grid;gap:3px}
.calls a{color:var(--ink);text-decoration:none;font:500 12.5px var(--mono)}
.service{padding:26px 0 18px;border-top:1px solid var(--rule);display:grid;gap:14px}
.svc-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 12px}
.svc-kind{font:600 10.5px/1 var(--sans);letter-spacing:.1em;text-transform:uppercase;color:var(--muted);flex-basis:100%}
.svc-head h2{margin:0;font:600 20px/1.2 var(--mono)}
.oas{font-size:12.5px;margin-left:auto}
.server{flex-basis:100%;display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 16px;margin:2px 0 0;font-size:13px;color:var(--muted)}
.server code{color:var(--ink);overflow-wrap:anywhere}
.server .key,.server .var{color:var(--key)}
.server .profile code{color:var(--ink-2)}
.map{display:block}
.map .edge{fill:none;stroke:var(--edge);stroke-width:1.4}
.map .act-card{fill:var(--surface);stroke:var(--activity);stroke-width:1.5}
.map .act-tile{fill:var(--activity)}
.map .act-k{fill:var(--activity);font:700 9.5px var(--sans);letter-spacing:.06em}
.map .act-name{fill:var(--ink);font:600 13px var(--sans)}
.map .row-bg{fill:var(--sunk)}
.map .row-t{fill:var(--ink);font:500 11.5px var(--mono)}
.bt{font:600 9.5px var(--mono)}
.b-get{fill:var(--get-bg)}.t-get{fill:var(--get)}.b-post{fill:var(--post-bg)}.t-post{fill:var(--post)}
.b-put{fill:var(--put-bg)}.t-put{fill:var(--put)}.b-patch{fill:var(--patch-bg)}.t-patch{fill:var(--patch)}
.b-delete{fill:var(--delete-bg)}.t-delete{fill:var(--delete)}.b-fn{fill:var(--fn-bg)}.t-fn{fill:var(--fn)}
.b-head,.b-options,.b-trace,.b-http{fill:var(--other-bg)}.t-head,.t-options,.t-trace,.t-http{fill:var(--other)}
.ops{display:grid;gap:14px}
.op{background:var(--surface);border:1px solid var(--rule);border-radius:10px;padding:14px 16px 15px;display:grid;gap:12px;min-width:0}
.op:target{box-shadow:0 0 0 2px var(--activity);border-color:transparent}
.op-line{display:flex;flex-wrap:wrap;align-items:center;gap:8px 10px;min-width:0}
.m{font:600 11px/1 var(--mono);padding:4px 6px;border-radius:5px;letter-spacing:.03em;flex:none;display:inline-block}
.m-get{color:var(--get);background:var(--get-bg)}.m-post{color:var(--post);background:var(--post-bg)}
.m-put{color:var(--put);background:var(--put-bg)}.m-patch{color:var(--patch);background:var(--patch-bg)}
.m-delete{color:var(--delete);background:var(--delete-bg)}.m-fn{color:var(--fn);background:var(--fn-bg)}
.m-head,.m-options,.m-trace,.m-http{color:var(--other);background:var(--other-bg)}
.path,.sig{font:500 15px/1.35 var(--mono);color:var(--ink);overflow-wrap:anywhere;min-width:0}
.path .p,.sig .sp,.panel .sp,.sig .p{color:var(--key)}
.sig .sn{font-weight:600}
.const{margin:0;font-size:13px;color:var(--muted)}
.const code{color:var(--ink-2)}
.params{display:flex;flex-wrap:wrap;align-items:center;gap:6px 8px}
.params .label{margin-right:4px}
.param{display:inline-flex;align-items:baseline;gap:6px;padding:3px 8px;border:1px solid var(--rule);border-radius:6px;font:400 12px var(--mono)}
.param .in{font:500 10.5px var(--sans);color:var(--muted)}
.param .val{color:var(--key)}
.panels{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:10px}
.panel{background:var(--sunk);border-radius:8px;padding:10px 12px 11px;min-width:0}
.panel-head{margin-top:7px;display:flex;flex-wrap:wrap;gap:2px 10px;align-items:baseline;font:500 13px/1.4 var(--mono)}
.media{font:400 11.5px var(--mono);color:var(--muted)}
.none,.state{font:500 13px var(--sans);color:var(--muted)}
.s-unknown,.s-not-read{color:var(--key)}
.why{margin:6px 0 0;font-size:12.5px;color:var(--muted)}
.fields{list-style:none;margin:9px 0 0;padding:8px 0 0;border-top:1px solid var(--rule-soft);display:grid;gap:3px}
.fields li{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.25fr);gap:12px;font:400 12.5px/1.45 var(--mono)}
.fields li.one{display:block;font:500 12px var(--sans)}
.f-name{color:var(--ink);overflow-wrap:anywhere}
.f-type{color:var(--ink-2);overflow-wrap:anywhere}
.opt{font:500 10px var(--sans);color:var(--muted);margin-left:6px;letter-spacing:.03em;white-space:nowrap;overflow-wrap:normal;display:inline-block}
.values{display:flex;flex-wrap:wrap;gap:4px 6px;margin:9px 0 0;padding:8px 0 0;border-top:1px solid var(--rule-soft);list-style:none}
.values li{font:500 11.5px var(--mono);padding:2px 6px;border-radius:4px;background:var(--surface);color:var(--ink-2)}
.t-ref{color:var(--activity-ink);text-decoration:none;border-bottom:1px dotted currentColor}
.t-null,.t-any{color:var(--muted)}
.usedby{display:grid;gap:7px;padding-top:10px;border-top:1px solid var(--rule-soft)}
.usedby ul{list-style:none;margin:0;padding:0;display:grid;gap:6px}
.usedby li{display:flex;flex-wrap:wrap;align-items:center;gap:4px 8px;font-size:13px}
.act-chip{display:inline-flex;align-items:center;gap:6px;padding:3px 9px 3px 5px;border-radius:6px;background:var(--activity);color:var(--on-activity);font:600 12.5px/1.2 var(--sans);text-decoration:none}
.act-chip svg{width:14px;height:14px;flex:none}
.wf-name{font-weight:600;color:var(--ink)}
.src{margin-left:auto;font:400 11.5px var(--mono);color:var(--muted)}
.model-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:12px;align-items:start}
.model{background:var(--surface);border:1px solid var(--rule);border-radius:10px;padding:12px 14px 13px;display:grid;gap:4px;min-width:0}
.model:target{box-shadow:0 0 0 2px var(--activity);border-color:transparent}
.model header{display:flex;align-items:baseline;gap:8px}
.model h3{margin:0;font:600 14.5px var(--mono);overflow-wrap:anywhere}
.m-kind{margin-left:auto;font:600 10px var(--sans);letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
.m-svc{margin:0;font-size:12px;color:var(--muted);overflow-wrap:anywhere}
.model .values li{background:var(--sunk)}
.m-use{margin:8px 0 0;padding-top:8px;border-top:1px solid var(--rule-soft);display:grid;gap:3px;font-size:12px;color:var(--muted)}
.m-use a{color:var(--ink-2);text-decoration:none;font:400 11.5px var(--mono)}
.m-use .m{font-size:9.5px;padding:2px 4px;margin-right:4px}
.note{margin-top:10px;padding-top:18px;border-top:1px solid var(--rule);color:var(--muted);font-size:13px;display:grid;gap:8px;max-width:84ch}
.note p{margin:0}
@media (max-width:960px){.layout{grid-template-columns:minmax(0,1fr);gap:18px}.index{position:static;max-height:none}.index ul{display:flex;flex-wrap:wrap;gap:4px}.index a{margin-left:0;border:1px solid var(--rule)}}
@media (max-width:520px){.top h1{font-size:28px}.op{padding:12px}.fields li{grid-template-columns:minmax(0,1fr);gap:0}.src{margin-left:0;flex-basis:100%}}
""".trimStart()
    }
}
