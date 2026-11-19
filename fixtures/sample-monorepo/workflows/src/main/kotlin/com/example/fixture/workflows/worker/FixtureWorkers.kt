package com.example.fixture.workflows.worker

import com.example.fixture.activities.InventoryActivitiesImpl
import com.example.fixture.activities.NotificationActivitiesImpl
import com.example.fixture.activities.PaymentActivitiesImpl
import com.example.fixture.activities.ShippingActivitiesImpl
import com.example.fixture.helpers.TaskQueues
import com.example.fixture.workflows.controlflow.BranchWorkflowImpl
import com.example.fixture.workflows.controlflow.DynamicFanOutWorkflowImpl
import com.example.fixture.workflows.controlflow.EarlyReturnWorkflowImpl
import com.example.fixture.workflows.controlflow.LoopWorkflowImpl
import com.example.fixture.workflows.controlflow.ScopeFunctionWorkflowImpl
import com.example.fixture.workflows.controlflow.TryCatchWorkflowImpl
import com.example.fixture.workflows.handlers.ApprovalWorkflowImpl
import com.example.fixture.workflows.hardcases.ComputedActivityNameWorkflowImpl
import com.example.fixture.workflows.hardcases.DeepHelperChainWorkflowImpl
import com.example.fixture.workflows.hardcases.DynamicDispatchWorkflowImpl
import com.example.fixture.workflows.hardcases.RecursionWorkflowImpl
import com.example.fixture.workflows.inlining.HelperInliningWorkflowImpl
import com.example.fixture.workflows.nexus.GreetingServiceImpl
import com.example.fixture.workflows.order.OrderWorkflowImpl
import com.example.fixture.workflows.order.ShipmentWorkflowImpl
import com.example.fixture.workflows.primitives.ApplicationFailureWorkflowImpl
import com.example.fixture.workflows.primitives.AwaitWorkflowImpl
import com.example.fixture.workflows.primitives.ContinueAsNewWorkflowImpl
import com.example.fixture.workflows.primitives.GreetingChildWorkflowImpl
import com.example.fixture.workflows.primitives.LocalActivityWorkflowImpl
import com.example.fixture.workflows.primitives.MethodReferenceWorkflowImpl
import com.example.fixture.workflows.primitives.NexusOperationWorkflowImpl
import com.example.fixture.workflows.primitives.ParallelWorkflowImpl
import com.example.fixture.workflows.primitives.ParentWorkflowImpl
import com.example.fixture.workflows.primitives.SagaWorkflowImpl
import com.example.fixture.workflows.primitives.SideEffectWorkflowImpl
import com.example.fixture.workflows.primitives.SignalExternalWorkflowImpl
import com.example.fixture.workflows.primitives.TimerWorkflowImpl
import com.example.fixture.workflows.primitives.TypedActivityWorkflowImpl
import com.example.fixture.workflows.primitives.UntypedActivityWorkflowImpl
import com.example.fixture.workflows.primitives.VersionGateWorkflowImpl
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory

/** Registers every workflow, activity and Nexus service of the fixture on its task queue. */
fun registerFixtureWorkers(factory: WorkerFactory) {
    val fixture = factory.newWorker(TaskQueues.FIXTURE)
    fixture.registerWorkflowImplementationTypes(
        TypedActivityWorkflowImpl::class.java,
        LocalActivityWorkflowImpl::class.java,
        UntypedActivityWorkflowImpl::class.java,
        ParentWorkflowImpl::class.java,
        GreetingChildWorkflowImpl::class.java,
        SignalExternalWorkflowImpl::class.java,
        TimerWorkflowImpl::class.java,
        AwaitWorkflowImpl::class.java,
        ParallelWorkflowImpl::class.java,
        VersionGateWorkflowImpl::class.java,
        ContinueAsNewWorkflowImpl::class.java,
        SideEffectWorkflowImpl::class.java,
        NexusOperationWorkflowImpl::class.java,
        SagaWorkflowImpl::class.java,
        ApplicationFailureWorkflowImpl::class.java,
        MethodReferenceWorkflowImpl::class.java,
        BranchWorkflowImpl::class.java,
        LoopWorkflowImpl::class.java,
        TryCatchWorkflowImpl::class.java,
        EarlyReturnWorkflowImpl::class.java,
        ScopeFunctionWorkflowImpl::class.java,
        DynamicFanOutWorkflowImpl::class.java,
        ApprovalWorkflowImpl::class.java,
        HelperInliningWorkflowImpl::class.java,
        ComputedActivityNameWorkflowImpl::class.java,
        DynamicDispatchWorkflowImpl::class.java,
        RecursionWorkflowImpl::class.java,
        DeepHelperChainWorkflowImpl::class.java,
    )
    fixture.registerNexusServiceImplementation(GreetingServiceImpl())
    registerActivities(fixture)

    val orders = factory.newWorker(TaskQueues.ORDERS)
    orders.registerWorkflowImplementationTypes(OrderWorkflowImpl::class.java)
    registerActivities(orders)

    val shipping = factory.newWorker(TaskQueues.SHIPPING)
    shipping.registerWorkflowImplementationTypes(ShipmentWorkflowImpl::class.java)
    registerActivities(shipping)
}

private fun registerActivities(worker: Worker) {
    worker.registerActivitiesImplementations(
        InventoryActivitiesImpl(),
        PaymentActivitiesImpl(),
        ShippingActivitiesImpl(),
        NotificationActivitiesImpl(),
    )
}

fun main() {
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service)
    val factory = WorkerFactory.newInstance(client)
    registerFixtureWorkers(factory)
    factory.start()
}
