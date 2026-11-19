package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.walk

/** What one of the options of an activity is set to, as far as the code says. */
internal sealed interface Setting<out T> {
    /** The options leave it alone: what lies under them shows through. */
    data object Unset : Setting<Nothing>

    data class Known<T>(val value: T) : Setting<T>

    /** It is set to something the code does not say, or nothing says whether it is set. */
    data object Unknown : Setting<Nothing>
}

/** The value of a setting, where the code says it. */
internal val <T> Setting<T>.known: T? get() = (this as? Setting.Known)?.value

/**
 * The options of an activity that a graph shows: its task queue, and the two timeouts after
 * which the caller gives up.
 */
internal data class ActivitySettings(
    val taskQueue: Setting<String> = Setting.Unset,
    val scheduleToClose: Setting<String> = Setting.Unset,
    val startToClose: Setting<String> = Setting.Unset,
) {
    /**
     * The time after which the caller gives up: schedule-to-close before start-to-close. Where
     * nothing says whether there is a schedule-to-close timeout, nothing says which of the two
     * it is.
     */
    val timeout: String?
        get() = when (scheduleToClose) {
            is Setting.Known -> scheduleToClose.value
            Setting.Unset -> startToClose.known
            Setting.Unknown -> null
        }

    /**
     * These with [override] laid over them the way the SDK merges options: whatever the
     * override sets wins, one option at a time.
     */
    fun under(override: ActivitySettings): ActivitySettings =
        ActivitySettings(
            override.taskQueue.over(taskQueue),
            override.scheduleToClose.over(scheduleToClose),
            override.startToClose.over(startToClose),
        )

    /** What is certain if either these or [other] hold: what both say alike. */
    fun orElse(other: ActivitySettings): ActivitySettings =
        ActivitySettings(
            taskQueue.orElse(other.taskQueue),
            scheduleToClose.orElse(other.scheduleToClose),
            startToClose.orElse(other.startToClose),
        )

    companion object {
        val NONE = ActivitySettings()
        val UNKNOWN = ActivitySettings(Setting.Unknown, Setting.Unknown, Setting.Unknown)
    }
}

private fun <T> Setting<T>.over(base: Setting<T>): Setting<T> = if (this == Setting.Unset) base else this

private fun <T> Setting<T>.orElse(other: Setting<T>): Setting<T> = if (this == other) this else Setting.Unknown

/**
 * The options a workflow has for its activities apart from those of each stub, for activities
 * or for local activities, as the SDK keeps them:
 *
 * - [default] is what a stub is given that is made without options of its own. The stub takes
 *   it when it is made; what is set afterwards does not reach it.
 * - [byType] is laid over the options of a stub, its own or the default, for the activity type
 *   it names. A stub that was not made with such a map of its own looks at this one each time
 *   it is called, so it counts as it stands where the activity is run.
 */
internal data class ActivityDefaults(
    val default: ActivitySettings = ActivitySettings.NONE,
    val byType: Map<String, ActivitySettings> = emptyMap(),
    /** What holds for the activity types [byType] does not name: nothing, unless a map was applied that cannot be read. */
    val otherTypes: ActivitySettings = ActivitySettings.NONE,
) {
    fun forType(type: String): ActivitySettings = byType[type] ?: otherTypes

    /** After `setDefaultActivityOptions`: what was set before stays where the new options say nothing. */
    fun withDefault(settings: ActivitySettings): ActivityDefaults = copy(default = default.under(settings))

    /**
     * After `applyActivityOptions`: the options of a type that has some already are merged.
     *
     * @param types the options by activity type; `null` for a map that cannot be read, which may hold anything for any type
     */
    fun withTypes(types: Map<String, ActivitySettings>?): ActivityDefaults =
        if (types == null) {
            copy(byType = byType.mapValues { ActivitySettings.UNKNOWN }, otherTypes = ActivitySettings.UNKNOWN)
        } else {
            copy(byType = byType + types.mapValues { (type, settings) -> forType(type).under(settings) })
        }

    /** What is certain if either these or [other] hold. */
    fun orElse(other: ActivityDefaults): ActivityDefaults =
        ActivityDefaults(
            default.orElse(other.default),
            (byType.keys + other.byType.keys).associateWith { forType(it).orElse(other.forType(it)) },
            otherTypes.orElse(other.otherTypes),
        )
}

/** The options a workflow has for activities and for local activities at one moment. */
internal data class ActivityEnvironment(
    val plain: ActivityDefaults = ActivityDefaults(),
    val local: ActivityDefaults = ActivityDefaults(),
) {
    fun of(local: Boolean): ActivityDefaults = if (local) this.local else plain

    fun with(local: Boolean, change: (ActivityDefaults) -> ActivityDefaults): ActivityEnvironment =
        if (local) copy(local = change(this.local)) else copy(plain = change(plain))

    /** What is certain if either these or [other] hold. */
    fun orElse(other: ActivityEnvironment): ActivityEnvironment = ActivityEnvironment(plain.orElse(other.plain), local.orElse(other.local))
}

/** What a call does to the options a workflow has for its activities. */
internal class OptionsChange(private val local: Boolean, private val change: (ActivityDefaults) -> ActivityDefaults) {
    /**
     * The options after the call.
     *
     * @param certain whether the call is sure to have run by the time what follows runs. If it
     *   is not, only what holds with and without it is left
     */
    fun on(environment: ActivityEnvironment, certain: Boolean): ActivityEnvironment {
        val changed = environment.with(local, change)
        return if (certain) changed else environment.orElse(changed)
    }
}

/** The options a workflow has for its activities where a call is made, for whoever reads the call. */
internal interface OptionsInForce {
    /** What a stub that was made without options of its own was given when it was made: by [creation], reached the way it says. */
    fun defaults(creation: StubCreation, local: Boolean): ActivitySettings

    /** What is laid over the options of a stub for the activity type [type], as it stands where the call is made. */
    fun forType(type: String, local: Boolean): ActivitySettings
}

/** Reads the options of activities out of the expressions that make them. */
internal class ActivityOptionsReader(private val tracer: ValueTracer, private val constants: Constants) {
    private val maps = MapLiterals(tracer, constants)

    /**
     * What the options [code] evaluates to set. An option that the builder is not seen to set
     * is what the builder began with: unset if it is made from nothing where the code can be
     * read, what other options set if it is made from those, with `newBuilder(other)` or
     * `other.toBuilder()`, and unknown if the options come from somewhere this reading does
     * not see, or if their builder may have been given more than the code that was read shows.
     */
    fun settings(code: Code, scope: Scope, local: Boolean): ActivitySettings = settings(Traced(code, scope), local, MAX_COPIES)

    /** @param copies how many more times options made from other options are followed to those */
    private fun settings(given: Traced, local: Boolean, copies: Int): ActivitySettings {
        val options = tracer.origin(given.code, given.scope, settled = false)
        val type = if (local) TemporalApi.LOCAL_ACTIVITY_OPTIONS else TemporalApi.ACTIVITY_OPTIONS
        val builder = if (local) TemporalApi.LOCAL_ACTIVITY_OPTIONS_BUILDER else TemporalApi.ACTIVITY_OPTIONS_BUILDER
        val start = BuilderChains.start(options, type, builder, tracer)
        val from = start?.from
        val began = when {
            start == null -> if (builtFromNothing(options.code, type, builder)) ActivitySettings.NONE else ActivitySettings.UNKNOWN
            start.open -> ActivitySettings.UNKNOWN
            // A builder made from no options at all, or from `null`, begins with nothing.
            from == null || isNull(from.code, from.scope) -> ActivitySettings.NONE
            copies == 0 -> ActivitySettings.UNKNOWN
            else -> settings(from, local, copies - 1)
        }
        val set = Builders.setters(options.code, builder)
        fun duration(setter: String, otherwise: Setting<String>): Setting<String> {
            val argument = set[setter] ?: return otherwise
            return constants.duration(argument.value, options.scope)?.let { Setting.Known(it.toString()) } ?: Setting.Unknown
        }
        val taskQueue = set["setTaskQueue"]?.let { queue -> constants.text(queue, options.scope)?.let { Setting.Known(it) } ?: Setting.Unknown }
        val built = ActivitySettings(
            // Local activities run where the workflow does: they have no task queue.
            taskQueue = taskQueue ?: if (local) Setting.Unset else began.taskQueue,
            scheduleToClose = duration("setScheduleToCloseTimeout", began.scheduleToClose),
            startToClose = duration("setStartToCloseTimeout", began.startToClose),
        )
        return merged(built, options, builder, local, copies)
    }

    /**
     * [built] with what the builder was told to merge in, where it was: other options laid
     * over its own, option by option, with `mergeActivityOptions`. That is followed where it is
     * the last thing the builder is told before it builds; anywhere else nothing is known of
     * what the options hold in the end.
     */
    private fun merged(built: ActivitySettings, options: Traced, builder: String, local: Boolean, copies: Int): ActivitySettings {
        fun merges(call: Call): Boolean = call.callee?.function?.let { it.owner == builder && it.simpleName == MERGE } == true
        val merges = options.code.walk().filterIsInstance<Call>().filter(::merges).toList()
        if (merges.isEmpty()) return built
        // `….mergeActivityOptions(other).build()`: the call that builds is made on the one that merges.
        val builds = (options.code as? Call)?.takeIf { it.callee?.function?.owner == builder }
        val last = (builds?.receiver as? Call)?.takeIf { it === merges.singleOrNull() }
        val override = last?.arguments?.singleOrNull()?.value ?: return ActivitySettings.UNKNOWN
        return when {
            isNull(override, options.scope) -> built
            copies == 0 -> ActivitySettings.UNKNOWN
            else -> built.under(settings(Traced(override, options.scope), local, copies - 1))
        }
    }

    /**
     * Whether the options [made] are built by a builder that nothing is known to have been
     * handed other options: the builder of a function this reading does not know, such as one
     * that takes the setters as a lambda. What it does not set is then taken to be unset.
     */
    private fun builtFromNothing(made: Code, type: String, builder: String): Boolean {
        val called = made.walk().filterIsInstance<Call>().mapNotNull { it.callee?.function }.toList()
        val built = called.any { it.owner == builder }
        val copied = called.any { it.owner == type && (it.simpleName == "toBuilder" || (it.simpleName == "newBuilder" && it.signature != "()")) }
        // A function that is handed options, or called on some, may build on what those hold.
        val handed = (made as? Call)?.let { call -> call.receiverType?.name == type || call.arguments.any { typeOf(it.value) == type } } == true
        return built && !copied && !handed
    }

    /** The type of the value of [code], where the code says it. */
    private fun typeOf(code: Code): String? =
        when (code) {
            is Call -> code.type?.name
            is PropertyRead -> code.type?.name
            is LocalRead -> code.type?.name
            else -> null
        }

    /**
     * The options that the map [code] evaluates to holds for each activity type; `null` if the
     * map is not written out where the code can be read.
     */
    fun byType(code: Code, scope: Scope, local: Boolean): Map<String, ActivitySettings>? {
        val entries = maps.entries(code, scope) ?: return null
        val found = LinkedHashMap<String, ActivitySettings>()
        // A key written twice holds what is written last.
        for ((type, options) in entries) found[type] = settings(options.code, options.scope, local)
        return found
    }

    /** Whether [code] is `null` written out: options that are not given. */
    fun isNull(code: Code, scope: Scope): Boolean = (tracer.origin(code, scope, settled = false).code as? Literal)?.constant == Constant.Null

    private companion object {
        /** How many times options made from other options are followed to those. */
        const val MAX_COPIES = 8

        /** The function of a builder that lays other options over what the builder holds. */
        const val MERGE = "mergeActivityOptions"
    }
}
