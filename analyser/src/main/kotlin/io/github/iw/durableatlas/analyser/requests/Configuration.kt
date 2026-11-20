package io.github.iw.durableatlas.analyser.requests

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.name

/**
 * The configuration of a service as its files give it, as MicroProfile Config reads it in
 * Quarkus: `application.yaml` or `application.yml` before `application.properties`, the value of
 * a key without a profile before that of the `prod` profile. Values are kept as written: a
 * `${…}` expression in one is not expanded.
 *
 * @param files the files, the one whose values count most first
 */
internal class Configuration private constructor(private val files: List<Map<String, String>>) {
    /** The value configured for [key]; `null` if no file gives one. */
    fun value(key: String): String? =
        files.firstNotNullOfOrNull { it[key] } ?: files.firstNotNullOfOrNull { it["$PROFILE$PRODUCTION.$key"] }

    val isEmpty: Boolean get() = files.all { it.isEmpty() }

    companion object {
        val NONE = Configuration(emptyList())

        /** The profile whose values count where a key has none without a profile. */
        const val PRODUCTION = "prod"
        private const val PROFILE = "%"

        /**
         * Reads [files], which are `application.yaml`, `application.yml` and
         * `application.properties` files, in the order in which their values count. A file that
         * cannot be read gives nothing, and [problems] is told why.
         */
        fun read(files: List<Path>, problems: (String) -> Unit = {}): Configuration {
            val ordered = files.sortedBy { rank(it.name) }
            return Configuration(
                ordered.map { file ->
                    try {
                        val text = Files.readString(file)
                        if (file.name.endsWith(".properties")) properties(text) else yaml(text)
                    } catch (e: Exception) {
                        problems("$file could not be read: ${e.message?.lineSequence()?.firstOrNull() ?: e}")
                        emptyMap()
                    }
                },
            )
        }

        /** A configuration of the texts of files named by [texts]' keys, for tests. */
        fun of(texts: Map<String, String>): Configuration =
            Configuration(
                texts.entries.sortedBy { rank(it.key) }.map { (name, text) -> if (name.endsWith(".properties")) properties(text) else yaml(text) },
            )

        /** YAML before properties, as their ordinals in Quarkus rank them. */
        private fun rank(name: String): Int = when {
            name.endsWith(".yaml") -> 0
            name.endsWith(".yml") -> 1
            else -> 2
        }

        /** The keys of a properties file with their values; a key of a profile starts with `%`. */
        fun properties(text: String): Map<String, String> {
            val properties = Properties()
            properties.load(StringReader(text))
            return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }

        /**
         * The values of a YAML file by their keys as properties write them: the keys of nested
         * maps joined by dots, an item of a list as `key[0]`. Every scalar is text, as written;
         * an empty one, or `~` or `null`, is no value. A map under a key that starts with `%`
         * holds the values of that profile.
         */
        fun yaml(text: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            for (document in Compose(LoadSettings.builder().build()).composeAllFromString(text)) flatten(null, document, values)
            return values
        }

        private fun flatten(prefix: String?, node: Node, into: MutableMap<String, String>) {
            when (node) {
                is MappingNode -> node.value.forEach { tuple ->
                    val name = (tuple.keyNode as? ScalarNode)?.value ?: return@forEach
                    flatten(if (prefix == null) name else "$prefix.$name", tuple.valueNode, into)
                }
                is SequenceNode -> node.value.forEachIndexed { index, item -> flatten("${prefix.orEmpty()}[$index]", item, into) }
                is ScalarNode -> if (prefix != null && !(node.isPlain && node.value in NULLS)) into.putIfAbsent(prefix, node.value)
                else -> Unit
            }
        }

        /** How a plain YAML scalar says that there is no value. */
        private val NULLS = setOf("", "~", "null", "Null", "NULL")
    }
}
