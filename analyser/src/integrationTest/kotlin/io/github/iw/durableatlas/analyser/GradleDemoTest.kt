package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.ir.ModelMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Graph extraction on a model of the demo project extracted through its own Gradle build. */
class GradleDemoTest : DemoContract(ModelMode.GRADLE) {
    @Test
    fun `the order workflow belongs to the module and the mode the expected document names`() {
        assertEquals(expected.module, actual.module)
        assertEquals(expected.modelMode, actual.modelMode)
        assertEquals(expected.commit, actual.commit)
    }
}
