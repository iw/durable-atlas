package com.example.fixture.activities

class NotificationActivitiesImpl : NotificationActivities {
    override fun email(recipient: String, message: String) {
        // Delivery is out of scope for the fixture.
    }

    override fun sms(recipient: String, message: String) {
        // Delivery is out of scope for the fixture.
    }

    override fun audit(entry: String) {
        // Persistence is out of scope for the fixture.
    }
}
