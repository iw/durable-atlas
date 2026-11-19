package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.ir.ModelMode

/** Lowering on a model made without the sample project's build. */
class DegradedLoweringTest : LoweringContract(ModelMode.DEGRADED)
