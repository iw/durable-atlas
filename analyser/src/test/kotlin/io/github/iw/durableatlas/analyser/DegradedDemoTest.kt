package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.ir.ModelMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Graph extraction on a model of the demo project made without its build. */
class DegradedDemoTest : DemoContract(ModelMode.DEGRADED) {
    @Test
    fun `everything belongs to the single module of the model`() {
        assertEquals(setOf(":"), documents.map { it.module }.toSet())
    }
}
