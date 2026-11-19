package io.github.iw.durableatlas.overlay.testing

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.StreamObserver
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.api.workflow.v1.WorkflowExecutionInfo
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.serviceclient.WorkflowServiceStubs
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the frontend of a Temporal server that knows the three requests a fetch makes:
 * the health check, the listing of executions and the reading of a history. It lists the most
 * recent executions first, as a server does, and understands the queries a fetch writes and no
 * others.
 *
 * It is reached through [channel], without a socket, or at the address [listen] returns.
 */
class FakeTemporalServer : AutoCloseable {
    class Execution(val namespace: String, val workflowId: String, val runId: String, val started: Instant, val history: History) {
        val workflowType: String get() = history.getEvents(0).workflowExecutionStartedEventAttributes.workflowType.name
    }

    /** The namespaces the server knows. */
    val namespaces: MutableSet<String> = mutableSetOf("default")

    /** If set, requests without this API key are refused. */
    @Volatile
    var apiKey: String? = null

    /** The most executions the server puts on one page, whatever is asked for. */
    @Volatile
    var maxPageSize: Int = 1000

    /** The most events the server puts on one page of a history. */
    @Volatile
    var historyPageSize: Int = Int.MAX_VALUE

    /** How many of the next listing requests are refused because the server is busy. */
    @Volatile
    var busyFor: Int = 0

    /** If set, every request about executions fails with it. */
    @Volatile
    var failure: Status? = null

    /** The listing requests received, in order. */
    val listed: MutableList<ListWorkflowExecutionsRequest> = CopyOnWriteArrayList()

    /** The history requests received, in order. */
    val read: MutableList<GetWorkflowExecutionHistoryRequest> = CopyOnWriteArrayList()

    /** The `authorization` header of every request about executions, `null` where there was none. */
    val authorizations: MutableList<String?> = CopyOnWriteArrayList()

    private val executions = CopyOnWriteArrayList<Execution>()

    /** Executions that are listed but whose history is gone, by run id. */
    private val gone = CopyOnWriteArrayList<String>()
    private val servers = ArrayList<Server>()
    private val channels = ArrayList<ManagedChannel>()

    /**
     * Adds an execution. Workflow id and run id are those of the first event of [history], where
     * it has them.
     */
    fun add(
        history: History,
        started: Instant,
        workflowId: String? = null,
        runId: String? = null,
        namespace: String = "default",
    ): Execution {
        val first = history.getEvents(0).workflowExecutionStartedEventAttributes
        val execution = Execution(
            namespace = namespace,
            workflowId = workflowId ?: first.workflowId.ifEmpty { "workflow-${executions.size + 1}" },
            runId = runId ?: first.originalExecutionRunId.ifEmpty { "run-${executions.size + 1}" },
            started = started,
            history = history,
        )
        executions += execution
        return execution
    }

    /** Adds the execution whose history [file] holds as JSON. */
    fun add(file: Path, started: Instant, workflowId: String? = null, runId: String? = null, namespace: String = "default"): Execution =
        add(WorkflowExecutionHistory.fromJson(Files.readString(file)).history, started, workflowId, runId, namespace)

    /** Keeps [execution] listed and forgets its history, as when it is deleted between the two requests. */
    fun forgetHistory(execution: Execution) {
        gone += execution.runId
    }

    /** A connection to the server that needs no socket. */
    fun channel(): ManagedChannel {
        val name = "fake-temporal-${UUID.randomUUID()}"
        servers += InProcessServerBuilder.forName(name).directExecutor().also(::serve).build().start()
        return InProcessChannelBuilder.forName(name).directExecutor().build().also { channels += it }
    }

    /** Listens on a free port of the loopback interface and returns the address, as `host:port`. */
    fun listen(): String {
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).also(::serve).build().start()
        servers += server
        return "127.0.0.1:${server.port}"
    }

    private fun serve(builder: ServerBuilder<*>) {
        val health = HealthStatusManager()
        health.setStatus(WorkflowServiceStubs.HEALTH_CHECK_SERVICE_NAME, HealthCheckResponse.ServingStatus.SERVING)
        builder.addService(health.healthService)
        builder.addService(ServerInterceptors.intercept(Service(), Credentials()))
    }

    override fun close() {
        channels.forEach { it.shutdownNow() }
        servers.forEach { it.shutdownNow() }
        servers.forEach { it.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private inner class Credentials : ServerInterceptor {
        override fun <Q, R> interceptCall(call: ServerCall<Q, R>, headers: Metadata, next: ServerCallHandler<Q, R>): ServerCall.Listener<Q> {
            val sent = headers.get(AUTHORIZATION)
            if (call.methodDescriptor.bareMethodName != "GetSystemInfo") authorizations += sent
            val wanted = apiKey
            if (wanted != null && sent != "Bearer $wanted") {
                call.close(Status.UNAUTHENTICATED.withDescription("Request unauthorized."), Metadata())
                return object : ServerCall.Listener<Q>() {}
            }
            return next.startCall(call, headers)
        }
    }

    private inner class Service : WorkflowServiceGrpc.WorkflowServiceImplBase() {
        override fun getSystemInfo(request: GetSystemInfoRequest, response: StreamObserver<GetSystemInfoResponse>) {
            response.onNext(GetSystemInfoResponse.newBuilder().setServerVersion("1.32.2").build())
            response.onCompleted()
        }

        override fun listWorkflowExecutions(request: ListWorkflowExecutionsRequest, response: StreamObserver<ListWorkflowExecutionsResponse>) {
            listed += request
            refusal(request.namespace)?.let { return response.onError(it.asRuntimeException()) }
            if (busyFor > 0) {
                busyFor--
                return response.onError(Status.RESOURCE_EXHAUSTED.withDescription("namespace rate limit exceeded").asRuntimeException())
            }
            val query = QUERY.matchEntire(request.query)
                ?: return response.onError(Status.INVALID_ARGUMENT.withDescription("invalid query: ${request.query}").asRuntimeException())
            val type = query.groupValues[1].replace("''", "'")
            val from = query.groupValues[2].ifEmpty { null }?.let(Instant::parse)
            val before = query.groupValues[3].ifEmpty { null }?.let(Instant::parse)
            val found = executions
                .filter { it.namespace == request.namespace && it.workflowType == type }
                .filter { (from == null || it.started >= from) && (before == null || it.started < before) }
                .sortedWith(compareByDescending<Execution> { it.started }.thenBy { it.runId })
            val offset = if (request.nextPageToken.isEmpty) 0 else request.nextPageToken.toStringUtf8().toInt()
            val size = minOf(if (request.pageSize > 0) request.pageSize else maxPageSize, maxPageSize)
            val page = found.drop(offset).take(size)
            val reply = ListWorkflowExecutionsResponse.newBuilder()
            for (execution in page) {
                reply.addExecutions(
                    WorkflowExecutionInfo.newBuilder()
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(execution.workflowId).setRunId(execution.runId))
                        .setType(WorkflowType.newBuilder().setName(execution.workflowType))
                        .setStartTime(Timestamp.newBuilder().setSeconds(execution.started.epochSecond).setNanos(execution.started.nano))
                        .setHistoryLength(execution.history.eventsCount.toLong()),
                )
            }
            if (offset + page.size < found.size) reply.setNextPageToken(ByteString.copyFromUtf8((offset + page.size).toString()))
            response.onNext(reply.build())
            response.onCompleted()
        }

        override fun getWorkflowExecutionHistory(
            request: GetWorkflowExecutionHistoryRequest,
            response: StreamObserver<GetWorkflowExecutionHistoryResponse>,
        ) {
            read += request
            refusal(request.namespace)?.let { return response.onError(it.asRuntimeException()) }
            val execution = executions.firstOrNull {
                it.namespace == request.namespace && it.workflowId == request.execution.workflowId && it.runId == request.execution.runId
            }
            if (execution == null || execution.runId in gone) {
                return response.onError(Status.NOT_FOUND.withDescription("workflow execution not found").asRuntimeException())
            }
            val offset = if (request.nextPageToken.isEmpty) 0 else request.nextPageToken.toStringUtf8().toInt()
            val events = execution.history.eventsList.drop(offset).take(historyPageSize)
            val reply = GetWorkflowExecutionHistoryResponse.newBuilder().setHistory(History.newBuilder().addAllEvents(events))
            if (offset + events.size < execution.history.eventsCount) {
                reply.setNextPageToken(ByteString.copyFromUtf8((offset + events.size).toString()))
            }
            response.onNext(reply.build())
            response.onCompleted()
        }

        private fun refusal(namespace: String): Status? =
            failure ?: Status.NOT_FOUND.withDescription("Namespace $namespace is not found.").takeIf { namespace !in namespaces }
    }

    companion object {
        private val AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** The queries a fetch writes: a workflow type, and bounds on the start time. */
        private val QUERY = Regex("""WorkflowType = '((?:[^']|'')*)'(?: AND StartTime >= '([^']+)')?(?: AND StartTime < '([^']+)')?""")

        /**
         * [history] as workers of a version would have left it: every completed workflow task
         * from the one numbered [from] on (the first is 0) names [deployment] and [buildId].
         */
        fun ranBy(history: History, deployment: String, buildId: String, from: Int = 0): History {
            var task = 0
            val events = history.eventsList.map { event ->
                if (event.eventType != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED || task++ < from) return@map event
                val attributes = event.workflowTaskCompletedEventAttributes.toBuilder().setWorkerDeploymentName(deployment)
                attributes.deploymentVersionBuilder.setDeploymentName(deployment).setBuildId(buildId)
                event.toBuilder().setWorkflowTaskCompletedEventAttributes(attributes).build()
            }
            return History.newBuilder().addAllEvents(events).build()
        }
    }
}
