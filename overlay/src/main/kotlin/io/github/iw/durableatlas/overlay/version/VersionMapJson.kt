package io.github.iw.durableatlas.overlay.version

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Thrown when text is not a version map this version of the tool can read. */
class VersionMapFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads version maps from JSON:
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "versions": [
 *     { "deployment": "orders", "buildId": "2026.11.3", "commit": "4f2a9c1" },
 *     { "buildId": "2026.11.4", "commit": "9b7d3e0" }
 *   ],
 *   "unversioned": "4f2a9c1"
 * }
 * ```
 *
 * Reading is strict: keys the format does not have are refused, and so are two entries for one
 * version, and commits that cannot be the name of a directory. A commit may be abbreviated; which
 * directory it then stands for is settled when the map is used, see [CommitDirectories].
 */
object VersionMapJson {
    /** Classpath location of the JSON Schema (draft 2020-12) of the format. */
    const val SCHEMA_RESOURCE: String = "io/github/iw/durableatlas/overlay/version-map.schema.json"

    private val format = Json { ignoreUnknownKeys = false }

    /** What a commit may be written as: it names a directory, or begins the name of one. */
    private val COMMIT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun schema(): String {
        val stream = checkNotNull(VersionMapJson::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)) {
            "Missing classpath resource $SCHEMA_RESOURCE"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** @throws VersionMapFormatException if the file cannot be read or does not hold a version map */
    fun read(file: Path): VersionMap {
        val text = try {
            Files.readAllBytes(file).toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw VersionMapFormatException("${file.name} cannot be read: $e", e)
        }
        return decode(text, file.name)
    }

    /**
     * @param source what to call the map in messages
     * @throws VersionMapFormatException if [text] is not a version map
     */
    fun decode(text: String, source: String): VersionMap {
        fun refuse(problem: String, cause: Throwable? = null): Nothing =
            throw VersionMapFormatException("$source is not a version map: $problem", cause)

        val map = try {
            val root = format.parseToJsonElement(text)
            if (root !is JsonObject) refuse("it is not a JSON object")
            if ("schemaVersion" !in root) refuse("it has no schemaVersion")
            format.decodeFromJsonElement(VersionMap.serializer(), root)
        } catch (e: SerializationException) {
            refuse(e.message?.lineSequence()?.first() ?: "it cannot be parsed", e)
        }

        if (map.schemaVersion != VersionMap.SCHEMA_VERSION) {
            refuse("unsupported schemaVersion ${map.schemaVersion} (supported: ${VersionMap.SCHEMA_VERSION})")
        }
        val seen = HashSet<Pair<String?, String>>()
        for (entry in map.versions) {
            val named = listOfNotNull(entry.deployment, entry.buildId).joinToString(":")
            if (entry.buildId.isBlank()) refuse("an entry has an empty buildId")
            if (entry.deployment != null && entry.deployment.isBlank()) refuse("the entry for build id ${entry.buildId} has an empty deployment")
            if (!COMMIT.matches(entry.commit)) refuse("the commit of $named, '${entry.commit}', cannot be the name of a directory")
            if (!seen.add(entry.deployment to entry.buildId)) refuse("$named is listed twice")
        }
        map.unversioned?.let { if (!COMMIT.matches(it)) refuse("the commit for histories without a version, '$it', cannot be the name of a directory") }
        return map
    }
}
