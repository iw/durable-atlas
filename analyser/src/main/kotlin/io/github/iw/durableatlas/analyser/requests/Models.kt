package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.AnnotationUse
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.ClassShape
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.ShapeProperty
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.WrittenAnnotations
import io.github.iw.durableatlas.analyser.code.findWritten
import io.github.iw.durableatlas.ir.ModelKind
import io.github.iw.durableatlas.ir.ModelProperty
import io.github.iw.durableatlas.ir.ModelSubtype
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.ModelValue
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.StandardTypes
import io.github.iw.durableatlas.ir.TypeName

/**
 * Reads the shapes of the types that requests send and receive, and of every type those reach
 * through their properties, as Jackson and kotlinx.serialization see them (see [ModelType]). A
 * class whose source the model does not have, or that is a class of Java, is known by its name
 * alone. Each class is read once, so that types that refer to each other end.
 */
internal class Models(private val backend: SourceBackend, private val types: StaticTypes) {
    private val subclasses: Map<String, List<String>> by lazy {
        backend.sourceClasses()
            .sortedWith(compareBy({ it.location?.file }, { it.location?.line }))
            .flatMap { declared -> declared.superTypes.map { it to declared.name } }
            .groupBy({ it.first }, { it.second })
    }

    /** The shapes of the types [requests] name, and of those they reach, by name. */
    fun of(requests: Collection<Request>): Map<String, ModelType> {
        val roots = requests.flatMap { request ->
            listOfNotNull(request.sends?.type, request.receives?.type) + request.arguments.mapNotNull { it.type } + request.parameters.mapNotNull { it.type }
        }
        return read(roots)
    }

    /** The shapes of the classes the types [roots] name, and of those they reach. */
    fun read(roots: Collection<String>): Map<String, ModelType> {
        val found = LinkedHashMap<String, ModelType>()
        val pending = ArrayDeque<String>()
        for (root in roots) classesOf(TypeName.parse(root), emptySet(), pending)
        while (pending.isNotEmpty() && found.size < MAX_TYPES) {
            val name = pending.removeFirst()
            if (name in found) continue
            val type = type(name)
            found[name] = type
            val own = type.typeParameters.toSet()
            for (property in type.properties) classesOf(TypeName.parse(property.type), own, pending)
            for (subtype in type.subtypes) pending += subtype.type
        }
        return found
    }

    /** Puts the classes [type] names on [into], leaving out standard types and [parameters]. */
    private fun classesOf(type: TypeName, parameters: Set<String>, into: ArrayDeque<String>) {
        if (!type.isStar && type.name !in parameters && !StandardTypes.isStandard(type.name) && type.name !in StandardTypes.NOTHING && !isParameter(type.name)) {
            into += type.name
        }
        for (argument in type.arguments) classesOf(argument, parameters, into)
    }

    private fun type(name: String): ModelType {
        val shape = backend.shape(name) ?: return ModelType(ModelKind.NOT_READ)
        val annotations = shape.annotations
        val file = shape.location?.file
        val common = ModelType(
            kind = ModelKind.OBJECT,
            typeParameters = shape.typeParameters,
            jsonNaming = annotations.findWritten(JSON_NAMING)?.let { naming(it) },
            serialName = annotations.findWritten(SERIAL_NAME)?.text(VALUE)?.ifEmpty { null },
            jsonName = annotations.findWritten(JSON_TYPE_NAME)?.text(VALUE)?.ifEmpty { null },
            loc = shape.location,
        )
        if (shape.kind == ClassKind.ENUM) {
            return common.copy(
                kind = ModelKind.ENUM,
                values = shape.entries.map { entry ->
                    ModelValue(entry.name, jsonName(entry.annotations), entry.annotations.findWritten(SERIAL_NAME)?.text(VALUE)?.ifEmpty { null })
                },
            )
        }
        val subtypes = subtypes(shape, file)
        if (subtypes != null) {
            return common.copy(
                kind = ModelKind.ALTERNATIVES,
                subtypes = subtypes,
                jsonDiscriminator = annotations.findWritten(JSON_TYPE_INFO)?.let(::discriminator),
                serialDiscriminator = annotations.findWritten(CLASS_DISCRIMINATOR)?.text(VALUE)?.ifEmpty { null },
            )
        }
        val ignored = annotations.findWritten(JSON_IGNORE_PROPERTIES)?.let(::texts).orEmpty().toSet()
        val properties = properties(shape)
            .filter { (it.inConstructor || it.isPublic && it.hasBackingField) && !ignoredProperty(it) && it.name !in ignored }
            .map { property ->
                ModelProperty(
                    name = property.name,
                    type = types.name(property.type, null, file) ?: property.type.text,
                    optional = property.hasDefault,
                    jsonName = jsonName(property.annotations),
                    serialName = property.annotations.findWritten(SERIAL_NAME)?.text(VALUE)?.ifEmpty { null },
                )
            }
        return common.copy(properties = properties)
    }

    /** The properties of [shape], and those of the classes above it that it does not declare again. */
    private fun properties(shape: ClassShape): List<ShapeProperty> {
        val properties = ArrayList(shape.properties)
        var current = shape.name
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val superclass = backend.findClass(current)?.superTypes?.firstOrNull { backend.findClass(it)?.kind == ClassKind.CLASS } ?: break
            val inherited = backend.shape(superclass) ?: break
            for (property in inherited.properties) if (properties.none { it.name == property.name }) properties += property
            current = superclass
        }
        return properties
    }

    /**
     * The types a sealed class or one with `@JsonSubTypes` may be: those `@JsonSubTypes` names,
     * else the classes of the sources that extend the sealed class directly. `null` for any other
     * class.
     */
    private fun subtypes(shape: ClassShape, file: String?): List<ModelSubtype>? {
        val listed = shape.annotations.findWritten(JSON_SUB_TYPES)?.let { listedSubtypes(it, shape, file) }
        val sealed = if (shape.isSealed) subclasses[shape.name].orEmpty() else emptyList()
        if (listed.isNullOrEmpty() && sealed.isEmpty()) return null
        val named = listed.orEmpty().associate { it.first to it.second }
        return (listed?.map { it.first }?.ifEmpty { null } ?: sealed).map { type ->
            val declared = backend.shape(type)?.annotations.orEmpty()
            ModelSubtype(
                type = type,
                jsonName = named[type] ?: declared.findWritten(JSON_TYPE_NAME)?.text(VALUE)?.ifEmpty { null },
                serialName = declared.findWritten(SERIAL_NAME)?.text(VALUE)?.ifEmpty { null },
            )
        }
    }

    /** The classes `@JsonSubTypes` lists, each with the name it gives it. */
    private fun listedSubtypes(annotation: AnnotationUse, shape: ClassShape, file: String?): List<Pair<String, String?>> {
        val found = ArrayList<Pair<String, String?>>()
        fun add(constant: Constant) {
            when (constant) {
                is Constant.Array -> constant.elements.forEach(::add)
                is Constant.Annotation -> {
                    val type = (constant.use.arguments[VALUE] as? Constant.ClassReference)?.name ?: return
                    found += type to constant.use.text(NAME)?.ifEmpty { null }
                }
                is Constant.Unsupported -> for (match in WRITTEN_SUBTYPE.findAll(constant.text)) {
                    val inner = match.groupValues[1]
                    val written = CLASS_LITERAL.find(inner)?.groupValues?.get(1) ?: continue
                    found += resolveWritten(written, shape, file) to WRITTEN_NAME.find(inner)?.groupValues?.get(1)
                }
                else -> Unit
            }
        }
        annotation.arguments.values.forEach(::add)
        return found
    }

    /** The class a name written in the declaration of [shape] stands for: by an import, in its package, or nested in it. */
    private fun resolveWritten(written: String, shape: ClassShape, file: String?): String {
        val qualified = types.qualified(written, file)
        val outer = shape.name.substringBeforeLast('.', "")
        val packageName = generateSequence(outer) { it.substringBeforeLast('.', "").ifEmpty { null } }
            .firstOrNull { it.substringAfterLast('.').firstOrNull()?.isLowerCase() == true }.orEmpty()
        val candidates = listOf(qualified, "${shape.name}.$written", "$outer.$written", "$packageName.$written")
        return candidates.firstOrNull { backend.findClass(it) != null }
            ?: backend.sourceClasses().firstOrNull { it.name.endsWith(".$written") }?.name
            ?: qualified
    }

    private fun jsonName(annotations: List<AnnotationUse>): String? =
        annotations.findWritten(JSON_PROPERTY)?.text(VALUE)?.ifEmpty { null }

    /** Whether Jackson and kotlinx.serialization leave [property] out: `@JsonIgnore` and the `@Transient` annotations. */
    private fun ignoredProperty(property: ShapeProperty): Boolean {
        val ignore = property.annotations.findWritten(JSON_IGNORE)
        if (ignore != null && (ignore.arguments[VALUE] as? Constant.Bool)?.value != false) return true
        return TRANSIENT.any { property.annotations.findWritten(it) != null }
    }

    /** The property `@JsonTypeInfo` writes the type in: its `property`, else the default of its `use`. */
    private fun discriminator(annotation: AnnotationUse): String? {
        annotation.text("property")?.ifEmpty { null }?.let { return it }
        val use = when (val value = annotation.arguments["use"] ?: annotation.arguments[VALUE]) {
            is Constant.EnumEntry -> value.entry
            is Constant.Unsupported -> value.text.substringAfterLast('.')
            else -> null
        }
        return when (use) {
            "NAME", "SIMPLE_NAME" -> "@type"
            "CLASS" -> "@class"
            "MINIMAL_CLASS" -> "@c"
            else -> null
        }
    }

    /** The naming strategy `@JsonNaming` names, by the simple name of its class. */
    private fun naming(annotation: AnnotationUse): String? =
        when (val value = annotation.arguments[VALUE]) {
            is Constant.ClassReference -> value.name.substringAfterLast('.').substringAfterLast('$')
            is Constant.Unsupported -> value.text.removeSuffix("::class").removeSuffix("::class.java").substringAfterLast('.').ifEmpty { null }
            else -> null
        }

    /** The texts an annotation is given in its `value`, written as one or as several. */
    private fun texts(annotation: AnnotationUse): List<String> =
        annotation.arguments.filterKeys { it == VALUE || it.startsWith(VALUE) && it.removePrefix(VALUE).all(Char::isDigit) }.values.flatMap { value ->
            when (value) {
                is Constant.Text -> listOf(value.value)
                is Constant.Array -> value.elements.filterIsInstance<Constant.Text>().map { it.value }
                is Constant.Unsupported -> QUOTED.findAll(value.text).map { it.groupValues[1] }.toList()
                else -> emptyList()
            }
        }

    /** A name that is a type parameter left unbound, as requests write it: one capital and at most one more letter or digit. */
    private fun isParameter(name: String): Boolean = name.length <= 2 && name.first().isUpperCase() && '.' !in name

    companion object {
        /** No more types than this are read for one commit. */
        const val MAX_TYPES = 2000

        private const val VALUE = "value"
        private const val NAME = "name"

        const val JSON_PROPERTY = WrittenAnnotations.JSON_PROPERTY
        const val JSON_IGNORE = WrittenAnnotations.JSON_IGNORE
        const val JSON_IGNORE_PROPERTIES = WrittenAnnotations.JSON_IGNORE_PROPERTIES
        const val JSON_TYPE_INFO = WrittenAnnotations.JSON_TYPE_INFO
        const val JSON_SUB_TYPES = WrittenAnnotations.JSON_SUB_TYPES
        const val JSON_TYPE_NAME = WrittenAnnotations.JSON_TYPE_NAME
        const val JSON_NAMING = WrittenAnnotations.JSON_NAMING
        const val SERIAL_NAME = WrittenAnnotations.SERIAL_NAME
        const val CLASS_DISCRIMINATOR = WrittenAnnotations.CLASS_DISCRIMINATOR
        val TRANSIENT = WrittenAnnotations.TRANSIENT

        private val WRITTEN_SUBTYPE = Regex("Type\\(([^()]*)\\)")
        private val CLASS_LITERAL = Regex("([A-Za-z_][\\w.]*)::class")
        private val WRITTEN_NAME = Regex("name\\s*=\\s*\"([^\"]*)\"")
        private val QUOTED = Regex("\"([^\"]*)\"")
    }
}
