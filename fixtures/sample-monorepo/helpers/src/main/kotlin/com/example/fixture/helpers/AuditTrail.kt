package com.example.fixture.helpers

import com.example.fixture.activities.NotificationActivities

/** Helper interface with exactly one implementation in the repository. */
interface AuditTrail {
    fun record(entry: String)
}

class ActivityAuditTrail(private val notifications: NotificationActivities) : AuditTrail {
    override fun record(entry: String) {
        notifications.audit(entry)
    }
}
