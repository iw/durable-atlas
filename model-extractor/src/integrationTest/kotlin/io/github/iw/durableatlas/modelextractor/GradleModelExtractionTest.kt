package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.modelextractor.testing.TreeSnapshot
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extraction through the sample repository's own Gradle build. The model is extracted once, before
 * the tests, and they examine that run; tests that need another commit make a repository of their own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GradleModelExtractionTest {
    private lateinit var dir: Path
    private lateinit var repository: SampleRepository
    private lateinit var commit: String
    private lateinit var repositoryBefore: Map<String, String>
    private lateinit var repositoryAfter: Map<String, String>
    private lateinit var result: ModelResult
    private lateinit var model: ExtractedModel
    private val log = mutableListOf<String>()

    private val out: Path get() = dir.resolve("out")

    @BeforeAll
    fun extract(@TempDir directory: Path) {
        dir = directory
        repository = SampleRepository.create(dir.resolve("repo"))
        commit = repository.head
        repositoryBefore = TreeSnapshot.metadata(repository.root)
        result = ModelExtractor(log::add).extract(
            request(repository, commit, out, ModelMode.GRADLE, scratch = "scratch", keepScratch = true),
        )
        repositoryAfter = TreeSnapshot.metadata(repository.root)
        model = ExtractedModel.read(result.directory)
    }

    private fun manifest(path: String): ModuleManifest = model.manifests.single { it.path == path }

    private fun assertMatchesSchema(resource: String, file: Path) {
        JsonSchemaChecker.forResource(resource).assertValid(Files.readString(file))
    }

    /** A request with a scratch location of its own, so a test can see what it leaves behind. */
    private fun request(
        from: SampleRepository,
        commit: String,
        out: Path,
        mode: ModelMode?,
        scratch: String,
        force: Boolean = false,
        keepScratch: Boolean = false,
    ) = ModelRequest(from.project, commit, out, mode, force, keepScratch, dir.resolve(scratch))

    @Test
    fun `modules that use Temporal and their dependencies are kept, the rest is left out`() {
        assertEquals(commit, result.commit)
        assertEquals(ModelMode.GRADLE, result.mode)
        assertEquals(out.resolve(commit), result.directory)
        assertFalse(result.cached)
        assertFalse(result.fallback)
        assertEquals(
            ModelIndex(
                commit = commit,
                mode = ModelMode.GRADLE,
                modules = listOf(
                    IndexedModule(":activities", "modules/_activities.json"),
                    IndexedModule(":helpers", "modules/_helpers.json"),
                    IndexedModule(":nexus-api", "modules/_nexus-api.json"),
                    IndexedModule(":workflows", "modules/_workflows.json"),
                ),
                dropped = listOf(DroppedModule(":unrelated", DropReasons.NO_TEMPORAL_USAGE)),
            ),
            result.index,
        )
        assertEquals(result.index, model.index)
        assertEquals(
            listOf("_activities.json", "_helpers.json", "_nexus-api.json", "_workflows.json"),
            result.directory.resolve("modules").listDirectoryEntries().map { it.name }.sorted(),
        )
    }

    @Test
    fun `every manifest records the commit, the mode and the module's Kotlin source directory`() {
        for (manifest in model.manifests) {
            assertEquals(commit, manifest.commit, manifest.path)
            assertEquals(ModelMode.GRADLE, manifest.mode, manifest.path)
            assertEquals(ModuleManifest.SCHEMA_VERSION, manifest.schemaVersion, manifest.path)
            // Only the Kotlin directory: no resources, no test sources, nothing that does not exist.
            val expected = listOf("${manifest.path.removePrefix(":")}/src/main/kotlin")
            assertEquals(expected, manifest.sources, manifest.path)
        }
    }

    @Test
    fun `sibling modules are recorded as project dependencies`() {
        assertEquals(emptyList(), manifest(":activities").projectDeps)
        assertEquals(listOf(":activities"), manifest(":helpers").projectDeps)
        assertEquals(emptyList(), manifest(":nexus-api").projectDeps)
        assertEquals(listOf(":activities", ":helpers", ":nexus-api"), manifest(":workflows").projectDeps)
    }

    @Test
    fun `external jars come from dependency resolution and never from a sibling module`() {
        for (manifest in model.manifests) {
            val jars = model.jars(manifest)
            assertEquals(manifest.jars, jars.map { it.toString() }, "${manifest.path} lists absolute paths")
            assertEquals(jars.distinct(), jars, manifest.path)
            for (jar in jars) {
                assertTrue(Files.isRegularFile(jar), "$jar should exist")
                assertEquals("jar", jar.extension, jar.toString())
                assertFalse(jar.startsWith(repository.root), "$jar is inside the repository")
                assertFalse(jar.startsWith(assertNotNull(result.scratch)), "$jar is inside the scratch directory")
            }
            val names = jars.map { it.name }
            assertTrue(names.any { it.startsWith("kotlin-stdlib-") }, "${manifest.path}: $names")
            for (module in listOf("activities", "helpers", "nexus-api", "workflows")) {
                assertTrue(names.none { it.startsWith(module) }, "${manifest.path}: $names")
            }
        }

        fun names(path: String) = manifest(path).jars.map { Path.of(it).name }
        for (path in listOf(":activities", ":helpers", ":workflows")) {
            assertTrue(names(path).any { it.startsWith("temporal-sdk-") }, path)
            // The SDK's own API dependencies are visible too.
            assertTrue(names(path).any { it.startsWith("temporal-serviceclient-") }, path)
        }
        assertTrue(names(":workflows").any { it.startsWith("nexus-sdk-") })
        assertTrue(names(":nexus-api").any { it.startsWith("nexus-sdk-") })
        assertTrue(names(":nexus-api").none { it.startsWith("temporal-") })
    }

    @Test
    fun `the model holds a copy of the kept modules' sources and of nothing else`() {
        for (manifest in model.manifests) {
            val copy = model.sourceDirectories(manifest).single()
            val original = repository.project.resolve(manifest.sources.single())
            assertEquals(TreeSnapshot.contents(original), TreeSnapshot.contents(copy), manifest.path)
        }
        assertEquals(
            listOf("activities", "helpers", "nexus-api", "workflows"),
            model.sourceRoot.listDirectoryEntries().map { it.name }.sorted(),
        )
        assertEquals(listOf("src"), model.sourceRoot.resolve("workflows").listDirectoryEntries().map { it.name })
        assertEquals(listOf("main"), model.sourceRoot.resolve("workflows/src").listDirectoryEntries().map { it.name })
        assertEquals(
            listOf("index.json", "modules", "source"),
            result.directory.listDirectoryEntries().map { it.name }.sorted(),
        )
    }

    @Test
    fun `every written file matches its schema`() {
        assertMatchesSchema(ModelSchemas.INDEX_RESOURCE, result.directory.resolve("index.json"))
        val files = result.directory.resolve("modules").listDirectoryEntries()
        assertEquals(4, files.size)
        for (file in files) assertMatchesSchema(ModelSchemas.MANIFEST_RESOURCE, file)
    }

    @Test
    fun `Gradle ran the model task of each module and compiled nothing`() {
        assertEquals(
            listOf(":activities", ":helpers", ":nexus-api", ":unrelated", ":workflows").map { "$it:dumpAnalysisModel" },
            assertNotNull(result.gradleTasks).sorted(),
        )
        assertTrue(log.none { it.startsWith("warning:") }, log.toString())

        // The scratch checkout was kept: compiling would have left a build directory, classes or jars in it.
        val checkout = assertNotNull(result.scratch).resolve("checkout")
        assertTrue(checkout.resolve("workflows/src/main/kotlin").isDirectory())
        val leftovers = Files.walk(checkout).use { paths ->
            paths.filter { it.name == "build" || it.extension == "class" || it.extension == "jar" }
                .map { checkout.relativize(it).toString() }
                .filter { it != "gradle/wrapper/gradle-wrapper.jar" }
                .toList()
        }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `the repository is left exactly as it was`() {
        assertEquals(repositoryBefore, repositoryAfter)
        assertTrue(repositoryBefore.keys.any { it.startsWith(".git/") }, "the snapshot covers the git directory")
        assertEquals("", repository.status())
    }

    @Test
    fun `extracting the same commit again gives identical output and removes its scratch directory`() {
        val first = TreeSnapshot.contents(result.directory)
        val scratchParent = dir.resolve("scratch-again")

        val again = ModelExtractor().extract(
            request(repository, commit, out, ModelMode.GRADLE, scratch = "scratch-again", force = true),
        )

        assertFalse(again.cached)
        assertNotNull(again.gradleTasks)
        assertEquals(first, TreeSnapshot.contents(again.directory))
        assertNull(again.scratch)
        assertEquals(emptyList(), scratchParent.listDirectoryEntries())

        val elsewhere = ModelExtractor().extract(
            request(repository, commit, dir.resolve("out-elsewhere"), mode = null, scratch = "scratch-again"),
        )
        assertEquals(first, TreeSnapshot.contents(elsewhere.directory))
    }

    @Test
    fun `an existing model is reused without running Gradle`() {
        val before = TreeSnapshot.metadata(result.directory)
        val messages = mutableListOf<String>()
        val scratchParent = dir.resolve("scratch-unused")

        for (mode in listOf(ModelMode.GRADLE, null)) {
            val cached = ModelExtractor(messages::add).extract(
                request(repository, commit, out, mode, scratch = "scratch-unused"),
            )

            assertTrue(cached.cached)
            assertEquals(ModelMode.GRADLE, cached.mode)
            assertEquals(result.index, cached.index)
            assertNull(cached.gradleTasks)
        }
        assertEquals(before, TreeSnapshot.metadata(result.directory))
        assertEquals(2, messages.size, messages.toString())
        assertTrue(messages.all { "nothing to extract" in it }, messages.toString())
        assertFalse(scratchParent.exists(), "no scratch directory is created for a cache hit")
    }

    @Test
    fun `a jar checked into the repository is copied into the model`() {
        val other = SampleRepository.create(dir.resolve("repo-with-jar"))
        val withJar = other.commit("Depend on a checked-in jar") { project ->
            Files.createDirectories(project.resolve("helpers/libs"))
            JarOutputStream(Files.newOutputStream(project.resolve("helpers/libs/local.jar"))).close()
            val build = project.resolve("helpers/build.gradle.kts")
            val dependency = "\ndependencies {\n    implementation(files(\"libs/local.jar\"))\n}\n"
            Files.writeString(build, Files.readString(build) + dependency)
        }
        val before = TreeSnapshot.metadata(other.root)

        val extracted = ExtractedModel.read(
            ModelExtractor().extract(request(other, withJar, out, ModelMode.GRADLE, scratch = "scratch-jar")).directory,
        )

        assertEquals(before, TreeSnapshot.metadata(other.root))
        val helpers = extracted.manifests.single { it.path == ":helpers" }
        assertContains(helpers.jars, "helpers/libs/local.jar")
        val copy = extracted.jars(helpers).single { it.name == "local.jar" }
        assertEquals(extracted.sourceRoot.resolve("helpers/libs/local.jar"), copy)
        assertTrue(Files.isRegularFile(copy))
        assertTrue(extracted.manifests.single { it.path == ":workflows" }.jars.none { it.endsWith("local.jar") })
        assertMatchesSchema(ModelSchemas.MANIFEST_RESOURCE, extracted.directory.resolve("modules/_helpers.json"))
        assertTrue(out.resolve(commit).isDirectory(), "models of other commits stay where they are")
    }

    @Test
    fun `a project in a subdirectory of the git repository is built and described from there`() {
        val nested = SampleRepository.create(dir.resolve("repo-nested"), subdirectory = "services/sample")
        val before = TreeSnapshot.metadata(nested.root)

        val extracted = ExtractedModel.read(
            ModelExtractor().extract(
                request(nested, nested.head, dir.resolve("out-nested"), ModelMode.GRADLE, scratch = "scratch-nested"),
            ).directory,
        )

        assertEquals(before, TreeSnapshot.metadata(nested.root))
        assertEquals("", nested.status())
        assertEquals(listOf(":activities", ":helpers", ":nexus-api", ":workflows"), extracted.manifests.map { it.path })
        assertEquals(listOf("workflows/src/main/kotlin"), extracted.manifests.last().sources)
        assertTrue(extracted.sourceRoot.resolve("workflows/src/main/kotlin/com/example/fixture").isDirectory())
    }

    @Test
    fun `a build that cannot be configured fails in Gradle mode and falls back to a degraded model otherwise`() {
        val other = SampleRepository.create(dir.resolve("repo-broken"))
        val broken = other.commit("Break the build") { project ->
            val settings = project.resolve("settings.gradle.kts")
            Files.writeString(settings, Files.readString(settings) + "\nthis is not a settings script\n")
        }
        val before = TreeSnapshot.metadata(other.root)
        val brokenOut = dir.resolve("out-broken")
        val scratchParent = dir.resolve("scratch-broken")
        fun request(mode: ModelMode?) = request(other, broken, brokenOut, mode, scratch = "scratch-broken")

        val failure = assertFailsWith<GradleExtractionException> { ModelExtractor().extract(request(ModelMode.GRADLE)) }
        assertContains(failure.message!!, "exited with status")
        assertEquals(emptyList(), brokenOut.listDirectoryEntries(), "a failed extraction leaves nothing behind")

        val messages = mutableListOf<String>()
        val fallback = ModelExtractor(messages::add).extract(request(null))
        assertEquals(ModelMode.DEGRADED, fallback.mode)
        assertTrue(fallback.fallback)
        assertFalse(fallback.cached)
        assertNull(fallback.gradleTasks)
        assertContains(assertNotNull(fallback.gradleFailure), "exited with status")
        assertTrue(messages.any { it.startsWith("gradle: ") && "FAILED" in it }, "Gradle's own output is passed on")

        val degraded = ExtractedModel.read(fallback.directory)
        assertEquals(ModelMode.DEGRADED, degraded.index.mode)
        val manifest = degraded.manifests.single()
        assertEquals(":", manifest.path)
        assertEquals(ModelMode.DEGRADED, manifest.mode)
        assertEquals(
            listOf("activities", "helpers", "nexus-api", "unrelated", "workflows").map { "$it/src/main/kotlin" },
            manifest.sources,
        )
        assertMatchesSchema(ModelSchemas.MANIFEST_RESOURCE, fallback.directory.resolve("modules/_.json"))
        assertMatchesSchema(ModelSchemas.INDEX_RESOURCE, fallback.directory.resolve("index.json"))

        // The degraded model does not stand in for a Gradle model that is asked for by name.
        assertFailsWith<GradleExtractionException> { ModelExtractor().extract(request(ModelMode.GRADLE)) }
        assertTrue(ModelExtractor().extract(request(null)).cached)

        assertEquals(before, TreeSnapshot.metadata(other.root))
        assertEquals("", other.status())
        assertEquals(emptyList(), scratchParent.listDirectoryEntries())
    }

    @Test
    fun `the degraded model of the sample covers the same sources as the Gradle model, plus the module left out`() {
        val degraded = ExtractedModel.read(
            ModelExtractor().extract(
                request(repository, commit, dir.resolve("out-degraded"), ModelMode.DEGRADED, scratch = "scratch-other"),
            ).directory,
        )

        val gradleSources = model.manifests.flatMap { it.sources }.sorted()
        assertEquals((gradleSources + "unrelated/src/main/kotlin").sorted(), degraded.manifests.single().sources)
        for (source in gradleSources) {
            assertEquals(
                TreeSnapshot.contents(model.sourceRoot.resolve(source)),
                TreeSnapshot.contents(degraded.sourceRoot.resolve(source)),
                source,
            )
        }
        // Whatever a module sees in Gradle mode from the SDK, the degraded classpath offers too.
        val degradedNames = degraded.manifests.single().jars.map { Path.of(it).name }.toSet()
        val gradleNames = manifest(":activities").jars.map { Path.of(it).name }.toSet()
        assertEquals(emptySet(), gradleNames - degradedNames)
    }
}
