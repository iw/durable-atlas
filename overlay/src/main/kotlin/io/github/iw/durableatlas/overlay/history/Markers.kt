package io.github.iw.durableatlas.overlay.history

import io.temporal.api.history.v1.HistoryEvent
import io.temporal.internal.history.LocalActivityMarkerUtils
import io.temporal.internal.history.MarkerUtils
import io.temporal.internal.history.VersionMarkerUtils

/**
 * What the markers of the Temporal Java SDK say. A local activity, a version, a side effect and a
 * mutable side effect each leave a `MarkerRecorded` event, told apart by the marker's name; what
 * they are about is in the marker's details, as payloads.
 *
 * The markers of local activities and versions are read with the SDK's own classes. The names
 * of the side effect markers are not public in the SDK; a test holds the copies here against it.
 */
internal object Markers {
    /** Marker of `Workflow.sideEffect`. Its only detail, `data`, is the value. */
    const val SIDE_EFFECT: String = "SideEffect"

    /** Marker of `Workflow.mutableSideEffect`, written when the value changed. */
    const val MUTABLE_SIDE_EFFECT: String = "MutableSideEffect"

    /** Detail of a mutable side effect marker that holds the id given in the code. */
    const val MUTABLE_SIDE_EFFECT_ID: String = "id"

    fun isLocalActivity(event: HistoryEvent): Boolean = LocalActivityMarkerUtils.hasLocalActivityStructure(event)

    /** The activity type of a local activity marker: its detail `type`. */
    fun activityType(event: HistoryEvent): String? =
        detail { LocalActivityMarkerUtils.getActivityTypeName(event.markerRecordedEventAttributes) }

    fun isVersion(event: HistoryEvent): Boolean = VersionMarkerUtils.hasVersionMarkerStructure(event)

    /** The change id of a version marker: its detail `changeId`. */
    fun changeId(event: HistoryEvent): String? =
        detail { VersionMarkerUtils.getChangeId(event.markerRecordedEventAttributes) }

    /** The version a version marker recorded: its detail `version`. */
    fun version(event: HistoryEvent): Int? =
        detail { VersionMarkerUtils.getVersion(event.markerRecordedEventAttributes) }

    /** The id of a mutable side effect marker: its detail `id`. */
    fun mutableSideEffectId(event: HistoryEvent): String? =
        detail { MarkerUtils.getValueFromMarker(event.markerRecordedEventAttributes, MUTABLE_SIDE_EFFECT_ID, String::class.java) }

    /** A detail that is missing or is not what the SDK writes reads as `null`. */
    private fun <T> detail(read: () -> T?): T? =
        try {
            read()
        } catch (_: RuntimeException) {
            null
        }
}
