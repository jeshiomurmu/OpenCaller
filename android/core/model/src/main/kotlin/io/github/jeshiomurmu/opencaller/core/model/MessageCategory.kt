package io.github.jeshiomurmu.opencaller.core.model

/** Smart-inbox category assigned to an SMS by the on-device classifier (docs/architecture.md §4.4). */
enum class MessageCategory {
    /** Messages from people. */
    PERSONAL,

    /** One-time passwords and verification codes. */
    OTP,

    /** Bank and payment transactions. */
    TRANSACTION,

    /** Bills and payment reminders. */
    BILL,

    /** Delivery and shipping updates. */
    DELIVERY,

    /** Marketing messages. */
    PROMOTION,

    /** Unwanted bulk messages. */
    SPAM,

    /** Messages that try to steal credentials or money. */
    PHISHING,
}
