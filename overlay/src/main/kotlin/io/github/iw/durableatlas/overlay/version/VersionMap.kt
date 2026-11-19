package io.github.iw.durableatlas.overlay.version

import io.github.iw.durableatlas.overlay.history.WorkerVersion
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Says which commit of the analysed repository each worker version was built from, so that a
 * history can be laid over the workflow documents of the code that ran it.
 *
 * Property order is the key order of the JSON form.
 *
 * @param versions one entry per worker version
 * @param unversioned the commit for histories whose workers reported no version; without it
 *   such histories are laid over nothing
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VersionMap(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    val versions: List<VersionMapEntry>,
    val unversioned: String? = null,
) {
    /**
     * The commit [version] was built from. An entry that names the deployment and the build id
     * goes before one that names the build id alone, which stands for that build id in every
     * deployment.
     */
    fun commitOf(version: WorkerVersion?): CommitChoice {
        if (version == null) return unversioned?.let(CommitChoice::Commit) ?: CommitChoice.NoVersion
        val entry = versions.firstOrNull { it.deployment == version.deployment && it.buildId == version.buildId }
            ?: versions.firstOrNull { it.deployment == null && it.buildId == version.buildId }
        return entry?.let { CommitChoice.Commit(it.commit) } ?: CommitChoice.UnknownVersion(version)
    }

    companion object {
        /** Version of the JSON format read by this module. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * @param deployment the Worker Deployment the version belongs to; absent in an entry for a build
 *   id whatever its deployment, which also stands for workers that report a build id alone
 * @param buildId the build id of the workers
 * @param commit the commit the workers were built from. It is also the name of the directory
 *   that holds the workflow documents of that commit, or the start of that name, as an
 *   abbreviated SHA is; see [CommitDirectories]
 */
@Serializable
data class VersionMapEntry(
    val deployment: String? = null,
    val buildId: String,
    val commit: String,
)

/** What a version map says about one history. */
sealed interface CommitChoice {
    /** The history belongs to this commit. */
    data class Commit(val commit: String) : CommitChoice

    /** The history names no worker version, and the map names no commit for such histories. */
    data object NoVersion : CommitChoice

    /** The map has no entry for the version. */
    data class UnknownVersion(val version: WorkerVersion) : CommitChoice
}
