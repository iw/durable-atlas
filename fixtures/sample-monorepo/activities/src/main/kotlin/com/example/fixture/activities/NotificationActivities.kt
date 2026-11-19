package com.example.fixture.activities

import io.temporal.activity.ActivityInterface

@ActivityInterface(namePrefix = "Notify")
interface NotificationActivities {
    /** Activity type `NotifyEmail`. */
    fun email(recipient: String, message: String)

    /** Activity type `NotifySms`. */
    fun sms(recipient: String, message: String)

    /** Activity type `NotifyAudit`. */
    fun audit(entry: String)
}
