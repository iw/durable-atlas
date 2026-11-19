package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.ir.ModelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Lowering on a model extracted through the sample project's own Gradle build. */
class GradleLoweringTest : LoweringContract(ModelMode.GRADLE) {
    @Test
    fun `a function reached through a call belongs to the module that declares it`() {
        val run = backend.functions("com.example.fixture.workflows.inlining.HelperInliningWorkflowImpl")
            .single { it.name == "run" }
        assertEquals(":workflows", run.module)

        val helper = assertNotNull(backend.body(run.id)).let { body ->
            body.statements.filterIsInstance<Call>().first { it.name == "reserveAll" }
        }
        assertEquals(":helpers", assertNotNull(backend.function(assertNotNull(helper.callee).function)).module)
    }
}
