package io.github.iw.durableatlas.analyser.discovery

/** Fully qualified names of the Temporal SDK types that discovery looks for. */
internal object TemporalTypes {
    const val WORKFLOW_INTERFACE = "io.temporal.workflow.WorkflowInterface"
    const val WORKFLOW_METHOD = "io.temporal.workflow.WorkflowMethod"
    const val SIGNAL_METHOD = "io.temporal.workflow.SignalMethod"
    const val QUERY_METHOD = "io.temporal.workflow.QueryMethod"
    const val UPDATE_METHOD = "io.temporal.workflow.UpdateMethod"
    const val UPDATE_VALIDATOR_METHOD = "io.temporal.workflow.UpdateValidatorMethod"
    const val DYNAMIC_WORKFLOW = "io.temporal.workflow.DynamicWorkflow"

    /** The method of `DynamicWorkflow` that is its workflow method. */
    const val DYNAMIC_EXECUTE = "execute"
    const val ACTIVITY_INTERFACE = "io.temporal.activity.ActivityInterface"
    const val ACTIVITY_METHOD = "io.temporal.activity.ActivityMethod"

    /** Attribute that names a workflow, signal, query, update or activity. */
    const val NAME = "name"

    /** Attribute of `@UpdateValidatorMethod` that names the update it guards. */
    const val UPDATE_NAME = "updateName"

    /** Attribute of `@ActivityInterface` that is put in front of default activity names. */
    const val NAME_PREFIX = "namePrefix"
}
