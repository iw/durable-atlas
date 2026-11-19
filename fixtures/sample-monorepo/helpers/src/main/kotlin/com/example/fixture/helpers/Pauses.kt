package com.example.fixture.helpers

import io.temporal.workflow.Workflow
import java.time.Duration

/** Helper wrapping a Workflow API call: a five second durable timer. */
fun coolDown() {
    Workflow.sleep(Duration.ofSeconds(5))
}
