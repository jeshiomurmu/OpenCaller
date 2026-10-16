package io.github.jeshiomurmu.opencaller.core.model

/** Label a user gives a number in a report. */
enum class ReportLabel {
    /** The number is spam. */
    SPAM,

    /** The number was wrongly flagged as spam. */
    NOT_SPAM,
}

/** Upload state of an item queued for sync. */
enum class SyncState {
    /** Waiting to be sent. */
    PENDING,

    /** Being sent now. */
    IN_FLIGHT,

    /** Sent successfully. */
    DONE,

    /** Gave up after the maximum number of attempts. */
    FAILED,
}

/** Role of a member in a family group. */
enum class FamilyRole {
    /** Created the group and manages members. */
    OWNER,

    /** Joined the group by invitation. */
    MEMBER,
}
