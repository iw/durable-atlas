package com.example.fixture.helpers

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

val DEFAULT_ACTIVITY_TIMEOUT: Duration = Duration.ofSeconds(30)

fun defaultActivityOptions(): ActivityOptions =
    ActivityOptions.newBuilder()
        .setStartToCloseTimeout(DEFAULT_ACTIVITY_TIMEOUT)
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
        .build()

/** Creates a typed activity stub. Must be called from workflow code. */
inline fun <reified T : Any> activityStub(options: ActivityOptions = defaultActivityOptions()): T =
    Workflow.newActivityStub(T::class.java, options)
