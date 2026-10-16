package io.github.jeshiomurmu.opencaller.logging

import android.annotation.SuppressLint
import android.util.Log
import io.github.jeshiomurmu.opencaller.core.common.LogRedactor
import timber.log.Timber

/**
 * Timber tree that redacts phone numbers (digit runs of 7+) before anything reaches the log
 * (docs/architecture.md §10, invariant 3). Messages, including appended stack traces, go through [LogRedactor].
 */
class RedactingTree(
    private val sink: (priority: Int, tag: String, message: String) -> Unit = ::logcat,
) : Timber.Tree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        sink(priority, tag ?: DEFAULT_TAG, LogRedactor.redact(message))
    }

    private companion object {
        const val DEFAULT_TAG = "OpenCaller"
    }
}

// The one place allowed to write to logcat directly: the message has already been redacted.
@SuppressLint("LogNotTimber")
private fun logcat(
    priority: Int,
    tag: String,
    message: String,
) {
    Log.println(priority, tag, message)
}
