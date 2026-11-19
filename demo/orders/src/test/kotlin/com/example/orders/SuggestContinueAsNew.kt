package com.example.orders

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.temporal.api.enums.v1.EventType
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse

/**
 * Makes every workflow task look as if the server had suggested to continue as new.
 *
 * The in-memory test service never makes that suggestion, however long a history gets. This
 * sets the flag on the workflow task events on their way to the worker, so the worker acts on
 * it; the history the service keeps is not changed.
 */
object SuggestContinueAsNew : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        object : SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val listener = object : SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onMessage(message: RespT) {
                        @Suppress("UNCHECKED_CAST")
                        val seen = if (message is PollWorkflowTaskQueueResponse) suggest(message) as RespT else message
                        super.onMessage(seen)
                    }
                }
                super.start(listener, headers)
            }
        }

    private fun suggest(task: PollWorkflowTaskQueueResponse): PollWorkflowTaskQueueResponse {
        val history = task.history.toBuilder()
        history.eventsBuilderList
            .filter { it.eventType == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED }
            .forEach { it.workflowTaskStartedEventAttributesBuilder.suggestContinueAsNew = true }
        return task.toBuilder().setHistory(history).build()
    }
}
