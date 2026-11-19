package com.example.fixture.nexus

import io.nexusrpc.Operation
import io.nexusrpc.Service

/** Nexus service `GreetingService` (the default service name is the interface's simple name). */
@Service
interface GreetingService {
    /** Operation `greet` (the default operation name is the method name). */
    @Operation
    fun greet(name: String): String

    /** Operation `say-goodbye` (explicit name). */
    @Operation(name = "say-goodbye")
    fun farewell(name: String): String
}
