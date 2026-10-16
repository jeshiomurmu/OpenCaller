package io.github.jeshiomurmu.opencaller.core.model

/** Outcome of call screening (docs/architecture.md §4.1). Errors always resolve to [ALLOW] (fail open). */
enum class Decision {
    /** Let the call ring normally. */
    ALLOW,

    /** Reject the call. */
    BLOCK,

    /** Let the call through without ringing. */
    SILENCE,
}

/** Direction of a call. */
enum class Direction {
    /** Call received by the user. */
    INCOMING,

    /** Call placed by the user. */
    OUTGOING,
}
