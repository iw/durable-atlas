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
import io.github.iw.durableatlas.render.markdown.Markdown

/**
 * The endpoints page in Markdown, as Git hosts show it: what the HTML page says, with a Mermaid
 * flowchart for the map of each service and headings for the places others link to. The anchors
 * of the headings are the same on GitHub and GitLab (see [MarkdownAnchors]).
 */
internal class EndpointsMarkdown(
    private val set: EndpointSet,
    private val workflowFile: (String) -> String?,
    private val indexFile: String?,
    private val sourceLink: (String, Int) -> String?,
) {
    private val shapes = Shapes(set.types)
    private val headings = MarkdownAnchors.Headings()

    /** The anchor of each place others link to, by the id the HTML page gives it. */
    val anchors: Map<String, String>
    private val titles: Map<String, String>

    init {
        val anchors = LinkedHashMap<String, String>()
        val titles = HashMap<String, String>()
        fun take(id: String, text: String) {
            val (heading, anchor) = headings.take(text)
            anchors[id] = anchor
            titles[id] = heading
        }
        // In the order the page has them, so that the anchors are those the hosts give.
        take(PAGE, "Endpoints")
        take(WHO, "Who calls what")
        take(ACTIVITIES, "Activities")
        for (activity in set.activities) take(activity.id, "Activity ${activity.name}")
        for (service in set.services) {
            take(service.id, "Service ${service.name}")
            for (operation in service.operations) take(operation.id, operation.title)
        }
        if (set.models.isNotEmpty()) {
            take(MODELS, "Models")
            for (model in set.models) take(model.id, "Model ${model.name}")
        }
        this.anchors = anchors
        this.titles = titles
    }

    private val page = StringBuilder()

    private fun block(text: String) {
        page.append(text.trimEnd('\n')).append("\n\n")
    }

    fun render(): String {
        page.setLength(0)
        block("# ${title(PAGE)}")
        indexFile?.let { block(Markdown.link("All workflows", it)) }
        val operations = set.operations
        block(
            "Every service operation the activities call, what it sends and gets back, and the workflows that depend on it, " +
                "as the code of ${commits()} shows them. Each service is also an OpenAPI 3.1 document next to this page: " +
                set.services.joinToString(" · ") { Markdown.link(it.file, it.file) } + ".",
        )
        block(
            listOf(
                Markdown.row("Services", "Operations", "Calls", "Models", "Workflows"),
                "|---:|---:|---:|---:|---:|",
                Markdown.row(
                    set.services.size.toString(),
                    operations.size.toString(),
                    operations.sumOf { it.callers.size }.toString(),
                    set.models.size.toString(),
                    set.workflows.size.toString(),
                ),
            ).joinToString("\n"),
        )
        overview()
        activities()
        set.services.forEach(::service)
        models()
        return page.toString().trimEnd('\n') + "\n"
    }

    private fun commits(): String =
        when (set.commits.size) {
            0 -> "the analysed sources"
            1 -> "commit ${Markdown.code(set.commits.single())}"
            else -> "commits " + set.commits.joinToString(", ", transform = Markdown::code)
        }

    // ---- who calls what

    private fun overview() {
        block("## ${title(WHO)}")
        block("Each workflow and the services its activities call, with how many of each service's operations it reaches.")
        val rows = set.workflows.map { it as String? } + (if (set.activities.any { it.workflows.isEmpty() }) listOf(null) else emptyList())
        val lines = mutableListOf(Markdown.row("Workflow", "Services"), "|---|---|")
        for (workflow in rows) {
            val activities = set.activities.filter { if (workflow == null) it.workflows.isEmpty() else workflow in it.workflows }
            val reached = activities.flatMap { it.calls }.map { it.operation }.distinct().groupBy { it.service }
            val services = set.services.filter { it.name in reached }.joinToString(" · ") { service ->
                "${link(service.name, service.id)} (${reached.getValue(service.name).size})"
            }
            lines += Markdown.row(workflow?.let(::workflowLink) ?: "no workflow", services)
        }
        block(lines.joinToString("\n"))
    }

    // ---- activities

    private fun activities() {
        block("## ${title(ACTIVITIES)}")
        block("Each activity type that calls another service, and the operations it calls in the order of its code.")
        for (activity in set.activities) {
            block("### ${title(activity.id)}")
            val facts = listOfNotNull(
                activity.interfaces.takeIf { it.isNotEmpty() }?.let { "Interface ${it.joinToString(", ", transform = Markdown::code)}" },
                "Workflows: " + (activity.workflows.joinToString(", ") { workflowLink(it) }.ifEmpty { "none of these sources" }),
            )
            block(facts.joinToString(". ") + ".")
            block(
                activity.calls.joinToString("\n") { call ->
                    "- " + link(call.operation.title, call.operation.id) + (call.request.className?.let { " in ${Markdown.code(it.substringAfterLast('.'))}" } ?: "")
                },
            )
        }
    }

    // ---- services

    private fun service(service: Service) {
        block("## ${title(service.id)}")
        val kind = if (service.kind == ServiceKind.CLIENT) "Client" else "HTTP service"
        block("$kind with ${count(service.operations.size, "operation")}. OpenAPI: ${Markdown.link(service.file, service.file)}.")
        servers(service)?.let(::block)
        block(map(service))
        service.operations.forEach { operation(service, it) }
    }

    private fun servers(service: Service): String? {
        if (service.kind == ServiceKind.CLIENT) {
            return "Called as ${Markdown.code("${service.client ?: "client"}.${service.call ?: "invoke"}(…)")}, one operation per name it is asked for."
        }
        val lines = service.servers.flatMap { server ->
            when {
                server.key != null && server.values.isEmpty() -> listOf("- ${Markdown.code("{${server.key}}")}: no configuration file of the module gives it${variable(server.variable, null)}")
                server.key != null -> server.values.map { value -> "- ${Markdown.code(value.value + (server.variable?.let { "{${it.key}}" } ?: ""))} ${origin(value)}${variable(server.variable, value.profile)}" }
                server.literal != null -> listOf("- ${Markdown.code(server.literal)}, written in the code")
                server.computed != null -> listOf("- ${Markdown.code(server.computed)}, computed in the code")
                else -> emptyList()
            }
        }
        return if (lines.isEmpty()) null else (listOf("Servers:", "") + lines).joinToString("\n")
    }

    private fun origin(value: ConfiguredValue): String =
        "from ${Markdown.code(value.key)}" + (value.file?.let { " in ${Markdown.code(it)}" } ?: ", the default the code gives") +
            (value.profile?.let { ", profile ${Markdown.code(it)}" } ?: "")

    private fun variable(variable: EndpointSet.Variable?, profile: String?): String {
        if (variable == null) return ""
        val value = variable.values.firstOrNull { it.profile == profile } ?: variable.values.firstOrNull { it.profile == null } ?: variable.values.firstOrNull()
        return "; ${Markdown.code("{${variable.key}}")} = " + (value?.let { Markdown.code(it.value) + (it.file?.let { file -> " in ${Markdown.code(file)}" } ?: ", the default the code gives") } ?: "not configured")
    }

    /** The map of a service as a Mermaid flowchart: its callers on the left, its operations on the right. */
    private fun map(service: Service): String {
        val pairs = service.operations.flatMap { operation -> operation.callers.map { it.activity to operation }.distinct() }.distinct()
        val activities = pairs.map { it.first }.distinct().sorted()
        val lines = mutableListOf("```mermaid", "flowchart LR")
        activities.forEachIndexed { index, activity -> lines += "    a$index[\"${label(activity)}\"]:::activity" }
        service.operations.forEachIndexed { index, operation -> lines += "    o$index[\"${label("${operation.badge} ${operation.path}")}\"]:::operation" }
        for ((activity, operation) in pairs) lines += "    a${activities.indexOf(activity)} --> o${service.operations.indexOf(operation)}"
        lines += "    classDef activity fill:#fff,stroke:#2563EB,stroke-width:2px,color:#101820"
        lines += "    classDef operation fill:#F0EDE5,stroke:#D9D4C7,color:#101820"
        lines += "```"
        return lines.joinToString("\n")
    }

    /** Text for a quoted Mermaid label: quotes and the characters Mermaid reads as markup as entities. */
    private fun label(text: String): String =
        text.replace("&", "#amp;").replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;").replace("`", "#96;")

    private fun operation(service: Service, operation: Operation) {
        block("### ${title(operation.id)}")
        val lines = ArrayList<String>()
        if (service.kind == ServiceKind.CLIENT) {
            lines += "Signature: ${Markdown.code(signature(operation))}"
        } else {
            lines += "Path: ${Markdown.code(operation.path)}"
            when {
                operation.method == null -> lines += "The code that sets the method is not seen."
                operation.badge == EndpointSet.HTTP -> lines += "The method is computed in the code as ${Markdown.code(operation.method)}."
            }
        }
        if (operation.constants.isNotEmpty()) lines += "Operation name from ${operation.constants.joinToString(", ", transform = Markdown::code)}."
        block(lines.joinToString("\n\n"))
        if (operation.parameters.isNotEmpty()) {
            block(
                (
                    listOf(Markdown.row("Parameter", "In", "Type", "Value"), "|---|---|---|---|") +
                        operation.parameters.map { parameter ->
                            Markdown.row(
                                Markdown.code(parameter.name),
                                if (parameter.location == ParameterLocation.PATH) "path" else "query",
                                parameter.types.joinToString(" or ") { typeText(TypeName.parse(it)) }.ifEmpty { "not known" },
                                parameter.values.joinToString(", ", transform = Markdown::code),
                            )
                        }
                    ).joinToString("\n"),
            )
        }
        if (service.kind == ServiceKind.CLIENT) {
            arguments(operation)
            payloads("Returns", operation.receives, receiving = true)
        } else {
            payloads("Request", operation.sends, receiving = false)
            payloads("Response", operation.receives, receiving = true)
        }
        callers(operation)
    }

    private fun signature(operation: Operation): String {
        val arguments = operation.arguments.joinToString(", ") { argument -> "${argument.label}: ${argument.types.joinToString(" | ") { TypeName.parse(it).short() }.ifEmpty { "?" }}" }
        val answers = operation.receives.joinToString(" | ") { if (it.kind == PayloadKind.TYPE) TypeName.parse(it.type.orEmpty()).short() else Shapes.describe(it).lowercase() }.ifEmpty { "not seen" }
        return "${operation.path}($arguments) → $answers"
    }

    private fun arguments(operation: Operation) {
        if (operation.arguments.isEmpty()) {
            block("**Arguments:** none besides the operation's name.")
            return
        }
        for (argument in operation.arguments) {
            if (argument.types.isEmpty()) {
                block("**Argument ${Markdown.code(argument.label)}:** type not known.")
                continue
            }
            for (type in argument.types) {
                val name = TypeName.parse(type)
                block("**Argument ${Markdown.code(argument.label)}:** ${typeText(name)}")
                shape(name, Context(null, null))?.let(::block)
            }
        }
    }

    private fun payloads(label: String, payloads: List<Payload>, receiving: Boolean) {
        if (payloads.isEmpty()) {
            block("**$label:** not seen.")
            return
        }
        for (payload in payloads) {
            val facts = Shapes.facts(payload).takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") { Markdown.text(it) }.orEmpty()
            if (payload.kind == PayloadKind.TYPE) {
                val type = TypeName.parse(payload.type.orEmpty())
                block("**$label:** ${typeText(type)}$facts")
                shape(type, Context(payload.format, payload.naming))?.let(::block)
            } else {
                block("**$label:** ${Markdown.text(Shapes.describe(payload).lowercase())}$facts. ${Shapes.explain(payload, receiving)?.let(Markdown::text).orEmpty()}".trimEnd())
            }
        }
    }

    private fun shape(type: TypeName, context: Context): String? =
        when (val shape = shapes.of(type, context)) {
            is Shapes.Shape.Object -> if (shape.fields.isEmpty()) "No properties." else fieldTable(shape.fields.map { Triple(it.name, it.kotlinName, it.type) to it.optional })
            is Shapes.Shape.Enum -> "Values: " + shape.values.joinToString(", ", transform = Markdown::code)
            is Shapes.Shape.Alternatives -> alternatives(shape)
            is Shapes.Shape.NotRead -> "Not read: the analysis did not have the source of ${Markdown.code(shape.model)}, so its fields are not known."
            null -> null
        }

    private fun fieldTable(fields: List<Pair<Triple<String, String, TypeName>, Boolean>>): String =
        (
            listOf(Markdown.row("Field", "Type", "Optional", "Kotlin"), "|---|---|---|---|") +
                fields.map { (field, optional) ->
                    val (name, kotlin, type) = field
                    Markdown.row(Markdown.code(name), typeText(type), if (optional) "optional" else "", if (kotlin != name) Markdown.code(kotlin) else "")
                }
            ).joinToString("\n")

    private fun alternatives(shape: Shapes.Shape.Alternatives): String =
        "One of" + (shape.discriminator?.let { ", told apart by ${Markdown.code(it)}" } ?: ", with no discriminator written") + ":\n\n" +
            shape.subtypes.joinToString("\n") { (type, value) -> "- ${Markdown.code(value)}: ${typeText(TypeName(type))}" }

    private fun callers(operation: Operation) {
        val lines = operation.callers.map { caller ->
            val activity = set.activity(caller.activity)
            val who = activity?.let { link(caller.activity, it.id) } ?: Markdown.code(caller.activity)
            val workflows = if (caller.workflows.isEmpty()) "in no workflow of these sources" else "in " + caller.workflows.joinToString(", ") { workflowLink(it) }
            val type = caller.className?.let { ", ${Markdown.code(it.substringAfterLast('.'))}" }.orEmpty()
            val place = "${caller.loc.file}:${caller.loc.line}"
            val source = sourceLink(caller.loc.file, caller.loc.line)?.let { "[${Markdown.code(place)}]($it)" } ?: Markdown.code(place)
            "- $who $workflows$type, $source"
        }
        block((listOf("Called by:", "") + lines).joinToString("\n"))
    }

    // ---- models

    private fun models() {
        if (set.models.isEmpty()) return
        block("## ${title(MODELS)}")
        block("Every type a request sends or reads its answer as, and the types those reach, with the names the library writes.")
        for (model in set.models) {
            block("### ${title(model.id)}")
            val kind = when (model.type.kind) {
                ModelKind.OBJECT -> "Object"
                ModelKind.ENUM -> "Enum"
                ModelKind.ALTERNATIVES -> "One of several types"
                ModelKind.NOT_READ -> "Not read"
            }
            val generic = model.type.typeParameters.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">").orEmpty()
            block("$kind ${Markdown.code(model.name + generic)}" + (model.type.loc?.let { ", declared at ${Markdown.code("${it.file}:${it.line}")}" } ?: "") + ".")
            when (model.type.kind) {
                ModelKind.OBJECT -> block(
                    if (model.type.properties.isEmpty()) {
                        "No properties."
                    } else {
                        (
                            listOf(Markdown.row("Field", "Type", "Optional", "Kotlin"), "|---|---|---|---|") +
                                model.type.properties.map { property ->
                                    val names = model.contexts.map { WireNames.property(model.type, property, it.format, it.naming) }.distinct()
                                    Markdown.row(
                                        names.joinToString(" / ", transform = Markdown::code),
                                        typeText(TypeName.parse(property.type)),
                                        if (property.optional) "optional" else "",
                                        if (names != listOf(property.name)) Markdown.code(property.name) else "",
                                    )
                                }
                            ).joinToString("\n")
                    },
                )
                ModelKind.ENUM -> block("Values: " + model.type.values.joinToString(", ") { value -> model.contexts.map { WireNames.value(value, it.format) }.distinct().joinToString(" / ", transform = Markdown::code) })
                ModelKind.ALTERNATIVES -> (shapes.of(TypeName(model.name), model.contexts.firstOrNull() ?: Context(null, null)) as? Shapes.Shape.Alternatives)?.let { block(alternatives(it)) }
                ModelKind.NOT_READ -> block("Its source is not in the model, so its fields are not known.")
            }
            val uses = model.sentBy.map { "- sent by ${link(it.title, it.id)}" } + model.returnedBy.map { "- read from ${link(it.title, it.id)}" } +
                (if (model.partOf.isEmpty()) emptyList() else listOf("- part of " + model.partOf.joinToString(", ") { name -> set.model(name)?.let { link(it.simpleName, it.id) } ?: Markdown.code(name) }))
            if (uses.isNotEmpty()) block(uses.joinToString("\n"))
        }
    }

    // ---- pieces

    private fun title(id: String): String = Markdown.text(titles.getValue(id))

    private fun link(text: String, id: String): String = "[${Markdown.code(text)}](#${anchors.getValue(id)})"

    private fun workflowLink(workflow: String): String = workflowFile(workflow)?.let { Markdown.link(workflow, it) } ?: Markdown.code(workflow)

    /** A type with simple names, each model linked to its heading. */
    private fun typeText(type: TypeName): String {
        val models = HashSet<String>()
        fun collect(name: TypeName) {
            if (set.model(name.name) != null) models += name.name
            name.arguments.forEach(::collect)
        }
        collect(type)
        val written = Markdown.code(type.short())
        if (models.isEmpty()) return written
        return written + " (" + models.sorted().joinToString(", ") { name -> set.model(name)!!.let { "[${Markdown.text(it.simpleName)}](#${anchors.getValue(it.id)})" } } + ")"
    }

    private fun count(count: Int, what: String): String = if (count == 1) "1 $what" else "$count ${what}s"

    companion object {
        const val PAGE = "\u0000page"
        const val WHO = "\u0000who"
        const val ACTIVITIES = "\u0000activities"
        const val MODELS = "\u0000models"
    }
}
