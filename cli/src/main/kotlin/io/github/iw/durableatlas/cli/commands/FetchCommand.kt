package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.overlay.fetch.ConnectionFlags
import io.github.iw.durableatlas.overlay.fetch.FetchRequest
import io.github.iw.durableatlas.overlay.fetch.FetchSummary
import io.github.iw.durableatlas.overlay.fetch.HistoryFetcher
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Downloads the event histories of executions of one workflow type, each to a file `overlay`
 * reads.
 *
 * Where the server is and how to sign in comes from the options, then from the `TEMPORAL_*`
 * environment variables, then from a profile of Temporal's configuration file. Finding no
 * execution is no failure; a server that cannot be reached or refuses the request is.
 *
 * @param environment the environment variables the connection settings are read from
 */
class FetchCommand(private val environment: Map<String, String> = System.getenv()) : StageCommand(
    name = "fetch",
    summary = "Download event histories of recent executions of a workflow type from a Temporal server.",
) {
    private val workflowType: String by option("--workflow-type", metavar = "type", help = "Workflow type to list.")
        .required()
    private val limit: Int by option("--limit", metavar = "n", help = "Maximum number of executions to fetch. The server lists the most recent first.")
        .int()
        .restrictTo(min = 1)
        .required()
    private val out: Path by option("--out", metavar = "dir", help = "Directory to write the histories to, one JSON file per execution.")
        .path(canBeFile = false)
        .required()
    private val since: Instant? by option(
        "--since",
        metavar = "time",
        help = "Only executions that started at this time or later: a date (2026-11-17, midnight UTC) or a date " +
            "and time with an offset (2026-11-17T09:30:00Z).",
    ).convert { time(it) ?: fail(NO_TIME) }
    private val until: Instant? by option("--until", metavar = "time", help = "Only executions that started before this time.")
        .convert { time(it) ?: fail(NO_TIME) }

    private val address: String? by option(
        "--address",
        metavar = "host:port",
        help = "Temporal frontend address. Environment: TEMPORAL_ADDRESS. By default 127.0.0.1:7233.",
    )
    private val namespace: String? by option(
        "--namespace",
        metavar = "name",
        help = "Temporal namespace. Environment: TEMPORAL_NAMESPACE. By default 'default'.",
    )
    private val apiKey: String? by option(
        "--api-key",
        metavar = "key",
        help = "API key to authenticate with. Environment: TEMPORAL_API_KEY.",
    )
    private val tls: Boolean? by option(
        "--tls",
        help = "Connect over TLS, or not. Environment: TEMPORAL_TLS. An API key or a certificate turns TLS on.",
    ).nullableFlag("--no-tls")
    private val tlsCertPath: String? by option(
        "--tls-cert-path",
        metavar = "file",
        help = "Client certificate in PEM form. Environment: TEMPORAL_TLS_CLIENT_CERT_PATH.",
    )
    private val tlsKeyPath: String? by option(
        "--tls-key-path",
        metavar = "file",
        help = "Key of the client certificate in PEM form. Environment: TEMPORAL_TLS_CLIENT_KEY_PATH.",
    )
    private val tlsCaPath: String? by option(
        "--tls-ca-path",
        metavar = "file",
        help = "Certificates to check the server's certificate against. Environment: TEMPORAL_TLS_SERVER_CA_CERT_PATH.",
    )
    private val tlsServerName: String? by option(
        "--tls-server-name",
        metavar = "name",
        help = "Name to check the server's certificate for. Environment: TEMPORAL_TLS_SERVER_NAME.",
    )
    private val profile: String? by option(
        "--profile",
        metavar = "name",
        help = "Profile of Temporal's configuration file (temporal.toml, or the file TEMPORAL_CONFIG_FILE names) to " +
            "take settings from that neither an option nor the environment gives. Environment: TEMPORAL_PROFILE.",
    )
    private val connectTimeout: Int by option(
        "--connect-timeout",
        metavar = "seconds",
        help = "How long to wait for the server to answer at all.",
    )
        .int()
        .restrictTo(min = 1)
        .default(HistoryFetcher.DEFAULT_CONNECT_TIMEOUT.seconds.toInt())

    override fun helpEpilog(context: Context): String =
        "Every connection setting is taken from its option, else from its environment variable, else from the profile, " +
            "else from the default."

    override fun run() {
        val request = FetchRequest(workflowType, limit, Summaries.shown(out), startedFrom = since, startedBefore = until)
        val flags = ConnectionFlags(
            address = address,
            namespace = namespace,
            apiKey = apiKey,
            tls = tls,
            tlsCertPath = tlsCertPath,
            tlsKeyPath = tlsKeyPath,
            tlsCaPath = tlsCaPath,
            tlsServerName = tlsServerName,
            profile = profile,
        )
        val summary = attempt { Stages(::log).fetch(request, flags, environment, Duration.ofSeconds(connectTimeout.toLong())) }
        if (json) echo(Summaries.format(summary(summary))) else echo(describe(summary))
    }

    private fun summary(summary: FetchSummary): JsonObject =
        buildJsonObject {
            put("command", commandName)
            put("address", summary.address)
            put("namespace", summary.namespace)
            put("workflowType", workflowType)
            put("query", summary.query)
            put("limit", limit)
            put("directory", summary.outDir.toString())
            put(
                "histories",
                buildJsonArray {
                    summary.histories.forEach { history ->
                        add(
                            buildJsonObject {
                                put("file", summary.outDir.resolve(history.file).toString())
                                put("workflowId", history.workflowId)
                                put("runId", history.runId)
                                history.started?.let { put("startTime", it.toString()) }
                                put("status", history.status.text)
                                put("events", history.events)
                                history.workerVersion?.let { put("workerVersion", version(it)) }
                                // Only an execution that changed hands has more to say than the one version.
                                if (history.workerVersions.size > 1 || history.workerVersions.firstOrNull() != history.workerVersion) {
                                    put("workerVersions", buildJsonArray { history.workerVersions.forEach { add(version(it)) } })
                                }
                            },
                        )
                    }
                },
            )
            put(
                "missing",
                buildJsonArray {
                    summary.missing.forEach {
                        add(
                            buildJsonObject {
                                put("workflowId", it.workflowId)
                                put("runId", it.runId)
                            },
                        )
                    }
                },
            )
            put("more", summary.more)
        }

    private fun version(version: WorkerVersion): JsonObject =
        buildJsonObject {
            version.deployment?.let { put("deployment", it) }
            put("buildId", version.buildId)
        }

    private fun describe(summary: FetchSummary): String =
        buildString {
            if (summary.histories.isNotEmpty()) {
                val rows = summary.histories.map { history ->
                    listOf(
                        history.file,
                        history.workflowId,
                        history.status.words,
                        history.events.toString(),
                        history.workerVersions.joinToString(", then ").ifEmpty { "-" },
                    )
                }
                append(Summaries.table(listOf("History", "Workflow id", "Status", "Events", "Worker version"), rows, rightAligned = setOf(3)))
                append('\n')
            }
            append("${summary.histories.size} history file(s) of workflow type $workflowType from namespace ${summary.namespace} on ${summary.address}")
            if (summary.more) append("; more executions fit, raise --limit to fetch them")
            append('\n')
            append(summary.outDir)
        }

    private companion object {
        const val NO_TIME = "neither a date like 2026-11-17 nor a time like 2026-11-17T09:30:00Z"

        /** A point in time as an option names it: a day, which begins at midnight UTC, or a date and time with its offset. */
        fun time(text: String): Instant? =
            try {
                if ('T' in text) OffsetDateTime.parse(text).toInstant() else LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
    }
}
