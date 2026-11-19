package io.github.iw.durableatlas.overlay.fetch

import com.google.protobuf.ByteString
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryFormatException
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.history.v1.History
import io.temporal.api.workflow.v1.WorkflowExecutionInfo
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.serviceclient.WorkflowServiceStubs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Thrown when histories cannot be fetched. The message says why, in words fit to show. */
class FetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Which histories to fetch, and where to put them.
 *
 * @param limit the most executions to fetch
 * @param startedFrom only executions that started at this time or later
 * @param startedBefore only executions that started before this time
 */
data class FetchRequest(
    val workflowType: String,
    val limit: Int,
    val out: Path,
    val startedFrom: Instant? = null,
    val startedBefore: Instant? = null,
) {
    init {
        require(workflowType.isNotEmpty()) { "a workflow type is needed" }
        require(limit > 0) { "the limit must be positive: $limit" }
    }
}

/**
 * A history that was written.
 *
 * @param file name of the file inside the output directory
 * @param started when the execution started, as the server lists it
 * @param events the number of events in the history
 * @param workerVersions the versions of the workers that completed workflow tasks of the
 *   execution, in the order they first did
 * @param workerVersion the version a version map is asked about: that of the worker that
 *   completed the last workflow task
 */
class FetchedHistory(
    val file: String,
    val workflowId: String,
    val runId: String,
    val started: Instant?,
    val status: CloseStatus,
    val events: Int,
    val workerVersions: List<WorkerVersion>,
    val workerVersion: WorkerVersion?,
)

/** An execution the server listed and then had no history for, as when it was deleted in between. */
class MissingExecution(val workflowId: String, val runId: String)

/**
 * What [HistoryFetcher.fetch] did.
 *
 * @param query the query the executions were listed with
 * @param histories the histories written, ordered by file name
 * @param more whether the server has more executions that fit than the limit allowed
 */
class FetchSummary(
    val outDir: Path,
    val address: String,
    val namespace: String,
    val query: String,
    val histories: List<FetchedHistory>,
    val missing: List<MissingExecution>,
    val more: Boolean,
)

/**
 * Downloads the event histories of executions of one workflow type from a Temporal server.
 *
 * The executions are found with a visibility query that asks for nothing but the workflow type
 * and, where the request has them, the bounds on the start time: `WorkflowType = '…' AND
 * StartTime >= '…' AND StartTime < '…'`. The server decides which executions come first when
 * there are more than the limit; it lists the most recent first.
 *
 * Each history goes to a file of its own (see [HistoryFileNames]) in the form
 * `temporal workflow show --output json` prints, which is what [HistoryJson] reads.
 *
 * @param connectTimeout how long to wait for the server to answer at all
 */
class HistoryFetcher(
    private val log: (String) -> Unit = {},
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
) {
    /**
     * @throws FetchException if the server cannot be reached, refuses the credentials, does not
     *   know the namespace or fails otherwise, or if a history cannot be written. Histories
     *   written by then stay where they are
     * @throws ConnectionSettingsException if [settings] name certificates that cannot be read
     */
    fun fetch(request: FetchRequest, settings: ConnectionSettings): FetchSummary = fetch(request, settings, null)

    /** @param channel a connection to use instead of making one to the address of [settings] */
    internal fun fetch(request: FetchRequest, settings: ConnectionSettings, channel: ManagedChannel?): FetchSummary {
        val service = WorkflowServiceStubs.newServiceStubs(settings.stubsOptions(channel))
        try {
            reach(service, settings)
            return Session(service.blockingStub(), request, settings).run()
        } finally {
            service.shutdownNow()
            service.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    /**
     * Asks the server whether it is there, before anything is asked of it. A server that refuses
     * the connection has answered; one that says nothing is given [connectTimeout].
     */
    private fun reach(service: WorkflowServiceStubs, settings: ConnectionSettings) {
        val health = HealthGrpc.newBlockingStub(service.blockingStub().channel)
            .withDeadlineAfter(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        val status = try {
            health.check(HealthCheckRequest.newBuilder().setService(WorkflowServiceStubs.HEALTH_CHECK_SERVICE_NAME).build()).status
        } catch (e: StatusRuntimeException) {
            throw failure(e, settings, "answer")
        }
        if (status != HealthCheckResponse.ServingStatus.SERVING) {
            throw FetchException("the Temporal server at ${settings.address} cannot be reached: it says it is not serving (${status.name})")
        }
    }

    private inner class Session(
        private val stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
        private val request: FetchRequest,
        private val settings: ConnectionSettings,
    ) {
        private val query = query(request)

        fun run(): FetchSummary {
            val (executions, more) = list()
            log(
                "found ${executions.size} execution(s) of workflow type ${request.workflowType} in namespace " +
                    "${settings.namespace} on ${settings.address}" + if (more) ", and there are more" else "",
            )
            if (executions.isEmpty() && request.workflowType.any { it == '\\' || it == '"' }) {
                log("warning: the server's query language does not match names that hold \\ or \" reliably")
            }

            val out = request.out.toAbsolutePath().normalize()
            val written = ArrayList<FetchedHistory>()
            val missing = ArrayList<MissingExecution>()
            for (execution in executions) {
                val id = execution.execution
                val history = history(id)
                if (history == null) {
                    log("warning: the server has no history for ${id.workflowId} (run ${id.runId}) any more; left out")
                    missing += MissingExecution(id.workflowId, id.runId)
                    continue
                }
                val name = HistoryFileNames.of(request.workflowType, id.workflowId, id.runId)
                val read = try {
                    HistoryJson.of(history, name)
                } catch (e: HistoryFormatException) {
                    throw FetchException("the server sent something else than a history for ${id.workflowId} (run ${id.runId}): ${e.message}", e)
                }
                try {
                    Files.createDirectories(out)
                    Files.write(out.resolve(name), HistoryJson.encode(history).toByteArray(Charsets.UTF_8))
                } catch (e: IOException) {
                    throw FetchException("the history of ${id.workflowId} could not be written: $e", e)
                }
                written += FetchedHistory(
                    file = name,
                    workflowId = id.workflowId,
                    runId = id.runId,
                    started = execution.takeIf { it.hasStartTime() }?.startTime?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) },
                    status = read.status,
                    events = read.events,
                    workerVersions = read.workerVersions,
                    workerVersion = read.workerVersion,
                )
            }
            return FetchSummary(out, settings.address, settings.namespace, query, written.sortedBy { it.file }, missing, more)
        }

        /** The executions to fetch, as the server lists them, and whether it has more. */
        private fun list(): Pair<List<WorkflowExecutionInfo>, Boolean> {
            val found = LinkedHashMap<Pair<String, String>, WorkflowExecutionInfo>()
            var token = ByteString.EMPTY
            var more = false
            do {
                val page = ListWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(settings.namespace)
                    .setQuery(query)
                    .setPageSize(minOf(request.limit - found.size, PAGE_SIZE))
                    .setNextPageToken(token)
                    .build()
                val response = call("list the executions") { stub.listWorkflowExecutions(page) }
                for (execution in response.executionsList) {
                    // The query asks for one workflow type; a server that reads it more loosely is not taken at its word.
                    if (execution.type.name != request.workflowType) continue
                    val key = execution.execution.workflowId to execution.execution.runId
                    if (found.size < request.limit) found.putIfAbsent(key, execution) else if (key !in found) more = true
                }
                token = response.nextPageToken
            } while (found.size < request.limit && !token.isEmpty)
            return found.values.toList() to (more || !token.isEmpty)
        }

        /** The whole history of [execution], or `null` if the server no longer has it. */
        private fun history(execution: WorkflowExecution): History? {
            val history = History.newBuilder()
            var token = ByteString.EMPTY
            do {
                val page = GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(settings.namespace)
                    .setExecution(execution)
                    .setNextPageToken(token)
                    .build()
                val response = try {
                    call("send the history of ${execution.workflowId}", notFoundIsNamespace = false) { stub.getWorkflowExecutionHistory(page) }
                } catch (e: ExecutionGone) {
                    return null
                }
                history.addAllEvents(response.history.eventsList)
                token = response.nextPageToken
            } while (!token.isEmpty)
            return history.build()
        }

        /**
         * Makes a request. A server that is busy says so and is asked again, a little later each
         * time; anything else it refuses ends the fetch.
         */
        private fun <T> call(doing: String, notFoundIsNamespace: Boolean = true, request: () -> T): T {
            var pause = FIRST_PAUSE_MILLIS
            var waited = 0L
            while (true) {
                try {
                    return request()
                } catch (e: StatusRuntimeException) {
                    val code = e.status.code
                    when {
                        code == Status.Code.RESOURCE_EXHAUSTED && waited < MAX_WAIT_MILLIS -> {
                            Thread.sleep(pause)
                            waited += pause
                            pause = minOf(pause * 2, MAX_PAUSE_MILLIS)
                        }
                        code == Status.Code.NOT_FOUND && !notFoundIsNamespace -> throw ExecutionGone()
                        else -> throw failure(e, settings, doing, query)
                    }
                }
            }
        }
    }

    private class ExecutionGone : RuntimeException()

    companion object {
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Executions asked for at a time. */
        internal const val PAGE_SIZE = 100

        private const val MAX_DETAIL = 200
        private const val FIRST_PAUSE_MILLIS = 250L
        private const val MAX_PAUSE_MILLIS = 2_000L
        private const val MAX_WAIT_MILLIS = 30_000L

        /** The visibility query for [request]. */
        internal fun query(request: FetchRequest): String =
            buildString {
                // A quote is written twice. The server's query language has no sure way to write a backslash, so it stays as it is.
                append("WorkflowType = '").append(request.workflowType.replace("'", "''")).append('\'')
                request.startedFrom?.let { append(" AND StartTime >= '").append(it).append('\'') }
                request.startedBefore?.let { append(" AND StartTime < '").append(it).append('\'') }
            }

        /** What went wrong, for a person who has to act on it. */
        private fun failure(e: StatusRuntimeException, settings: ConnectionSettings, doing: String, query: String? = null): FetchException {
            val server = "the Temporal server at ${settings.address}"
            // The first line of what the server or the connection said; what follows is for those who wrote them.
            val said = listOfNotNull(e.status.description, rootCause(e)?.message)
                .map { it.lineSequence().first().take(MAX_DETAIL) }
                .distinct()
                .joinToString(": ")
                .ifEmpty { e.status.code.name }
            val message = when (e.status.code) {
                Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED ->
                    "$server cannot be reached: $said"
                Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED ->
                    "$server refused the credentials for namespace ${settings.namespace}: $said"
                Status.Code.NOT_FOUND ->
                    "$server does not know the namespace ${settings.namespace}: $said"
                Status.Code.INVALID_ARGUMENT ->
                    "$server refused the query${query?.let { " $it" } ?: ""}: $said"
                Status.Code.RESOURCE_EXHAUSTED ->
                    "$server is too busy to $doing: $said"
                else ->
                    "$server failed to $doing: ${e.status.code.name}: $said"
            }
            return FetchException(message, e)
        }

        private fun rootCause(e: Throwable): Throwable? {
            var cause = e.cause ?: return null
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause
        }
    }
}
