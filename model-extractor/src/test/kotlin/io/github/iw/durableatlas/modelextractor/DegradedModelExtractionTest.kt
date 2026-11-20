package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.modelextractor.testing.TreeSnapshot
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
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

/** Everything about extraction that needs git but not the sample repository's Gradle build. */
class DegradedModelExtractionTest {
    @TempDir
    lateinit var dir: Path

    private val log = mutableListOf<String>()
    private val extractor = ModelExtractor(log::add)

    private val out: Path get() = dir.resolve("out")
    private val scratchParent: Path get() = dir.resolve("scratch")

    private fun request(
        repository: SampleRepository = shared,
        commit: String = repository.head,
        mode: ModelMode? = ModelMode.DEGRADED,
        force: Boolean = false,
        keepScratch: Boolean = false,
    ) = ModelRequest(repository.project, commit, out, mode, force, keepScratch, scratchParent)

    @Test
    fun `a degraded model is a single module made of every main source directory`() {
        val result = extractor.extract(request())

        assertEquals(shared.head, result.commit)
        assertEquals(ModelMode.DEGRADED, result.mode)
        assertEquals(out.resolve(shared.head), result.directory)
        assertFalse(result.cached)
        assertFalse(result.fallback)
        assertNull(result.gradleTasks)
        assertEquals(
            ModelIndex(
                commit = shared.head,
                mode = ModelMode.DEGRADED,
                modules = listOf(IndexedModule(":", "modules/_.json")),
                dropped = emptyList(),
            ),
            result.index,
        )

        val model = ExtractedModel.read(result.directory)
        assertEquals(result.index, model.index)
        val manifest = model.manifests.single()
        assertEquals(shared.head, manifest.commit)
        assertEquals(":", manifest.path)
        assertEquals(ModelMode.DEGRADED, manifest.mode)
        assertEquals(emptyList(), manifest.projectDeps)
        assertEquals(
            listOf("activities", "helpers", "nexus-api", "unrelated", "workflows").map { "$it/src/main/kotlin" },
            manifest.sources,
        )
    }

    @Test
    fun `the Java source directories of a commit are part of a degraded model, with the Kotlin files that lie in them`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val commit = repository.commit("Add Java sources") { project ->
            val java = Files.createDirectories(project.resolve("activities/src/main/java/com/example/fixture/activities"))
            Files.writeString(java.resolve("Ledger.java"), "package com.example.fixture.activities;\n\npublic interface Ledger {}\n")
            Files.writeString(java.resolve("LedgerNotes.kt"), "package com.example.fixture.activities\n\nclass LedgerNotes\n")
            // Not a main source directory: neither its place nor its name makes it one.
            val other = Files.createDirectories(project.resolve("activities/src/test/java/com/example"))
            Files.writeString(other.resolve("LedgerTest.java"), "package com.example;\n\nclass LedgerTest {}\n")
            val generated = Files.createDirectories(project.resolve("activities/src/main/java-templates"))
            Files.writeString(generated.resolve("Template.java"), "class Template {}\n")
        }

        val model = ExtractedModel.read(extractor.extract(request(repository, commit)).directory)

        val manifest = model.manifests.single()
        assertEquals(
            listOf(
                "activities/src/main/java",
                "activities/src/main/kotlin",
                "helpers/src/main/kotlin",
                "nexus-api/src/main/kotlin",
                "unrelated/src/main/kotlin",
                "workflows/src/main/kotlin",
            ),
            manifest.sources,
        )
        val java = model.sourceRoot.resolve("activities/src/main/java/com/example/fixture/activities")
        assertEquals(listOf("Ledger.java", "LedgerNotes.kt"), java.listDirectoryEntries().map { it.name }.sorted())
        assertContains(log, "taking the sources of 6 main source directories of $commit: 5 src/main/kotlin, 1 src/main/java")
        assertEquals(listOf("main"), model.sourceRoot.resolve("activities/src").listDirectoryEntries().map { it.name })
        assertEquals(listOf("java", "kotlin"), model.sourceRoot.resolve("activities/src/main").listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `the directories of a degraded model are the main Kotlin and Java ones, each once`() {
        assertEquals(
            listOf("a/src/main/java", "a/src/main/kotlin", "b/c/src/main/java", "src/main/java", "src/main/kotlin"),
            ModelBuilder.degradedSources(
                setOf(
                    "a", "a/src", "a/src/main", "a/src/main/java", "a/src/main/java/com", "a/src/main/kotlin", "a/src/main/resources",
                    "a/src/test/java", "a/src/main/javascript", "a/src/main/kotlin-gen", "a/xsrc/main/java",
                    "b/c/src/main/java", "src/main/java", "src/main/kotlin",
                    // A directory of that name inside a source directory comes with the one around it.
                    "a/src/main/kotlin/templates/src/main/kotlin", "a/src/main/java/samples/src/main/java",
                ),
            ),
        )
        assertEquals(listOf("only/src/main/java"), ModelBuilder.degradedSources(setOf("only", "only/src/main/java", "docs")))
        assertEquals(emptyList(), ModelBuilder.degradedSources(setOf("docs", "src", "src/main", "src/main/resources", "lib/main/java")))
    }

    @Test
    fun `a commit with Java sources alone gives a degraded model, and one without main sources is an error that names both directories`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val kotlin = listOf("activities", "helpers", "nexus-api", "unrelated", "workflows").map { "$it/src/main/kotlin" }
        val none = repository.commit("Remove the sources") { _ -> repository.git("rm", "-r", "--quiet", "--", *kotlin.toTypedArray()) }
        val javaOnly = repository.commit("Add Java sources") { project ->
            val java = Files.createDirectories(project.resolve("activities/src/main/java/com/example"))
            Files.writeString(java.resolve("Ledger.java"), "package com.example;\n\npublic interface Ledger {}\n")
        }

        val model = ExtractedModel.read(extractor.extract(request(repository, javaOnly)).directory)
        assertEquals(listOf("activities/src/main/java"), model.manifests.single().sources)

        val failure = assertFailsWith<ModelExtractionException> { extractor.extract(request(repository, none)) }
        assertEquals("Commit $none has no src/main/kotlin or src/main/java directory", failure.message)
    }

    @Test
    fun `the degraded classpath is the Temporal SDK with its Kotlin extensions, the Kotlin standard library and what the SDK exposes`() {
        val model = ExtractedModel.read(extractor.extract(request()).directory)

        val jars = model.jars(model.manifests.single())
        val names = jars.map { it.name }
        assertTrue(names.any { it.startsWith("temporal-sdk-") }, names.toString())
        assertTrue(names.any { it.startsWith("temporal-kotlin-") }, names.toString())
        assertTrue(names.any { it.startsWith("kotlin-stdlib-") }, names.toString())
        // The extensions come with what their API needs, which the SDK and the standard library are, and not with
        // what they need to run: the libraries they read Kotlin classes with.
        assertTrue(names.none { it.startsWith("kotlin-reflect-") || it.startsWith("jackson-module-kotlin-") }, names.toString())
        assertTrue(names.any { it.startsWith("temporal-serviceclient-") }, names.toString())
        assertTrue(names.any { it.startsWith("nexus-sdk-") }, names.toString())
        for (jar in jars) {
            assertTrue(jar.isAbsolute && Files.isRegularFile(jar), "$jar should be an existing file")
        }
        assertEquals(jars.map { it.toString() }, model.manifests.single().jars)
    }

    @Test
    fun `the model holds a copy of the sources and of nothing else`() {
        val model = ExtractedModel.read(extractor.extract(request()).directory)

        for (directory in model.sourceDirectories(model.manifests.single())) {
            val original = shared.project.resolve(model.sourceRoot.relativize(directory).toString())
            assertEquals(TreeSnapshot.contents(original), TreeSnapshot.contents(directory), directory.toString())
        }
        assertEquals(
            listOf("activities", "helpers", "nexus-api", "unrelated", "workflows"),
            model.sourceRoot.listDirectoryEntries().map { it.name }.sorted(),
        )
        assertEquals(listOf("main"), model.sourceRoot.resolve("workflows/src").listDirectoryEntries().map { it.name })
        assertEquals(
            listOf("index.json", "modules", "source"),
            model.directory.listDirectoryEntries().map { it.name }.sorted(),
        )
    }

    @Test
    fun `the configuration files beside each source directory come with a degraded model, and nothing else of the resources`() {
        val repository = SampleRepository.create(dir.resolve("repo-configured"))
        val configured = repository.commit("Configure the services") { project ->
            write(project, "activities/src/main/resources/application.yaml", "inventory:\n  url: http://inventory.example.com\n")
            write(project, "activities/src/main/resources/application.properties", "billing.url=http://billing.example.com\n")
            write(project, "activities/src/main/resources/logback.xml", "<configuration/>\n")
            write(project, "activities/src/test/resources/application.yaml", "inventory:\n  url: http://localhost\n")
            write(project, "workflows/src/main/resources/application.yml", "a: b\n")
            write(project, "config/application.yaml", "a: b\n")
        }

        val model = ExtractedModel.read(extractor.extract(request(repository, configured)).directory)

        val expected = listOf(
            "activities/src/main/resources/application.properties",
            "activities/src/main/resources/application.yaml",
            "workflows/src/main/resources/application.yml",
        )
        val manifest = model.manifests.single()
        assertEquals(expected, manifest.resources)
        assertEquals(expected.map { model.sourceRoot.resolve(it) }, model.resourceFiles(manifest))
        for (file in expected) {
            assertEquals(Files.readString(repository.project.resolve(file)), Files.readString(model.sourceRoot.resolve(file)), file)
        }
        assertFalse(model.sourceRoot.resolve("activities/src/main/resources/logback.xml").exists())
        assertFalse(model.sourceRoot.resolve("activities/src/test").exists())
        assertFalse(model.sourceRoot.resolve("config").exists())
        JsonSchemaChecker.forResource(ModelSchemas.MANIFEST_RESOURCE).assertValid(Files.readString(model.directory.resolve("modules/_.json")))
    }

    @Test
    fun `a degraded model keeps the configuration files of the resource directory next to a source directory`() {
        val files = listOf(
            "src/main/resources/application.yaml",
            "a/src/main/resources/application.properties",
            "a/src/main/resources/nested/application.yaml",
            "b/src/main/resources/application.yml",
            "c/src/main/resources/application.yaml",
            "a/src/test/resources/application.yaml",
        )
        assertEquals(
            listOf("a/src/main/resources/application.properties", "b/src/main/resources/application.yml", "src/main/resources/application.yaml"),
            ModelBuilder.degradedResources(listOf("src/main/kotlin", "a/src/main/kotlin", "b/src/main/java"), files),
        )
        assertEquals(emptyList(), ModelBuilder.degradedResources(listOf("a/src/main/kotlin"), emptyList()))
    }

    private fun write(project: Path, path: String, text: String) {
        val file = project.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `every written file matches its schema`() {
        val result = extractor.extract(request())

        JsonSchemaChecker.forResource(ModelSchemas.INDEX_RESOURCE)
            .assertValid(Files.readString(result.directory.resolve("index.json")))
        JsonSchemaChecker.forResource(ModelSchemas.MANIFEST_RESOURCE)
            .assertValid(Files.readString(result.directory.resolve("modules/_.json")))
    }

    @Test
    fun `the repository is left exactly as it was`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val before = TreeSnapshot.metadata(repository.root)

        extractor.extract(request(repository))

        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertEquals("", repository.status())
    }

    @Test
    fun `extracting the same commit again gives identical output`() {
        val first = TreeSnapshot.contents(extractor.extract(request()).directory)

        val again = extractor.extract(request(force = true))

        assertFalse(again.cached)
        assertEquals(first, TreeSnapshot.contents(again.directory))
        assertEquals(listOf(shared.head), out.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `an existing model is reused unless extraction is forced`() {
        val first = extractor.extract(request())
        val before = TreeSnapshot.metadata(first.directory)
        log.clear()

        val second = extractor.extract(request())

        assertTrue(second.cached)
        assertEquals(first.index, second.index)
        assertEquals(first.directory, second.directory)
        assertEquals(before, TreeSnapshot.metadata(first.directory))
        assertEquals(1, log.size, log.toString())
        assertContains(log.single(), "nothing to extract")
    }

    @Test
    fun `an existing degraded model satisfies a request that names no mode`() {
        extractor.extract(request())

        val result = extractor.extract(request(mode = null))

        assertTrue(result.cached)
        assertEquals(ModelMode.DEGRADED, result.mode)
        assertFalse(result.fallback)
    }

    @Test
    fun `the scratch directory is removed unless it is asked for`() {
        extractor.extract(request())
        assertEquals(emptyList(), scratchParent.listDirectoryEntries())

        val kept = extractor.extract(request(force = true, keepScratch = true))

        val scratch = assertNotNull(kept.scratch)
        assertTrue(scratch.isDirectory())
        assertEquals(listOf(scratch.name), scratchParent.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a branch name or short SHA is resolved to the full SHA`() {
        val byBranch = extractor.extract(request(commit = "main"))
        val byPrefix = extractor.extract(request(commit = shared.head.take(10)))

        assertEquals(shared.head, byBranch.commit)
        assertEquals(out.resolve(shared.head), byBranch.directory)
        assertTrue(byPrefix.cached)
        assertEquals(shared.head, byPrefix.commit)
    }

    @Test
    fun `sources come from the commit, not from the working tree`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val original = repository.head
        val file = "unrelated/src/main/kotlin/com/example/fixture/unrelated/Later.kt"
        val later = repository.commit("Add a file") { project ->
            Files.writeString(project.resolve(file), "package com.example.fixture.unrelated\n\nclass Later\n")
        }
        Files.writeString(repository.project.resolve(file), "this text was never committed\n")

        val old = extractor.extract(request(repository, commit = original))
        val new = extractor.extract(request(repository, commit = later))

        assertFalse(old.directory.resolve("source/$file").exists())
        assertContains(Files.readString(new.directory.resolve("source/$file")), "class Later")
        assertEquals(listOf(later, original).sorted(), out.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `a project in a subdirectory of the git repository is described relative to that subdirectory`() {
        val repository = SampleRepository.create(dir.resolve("repo"), subdirectory = "services/sample")
        val before = TreeSnapshot.metadata(repository.root)

        val model = ExtractedModel.read(extractor.extract(request(repository)).directory)

        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertContains(model.manifests.single().sources, "workflows/src/main/kotlin")
        assertTrue(model.sourceRoot.resolve("workflows/src/main/kotlin/com/example/fixture").isDirectory())
    }

    @Test
    fun `without a Gradle wrapper the result depends on the requested mode`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val commit = repository.commit("Remove the wrapper") { project -> Files.delete(project.resolve("gradlew")) }

        val failure = assertFailsWith<GradleExtractionException> {
            extractor.extract(request(repository, commit, mode = ModelMode.GRADLE))
        }
        assertContains(failure.message!!, "Gradle wrapper")
        assertEquals(emptyList(), out.listDirectoryEntries(), "a failed extraction leaves nothing behind")
        assertEquals(emptyList(), scratchParent.listDirectoryEntries())

        val result = extractor.extract(request(repository, commit, mode = null))
        assertEquals(ModelMode.DEGRADED, result.mode)
        assertTrue(result.fallback)
        assertContains(assertNotNull(result.gradleFailure), "Gradle wrapper")
        assertEquals(ModelMode.DEGRADED, ExtractedModel.read(result.directory).manifests.single().mode)
    }

    @Test
    fun `an unusable repository or commit is an error in every mode`() {
        for (mode in listOf(null, ModelMode.GRADLE, ModelMode.DEGRADED)) {
            val unknown = assertFailsWith<ModelExtractionException> {
                extractor.extract(request(commit = "no-such-commit", mode = mode))
            }
            assertFalse(unknown is GradleExtractionException)
            assertContains(unknown.message!!, "no-such-commit")
        }

        val plain = Files.createDirectories(dir.resolve("plain"))
        val failure = assertFailsWith<ModelExtractionException> {
            extractor.extract(ModelRequest(plain, "HEAD", out, ModelMode.DEGRADED, scratchParent = scratchParent))
        }
        assertContains(failure.message!!, "not inside a git repository")
        assertFalse(out.exists())
    }

    companion object {
        /** A repository for the tests that only read it. */
        private lateinit var shared: SampleRepository

        @JvmStatic
        @BeforeAll
        fun createRepository(@TempDir directory: Path) {
            shared = SampleRepository.create(directory.resolve("repo"))
        }
    }
}
