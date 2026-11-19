package com.example.fixture.workflows.nexus

import com.example.fixture.nexus.GreetingService
import io.nexusrpc.handler.OperationHandler
import io.nexusrpc.handler.OperationImpl
import io.nexusrpc.handler.ServiceImpl

/** Synchronous handlers for [GreetingService]. */
@ServiceImpl(service = GreetingService::class)
class GreetingServiceImpl {
    @OperationImpl
    fun greet(): OperationHandler<String, String> =
        OperationHandler.sync { _, _, name -> "Hello, $name" }

    @OperationImpl
    fun farewell(): OperationHandler<String, String> =
        OperationHandler.sync { _, _, name -> "Goodbye, $name" }
}
