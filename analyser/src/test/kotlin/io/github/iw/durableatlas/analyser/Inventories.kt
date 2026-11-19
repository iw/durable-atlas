package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.ir.ModelMode

/** A commit id that stays the same from one test run to the next. */
const val FIXED_COMMIT = "0000000000000000000000000000000000000000"

/** Sample repositories get a new commit id every time they are created; golden files need a fixed one. */
fun Inventory.withFixedCommit(): Inventory = copy(commit = FIXED_COMMIT)

/** What is left of an inventory when the differences between Gradle and degraded models are taken out. */
fun Inventory.withoutModules(): Inventory =
    copy(
        modelMode = ModelMode.GRADLE,
        workflows = workflows.map { workflow ->
            workflow.copy(
                module = null,
                implementations = workflow.implementations.map { it.copy(module = null) },
            )
        },
        activityInterfaces = activityInterfaces.map { activityInterface ->
            activityInterface.copy(
                module = null,
                implementations = activityInterface.implementations.map { it.copy(module = null) },
            )
        },
    )
