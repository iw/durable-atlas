package io.github.iw.durableatlas.analyser.discovery

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.ir.EntryPointKind

/** What the analysed sources define for Temporal: workflow types and activity interfaces. */
data class Discovery(
    /** Ordered by workflow type name, then by interface name. */
    val workflows: List<WorkflowType>,
    /** Ordered by interface name. Those declared in the Java files of the sources are among them. */
    val activityInterfaces: List<ActivityInterface>,
    /** Declarations the Temporal SDK would reject or that could not be fully resolved. */
    val warnings: List<String>,
)

/** A workflow type: the interface that declares its workflow method, and the classes that implement it. */
data class WorkflowType(
    /**
     * The workflow type name as registered with Temporal. A class that runs for every workflow
     * type without an implementation of its own has no such name, and is called `dynamic-`
     * followed by its simple name.
     */
    val name: String,
    /** The `@WorkflowInterface` that registers the workflow method, or the SDK's `DynamicWorkflow`. */
    val declaration: ClassDecl,
    /** The workflow method and every handler the interface declares or inherits. */
    val entryPoints: List<WorkflowEntryPoint>,
    /** Concrete classes of the sources that implement the interface, ordered by name. */
    val implementations: List<WorkflowImplementation>,
)

/** A method through which a workflow is entered. */
data class WorkflowEntryPoint(
    val kind: EntryPointKind,
    /** Workflow type, signal, query or update name as registered with Temporal. */
    val name: String,
    /** The interface method that carries the annotation. */
    val method: FunctionDecl,
)

data class WorkflowImplementation(
    val declaration: ClassDecl,
    /** The entry points of the workflow type, each with the function of this class that runs for it. */
    val entryPoints: List<ImplementedEntryPoint>,
    /**
     * Handlers the class also registers because it implements further workflow interfaces that
     * are not supertypes of the workflow type's interface.
     */
    val additionalEntryPoints: List<ImplementedEntryPoint>,
)

data class ImplementedEntryPoint(
    val entryPoint: WorkflowEntryPoint,
    /**
     * The function that runs: declared by the class or inherited from a superclass, or the
     * interface method itself if it has a body. `null` if none was found.
     */
    val function: FunctionDecl?,
)

/** An `@ActivityInterface` and the activity types it registers. */
data class ActivityInterface(
    val declaration: ClassDecl,
    /** The interface's `namePrefix`; empty if it has none. */
    val namePrefix: String,
    /** Ordered by activity type name. */
    val activities: List<ActivityType>,
    /** Concrete classes of the sources that implement the interface, ordered by name. */
    val implementations: List<ClassDecl>,
)

data class ActivityType(
    /** The activity type name as registered with Temporal. */
    val name: String,
    /** The interface method. */
    val method: FunctionDecl,
)
