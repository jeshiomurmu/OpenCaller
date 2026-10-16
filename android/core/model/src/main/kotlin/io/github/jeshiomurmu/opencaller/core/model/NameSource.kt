package io.github.jeshiomurmu.opencaller.core.model

/** Where a displayed name came from (ADR 0001: names never come from other people's address books). */
enum class NameSource {
    /** The user's own contacts on this device. */
    CONTACT,

    /** The number's owner registered it with OTP verification. */
    SELF_REGISTERED,

    /** A business verified by OpenCaller. */
    VERIFIED_BUSINESS,

    /** An official sender-ID registry (SMS headers). */
    SENDER_REGISTRY,

    /** A community caller-type label (e.g. "Delivery"), never a personal name. */
    COMMUNITY_LABEL,

    /** Inferred on this device (e.g. from message templates); never uploaded. */
    DEVICE_INFERENCE,
}
