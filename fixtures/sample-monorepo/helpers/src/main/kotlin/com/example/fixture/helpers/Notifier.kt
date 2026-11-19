package com.example.fixture.helpers

import com.example.fixture.activities.NotificationActivities

/** Helper interface with two implementations in the repository. */
interface Notifier {
    fun notify(recipient: String, message: String)
}

class EmailNotifier(private val notifications: NotificationActivities) : Notifier {
    override fun notify(recipient: String, message: String) {
        notifications.email(recipient, message)
    }
}

class SmsNotifier(private val notifications: NotificationActivities) : Notifier {
    override fun notify(recipient: String, message: String) {
        notifications.sms(recipient, message)
    }
}
