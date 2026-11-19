plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.integration-test")
    id("durable-atlas.analysis-classpath")
    application
}

application {
    applicationName = "durable-atlas"
    mainClass = "io.github.iw.durableatlas.cli.MainKt"
    // Resolving Kotlin recurses deeply on long expressions. The heap is left to the JVM's own
    // sizing; DURABLE_ATLAS_OPTS and JAVA_OPTS can set it. The analysis libraries load native code
    // through JNA, which Java 24 and later warn about unless native access is enabled; Java 17
    // accepts the flag as well.
    applicationDefaultJvmArgs = listOf("-Xss4m", "--enable-native-access=ALL-UNNAMED")
}

// Java 23 and later warn on stderr when a library reads memory through sun.misc.Unsafe, which the
// protobuf and Netty versions the Temporal SDK brings still do, unless that is allowed. Java 17 to
// 22 refuse the option that allows it, so both launchers add it only for later versions. They read
// the version from the line of `java -version` whose second word is "version", because the JVM may
// first print the options it picked up from JAVA_TOOL_OPTIONS.
tasks.startScripts {
    doLast {
        val scripts = this as CreateStartScripts

        val unix = scripts.unixScript
        val exec = "exec \"\$JAVACMD\" \"\$@\""
        val unixAllowUnsafe = """
            |case ${'$'}("${'$'}JAVACMD" -version 2>&1 | sed -n 's/^[^ ]* version "\([0-9]*\).*/\1/p' | head -n 1) in
            |    1|17|18|19|20|21|22|'') ;;
            |    *) set -- --sun-misc-unsafe-memory-access=allow "${'$'}@" ;;
            |esac
            |
            |$exec
        """.trimMargin()
        val unixText = unix.readText()
        check(unixText.lines().count { it == exec } == 1) { "the Unix launcher no longer ends with the expected exec line" }
        unix.writeText(unixText.replace(exec, unixAllowUnsafe))

        // The Windows launcher runs Java from a `for /f`; the outer escaped quotes keep a path to
        // java.exe with spaces or parentheses in it whole when cmd strips the first and last quote.
        val windows = scripts.windowsScript
        val execute = "@rem Execute ${scripts.applicationName}"
        val windowsAllowUnsafe = listOf(
            "set JAVA_MAJOR=",
            "for /f \"tokens=1-3\" %%a in ('^\"\"%JAVA_EXE%\" -version 2^>^&1^\"') do if /i \"%%b\"==\"version\" if not defined JAVA_MAJOR " +
                "for /f \"delims=.-+\" %%m in (\"%%~c\") do set JAVA_MAJOR=%%m",
            "if defined JAVA_MAJOR if %JAVA_MAJOR% GEQ 23 set DEFAULT_JVM_OPTS=%DEFAULT_JVM_OPTS% \"--sun-misc-unsafe-memory-access=allow\"",
            "",
            execute,
        ).joinToString("\r\n")
        val windowsText = windows.readText()
        check(windowsText.split("\r\n").count { it == execute } == 1) { "the Windows launcher no longer has the expected execute line" }
        windows.writeText(windowsText.replace(execute, windowsAllowUnsafe))
    }
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":model-extractor"))
    implementation(project(":analyser"))
    implementation(project(":render"))
    implementation(project(":overlay"))
    implementation(libs.clikt)

    // The Temporal SDK, which reads event histories, logs through SLF4J. A command line tool has
    // its own output, so the SDK's logging goes nowhere instead of asking for a place on stderr.
    runtimeOnly(libs.slf4j.nop)

    // The analysis backend with the libraries it runs on. They are loaded by a class loader of
    // their own and must stay off the classpath above.
    analysisClasspath(project(mapOf("path" to ":analyser", "configuration" to "analysisBackendElements")))

    testImplementation(testFixtures(project(":model-extractor")))
    testImplementation(testFixtures(project(":analyser")))
    testImplementation(testFixtures(project(":ir")))
    // A stand-in for a Temporal server, for the tests of the command that fetches histories.
    testImplementation(testFixtures(project(":overlay")))
}

// The degraded-mode classpath travels with the tool, next to its own jars, so that mode needs no
// network access. So does the analysis backend, in a directory of its own because it is not part
// of the tool's classpath.
distributions {
    main {
        contents {
            into("lib/degraded") {
                from(configurations.degradedClasspath)
            }
            into("lib/analysis") {
                from(configurations.analysisClasspath)
            }
        }
    }
}

// The tests that need a Temporal server to fetch from are no part of the build. They live among the
// integration tests, carry the tag `server`, and run only when asked for:
//     ./gradlew :cli:serverTest [-Ddurable-atlas.server=<host:port>]
val serverTag = "server"
val serverTest = tasks.register<Test>("serverTest") {
    description = "Runs the tests that fetch from a Temporal server, by default the one at 127.0.0.1:7233."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val integrationTests = sourceSets["integrationTest"]
    testClassesDirs = integrationTests.output.classesDirs
    classpath = integrationTests.runtimeClasspath
    useJUnitPlatform { includeTags(serverTag) }
    systemProperty("durable-atlas.server", providers.systemProperty("durable-atlas.server").getOrElse("127.0.0.1:7233"))
    // What the server holds is no input Gradle can see.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging { showStandardStreams = true }
}

// One test starts the installed tool as a user would, to check that the distribution is complete.
tasks.named<Test>("integrationTest") {
    useJUnitPlatform { excludeTags(serverTag) }
    val installation = tasks.installDist.map { it.destinationDir }
    inputs.dir(installation).withPropertyName("installation").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Ddurable-atlas.installDir=${installation.get().path}") })
    // Golden files are read from the source tree, so a change to one has to run the tests again.
    inputs.dir(layout.projectDirectory.dir("src/test/resources/golden"))
        .withPropertyName("goldenFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
