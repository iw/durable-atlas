package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.ConfiguredValue
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.ParameterLocation
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind
import io.github.iw.durableatlas.ir.ServiceNames
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.StandardTypes
import io.github.iw.durableatlas.ir.TypeName

/**
 * Every service operation the activities of a commit call, worked out from its documents alone:
 * the requests of each activity type, the services they go to, the operations they ask for, who
 * calls each, and the models they send and receive. The endpoints page, its Markdown form and the
 * OpenAPI documents all say what this holds, and the diagrams link into it.
 */
internal class EndpointSet private constructor(
    val commits: List<String>,
    val services: List<Service>,
    val activities: List<Activity>,
    /** Every workflow type that runs one of [activities], in order. */
    val workflows: List<String>,
    /** The types table, by qualified name. */
    val types: Map<String, ModelType>,
    /** The entries of [types] that the requests reach, in order of their names. */
    val models: List<Model>,
) {
    enum class ServiceKind { HTTP, CLIENT }

    /**
     * @param client for a client, its class as the requests name it and the function they call
     * @param file the name of its OpenAPI document, inside the directory of the page
     */
    class Service(
        val name: String,
        val kind: ServiceKind,
        val servers: List<Server>,
        val client: String?,
        val call: String?,
        val operations: List<Operation>,
        val id: String,
        val file: String,
    )

    /**
     * Where an HTTP service is reached: the configuration key a target starts with and every
     * value configured for it, by profile, with the second key that follows it as a [variable];
     * or the fixed start of a target ([literal]), or the computed value it starts with ([computed]).
     */
    class Server(
        val key: String?,
        val values: List<ConfiguredValue>,
        val literal: String?,
        val computed: String?,
        val variable: Variable?,
    )

    /** A configuration key that follows the one a target starts with, and its values. */
    class Variable(val key: String, val values: List<ConfiguredValue>)

    /**
     * One thing a service is asked for: an HTTP method and path, or a client's operation.
     *
     * @param method the HTTP method as the requests write it, `null` where none says it
     * @param path for HTTP, the path; for a client, the operation as the requests write it
     * @param sends what the requests send, each different one once; for a client, nothing
     * @param receives what the requests make of the answer, each different one once
     */
    class Operation(
        val service: String,
        val kind: ServiceKind,
        val method: String?,
        val path: String,
        val constants: List<String>,
        val parameters: List<Parameter>,
        val arguments: List<Argument>,
        val sends: List<Payload>,
        val receives: List<Payload>,
        val callers: List<Caller>,
        val id: String,
    ) {
        /** The badge of the operation: its method, `HTTP` where it is not known, or `FN` for a client. */
        val badge: String
            get() = when {
                kind == ServiceKind.CLIENT -> FN
                method != null && method in METHODS -> method
                else -> HTTP
            }

        /** Its short name: `POST billing /v1/charges`, `FN pricing-quote`. */
        val title: String
            get() = if (kind == ServiceKind.CLIENT) "$FN $path" else "$badge $service $path"
    }

    /** A parameter of an operation, with every type and value the requests give it. */
    class Parameter(val name: String, val location: ParameterLocation, val types: List<String>, val values: List<String>)

    /** An argument of a client's operation, by its name or else its place, with every type the requests give it. */
    class Argument(val name: String?, val position: Int, val types: List<String>) {
        val label: String get() = name ?: "argument $position"
    }

    /** Who makes a request: the activity type, its interface, its class where several implement it, its workflows, and where. */
    class Caller(val activity: String, val interfaceName: String?, val className: String?, val workflows: List<String>, val loc: SourceLocation)

    /** An activity type that makes requests, the workflows that run it, and what it calls, in the order of its requests. */
    class Activity(val name: String, val interfaces: List<String>, val workflows: List<String>, val calls: List<Call>, val id: String)

    /** A request and the operation it asks for. */
    class Call(val request: Request, val operation: Operation)

    /**
     * An entry of the types table that a request reaches.
     *
     * @param contexts the libraries and naming strategies the model is written or read with,
     *   each once; a model reached from a client's call has a context with neither
     * @param sentBy the operations whose requests send it, directly or as the item of a collection
     * @param returnedBy the operations whose answers are read as it
     * @param partOf the models one of whose properties or alternatives is of it
     */
    class Model(
        val name: String,
        val type: ModelType,
        val id: String,
        val contexts: List<Context>,
        val sentBy: List<Operation>,
        val returnedBy: List<Operation>,
        val partOf: List<String>,
    ) {
        val simpleName: String get() = name.substringAfterLast('.')
    }

    /** How a model is written: the library, where known, and the naming strategy Quarkus gives Jackson. */
    data class Context(val format: PayloadFormat?, val naming: String?)

    /** The operation a request asks for. */
    fun operationOf(request: Request): Operation? = byRequest[request]

    /** The activity of a type, by name. */
    fun activity(name: String): Activity? = byName[name]

    fun model(name: String): Model? = byModel[name]

    private val byRequest = HashMap<Request, Operation>()
    private val byName = activities.associateBy { it.name }
    private val byModel = models.associateBy { it.name }

    val operations: List<Operation> get() = services.flatMap { it.operations }

    companion object {
        const val FN = "FN"
        const val HTTP = "HTTP"

        /** The HTTP methods an OpenAPI document has a place for, in the order a service lists them. */
        val METHODS = listOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")

        /** The file name an OpenAPI document of a service may not have: that of the page. */
        private const val RESERVED = "index"

        /**
         * What [documents] and [inventory] say of the requests of the activities, or `null` where
         * there is none. Requests are taken from the inventory where there is one, since it has
         * those of activity types no workflow runs, and from the workflow documents otherwise.
         */
        fun of(documents: List<IrDocument>, inventory: InventoryPart?): EndpointSet? {
            // Each activity type: its interfaces, the workflows that run it, its requests in order.
            val interfaces = sortedMapOf<String, MutableSet<String>>()
            val workflows = sortedMapOf<String, MutableSet<String>>()
            val requests = LinkedHashMap<String, LinkedHashSet<Request>>()
            for (document in documents) {
                for (node in document.nodes) {
                    if (node.kind != NodeKind.ACTIVITY && node.kind != NodeKind.LOCAL_ACTIVITY) continue
                    val name = node.name ?: continue
                    workflows.getOrPut(name) { sortedSetOf() } += document.workflowType
                    if (node.requests.isNotEmpty()) requests.getOrPut(name) { LinkedHashSet() } += node.requests
                }
            }
            for (activity in inventory?.activities.orEmpty()) {
                interfaces.getOrPut(activity.name) { sortedSetOf() } += activity.interfaceName
                if (activity.requests.isNotEmpty()) requests.getOrPut(activity.name) { LinkedHashSet() } += activity.requests
            }
            if (requests.isEmpty()) return null
            val all = requests.values.flatten()
            val named = ServiceNames.of(all)
            fun serviceOf(request: Request): String = request.service ?: named.getValue(request)

            val anchors = Anchors()
            val builders = LinkedHashMap<String, OperationBuilder>()
            val calls = LinkedHashMap<String, MutableList<Pair<Request, String>>>()
            for ((activity, made) in requests.toSortedMap()) {
                for (request in made) {
                    val service = serviceOf(request)
                    val (key, builder) = when (request.kind) {
                        RequestKind.HTTP -> {
                            val split = Targets.split(request)
                            val method = request.method
                            "http\u0000$service\u0000${method.orEmpty()}\u0000${split.path}" to { OperationBuilder(service, ServiceKind.HTTP, method, split.path) }
                        }
                        RequestKind.CLIENT -> {
                            val operation = request.operation ?: request.call ?: "?"
                            "client\u0000$service\u0000$operation" to { OperationBuilder(service, ServiceKind.CLIENT, null, operation) }
                        }
                    }
                    builders.getOrPut(key, builder).add(
                        request,
                        Caller(
                            activity = activity,
                            interfaceName = interfaces[activity]?.singleOrNull(),
                            className = request.className,
                            workflows = workflows[activity]?.toList().orEmpty(),
                            loc = request.loc,
                        ),
                    )
                    calls.getOrPut(activity) { ArrayList() } += request to key
                }
            }

            // Services: HTTP services by name, then clients by name.
            val byService = builders.values.groupBy { it.service }
            val serviceOrder = byService.keys.sortedWith(
                compareBy<String>({ byService.getValue(it).first().kind == ServiceKind.CLIENT }, { it.lowercase() }, { it }),
            )
            val files = HashSet<String>()
            val built = HashMap<OperationBuilder, Operation>()
            val services = serviceOrder.map { name ->
                val group = byService.getValue(name).sortedWith(OperationBuilder.ORDER)
                val kind = group.first().kind
                val id = anchors.take("service-$name")
                val operations = group.map { builder ->
                    builder.build(anchors.take(if (kind == ServiceKind.CLIENT) "op-$name-${builder.path}" else "op-$name-${builder.method ?: HTTP}-${builder.path}"))
                        .also { built[builder] = it }
                }
                val firstRequest = group.first().requests.first()
                Service(
                    name = name,
                    kind = kind,
                    servers = if (kind == ServiceKind.HTTP) servers(group.flatMap { it.requests }) else emptyList(),
                    client = firstRequest.client.takeIf { kind == ServiceKind.CLIENT },
                    call = firstRequest.call.takeIf { kind == ServiceKind.CLIENT },
                    operations = operations,
                    id = id,
                    file = fileName(name, files),
                )
            }
            val activities = calls.keys.sorted().map { name ->
                Activity(
                    name = name,
                    interfaces = interfaces[name]?.toList().orEmpty(),
                    workflows = workflows[name]?.toList().orEmpty(),
                    calls = calls.getValue(name).map { (request, key) -> Call(request, built.getValue(builders.getValue(key))) },
                    id = anchors.take("activity-$name"),
                )
            }
            val types = inventory?.types.orEmpty().toSortedMap()
            val models = Models(types, services.flatMap { it.operations }).read(anchors)
            val commits = (documents.map { it.commit } + listOfNotNull(inventory?.commit)).distinct().sorted()
            return EndpointSet(commits, services, activities, activities.flatMap { it.workflows }.distinct().sorted(), types, models).also { set ->
                for (activity in activities) for (call in activity.calls) set.byRequest[call.request] = call.operation
            }
        }

        /**
         * The servers of a service, as its requests reach it: one per key the targets start with,
         * fixed start or computed start, in the order first met.
         */
        private fun servers(requests: List<Request>): List<Server> {
            val servers = LinkedHashMap<String, Server>()
            for (request in requests) {
                val split = Targets.split(request)
                val key = split.keys.firstOrNull()
                val identity = key?.let { "key:$it" } ?: split.literal?.let { "literal:$it" } ?: split.computed?.let { "computed:$it" } ?: continue
                val existing = servers[identity]
                val variableKey = split.keys.getOrNull(1)
                val variable = variableKey?.let { second -> Variable(second, request.hosts.filter { it.key == second }) }
                val values = key?.let { first -> request.hosts.filter { it.key == first } }.orEmpty()
                servers[identity] = when {
                    existing == null -> Server(key, values, split.literal, split.computed, variable)
                    else -> Server(
                        key,
                        (existing.values + values).distinct(),
                        existing.literal,
                        existing.computed,
                        existing.variable ?: variable,
                    )
                }
            }
            return servers.values.toList()
        }

        /** The file name of a service's OpenAPI document: its name with only safe characters, distinct regardless of case. */
        private fun fileName(service: String, taken: MutableSet<String>): String {
            val base = service.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }.joinToString("").trim('.').ifEmpty { "service" }
            var name = if (base.equals(RESERVED, ignoreCase = true)) "${base}_service" else base
            var n = 2
            while (!taken.add(name.lowercase())) name = "$base-${n++}"
            return "$name$OPENAPI_SUFFIX"
        }

        const val OPENAPI_SUFFIX = ".openapi.json"
    }

    /** The requests that ask for one operation, gathered before it is built. */
    private class OperationBuilder(val service: String, val kind: ServiceKind, val method: String?, val path: String) {
        val requests = ArrayList<Request>()
        val callers = ArrayList<Caller>()

        fun add(request: Request, caller: Caller) {
            requests += request
            callers += caller
        }

        fun build(id: String): Operation {
            val parameters = LinkedHashMap<Pair<String, ParameterLocation>, Pair<LinkedHashSet<String>, LinkedHashSet<String>>>()
            // Every part of the path is a parameter, also where no request says what gives it: for a
            // client, a part of the operation that the code computes.
            for (slot in Targets.slots(path)) parameters.getOrPut(slot to ParameterLocation.PATH) { LinkedHashSet<String>() to LinkedHashSet() }
            for (request in requests) {
                for (parameter in request.parameters) {
                    val (types, values) = parameters.getOrPut(parameter.name to parameter.location) { LinkedHashSet<String>() to LinkedHashSet() }
                    parameter.type?.let(types::add)
                    parameter.value?.let(values::add)
                }
            }
            val arguments = LinkedHashMap<String, Triple<String?, Int, LinkedHashSet<String>>>()
            for (request in requests) {
                for (argument in request.arguments) {
                    val key = argument.name ?: "#${argument.position}"
                    val entry = arguments.getOrPut(key) { Triple(argument.name, argument.position, LinkedHashSet()) }
                    argument.type?.let(entry.third::add)
                }
            }
            return Operation(
                service = service,
                kind = kind,
                method = method,
                path = path,
                constants = requests.mapNotNull { it.constant }.distinct().sorted(),
                parameters = parameters.map { (key, found) -> Parameter(key.first, key.second, found.first.toList(), found.second.toList()) }
                    .sortedBy { it.location.ordinal },
                arguments = arguments.values.sortedBy { it.second }.map { Argument(it.first, it.second, it.third.toList()) },
                sends = requests.mapNotNull { it.sends }.distinct(),
                receives = requests.mapNotNull { it.receives }.distinct(),
                callers = callers.distinctBy { listOf(it.activity, it.className, it.loc.file, it.loc.line.toString()) }
                    .sortedWith(compareBy({ it.activity }, { it.className.orEmpty() }, { it.loc.file }, { it.loc.line })),
                id = id,
            )
        }

        companion object {
            val ORDER: Comparator<OperationBuilder> = compareBy<OperationBuilder>(
                { it.path },
                { builder -> builder.method?.let { METHODS.indexOf(it) }?.takeIf { it >= 0 } ?: METHODS.size },
                { it.method.orEmpty() },
            )
        }
    }

    /**
     * The models the operations reach, read from the types table: the types each request sends or
     * receives and each type of a client's arguments and of a parameter, then every type their
     * properties and alternatives name, each once however often it is reached.
     */
    private class Models(private val types: Map<String, ModelType>, private val operations: List<Operation>) {
        fun read(anchors: Anchors): List<Model> {
            val contexts = HashMap<String, LinkedHashSet<Context>>()
            val sentBy = HashMap<String, LinkedHashSet<Operation>>()
            val returnedBy = HashMap<String, LinkedHashSet<Operation>>()
            val partOf = HashMap<String, java.util.TreeSet<String>>()
            val pending = ArrayDeque<Pair<String, Context>>()

            fun reach(type: String, context: Context) {
                for (name in classes(TypeName.parse(type))) pending += name to context
            }

            for (operation in operations) {
                for (payload in operation.sends) payload.type?.let { type ->
                    classes(TypeName.parse(type)).forEach { sentBy.getOrPut(it) { LinkedHashSet() } += operation }
                    reach(type, Context(payload.format, payload.naming))
                }
                for (argument in operation.arguments) for (type in argument.types) {
                    classes(TypeName.parse(type)).forEach { sentBy.getOrPut(it) { LinkedHashSet() } += operation }
                    reach(type, Context(null, null))
                }
                for (payload in operation.receives) payload.type?.let { type ->
                    classes(TypeName.parse(type)).forEach { returnedBy.getOrPut(it) { LinkedHashSet() } += operation }
                    reach(type, Context(payload.format, payload.naming))
                }
                for (parameter in operation.parameters) for (type in parameter.types) reach(type, Context(null, null))
            }
            while (pending.isNotEmpty()) {
                val (name, context) = pending.removeFirst()
                if (!contexts.getOrPut(name) { LinkedHashSet() }.add(context)) continue
                val type = types[name] ?: continue
                val inner = type.properties.map { it.type } + type.subtypes.map { it.type }
                for (used in inner) {
                    for (reached in classes(TypeName.parse(used), type.typeParameters.toSet())) {
                        if (reached != name) partOf.getOrPut(reached) { java.util.TreeSet() } += name
                        pending += reached to context
                    }
                }
            }
            return contexts.keys.filter { it in types }.sorted().map { name ->
                Model(
                    name = name,
                    type = types.getValue(name),
                    id = anchors.take("model-$name"),
                    contexts = contexts.getValue(name).sortedWith(compareBy({ it.format?.ordinal ?: -1 }, { it.naming.orEmpty() })),
                    sentBy = sentBy[name]?.toList().orEmpty(),
                    returnedBy = returnedBy[name]?.toList().orEmpty(),
                    partOf = partOf[name]?.toList().orEmpty(),
                )
            }
        }

        /** The classes [type] names that are not standard types nor type parameters: itself and its type arguments. */
        private fun classes(type: TypeName, parameters: Set<String> = emptySet()): List<String> {
            val found = ArrayList<String>()
            fun visit(name: TypeName) {
                if (name.isStar) return
                if (name.name !in parameters && !StandardTypes.isStandard(name.name) && name.name !in StandardTypes.NOTHING) found += name.name
                name.arguments.forEach(::visit)
            }
            visit(type)
            return found.distinct()
        }
    }
}

/**
 * The ids of the places on the page, each unique however the names they come from are written: a
 * name in lower case with each run of other characters than letters and digits as one hyphen, and
 * a number after it where an earlier place has that id.
 */
internal class Anchors {
    private val taken = HashSet<String>()

    fun take(name: String): String {
        val base = slug(name)
        var id = base
        var n = 2
        while (!taken.add(id)) id = "$base-${n++}"
        return id
    }

    companion object {
        fun slug(name: String): String {
            val out = StringBuilder()
            var gap = false
            for (char in name.lowercase()) {
                if (char in 'a'..'z' || char in '0'..'9') {
                    if (gap && out.isNotEmpty()) out.append('-')
                    out.append(char)
                    gap = false
                } else {
                    gap = true
                }
            }
            return out.toString().ifEmpty { "x" }
        }
    }
}
