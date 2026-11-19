package com.example.fixture.helpers

import com.example.fixture.activities.NotificationActivities

/** Extension function on an activity stub; the activity call uses the implicit receiver. */
fun NotificationActivities.notifyOps(message: String) {
    email(OPS_EMAIL, message)
}
