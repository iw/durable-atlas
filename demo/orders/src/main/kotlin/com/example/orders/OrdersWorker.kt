package com.example.orders

import com.example.orders.activities.InventoryActivitiesImpl
import com.example.orders.activities.OrderValidationActivitiesImpl
import com.example.orders.activities.PaymentActivitiesImpl
import com.example.orders.activities.PromotionActivitiesImpl
import com.example.orders.loyalty.LoyaltyServiceHandler
import com.example.orders.shipping.ShipmentWorkflowImpl
import com.example.orders.shipping.ShippingActivitiesImpl
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory

/**
 * Registers the order and shipment workflows with their activities on [ORDERS_TASK_QUEUE], and
 * the loyalty programme's Nexus service on [LOYALTY_TASK_QUEUE].
 */
fun registerWorkers(factory: WorkerFactory) {
    val orders = factory.newWorker(ORDERS_TASK_QUEUE)
    orders.registerWorkflowImplementationTypes(
        OrderWorkflowImpl::class.java,
        ShipmentWorkflowImpl::class.java,
    )
    orders.registerActivitiesImplementations(
        OrderValidationActivitiesImpl(),
        PaymentActivitiesImpl(),
        InventoryActivitiesImpl(),
        PromotionActivitiesImpl(),
        ShippingActivitiesImpl(),
    )

    val loyalty = factory.newWorker(LOYALTY_TASK_QUEUE)
    loyalty.registerNexusServiceImplementation(LoyaltyServiceHandler())
}

/**
 * Runs the workers against a Temporal service on localhost. The service needs a Nexus endpoint
 * named [LOYALTY_ENDPOINT] that targets [LOYALTY_TASK_QUEUE].
 */
fun main() {
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val factory = WorkerFactory.newInstance(WorkflowClient.newInstance(service))
    registerWorkers(factory)
    factory.start()
}
