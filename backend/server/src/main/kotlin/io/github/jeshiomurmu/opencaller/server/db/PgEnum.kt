package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.postgresql.util.PGobject

/** A Kotlin enum stored in a PostgreSQL `ENUM` type; [sqlValue] is the label used in `V1__init.sql`. */
interface PgEnumValue {
    val sqlValue: String
}

/** `visibility` (docs/architecture.md §4.7): who sees a user's name in live caller ID. */
enum class Visibility(
    override val sqlValue: String,
) : PgEnumValue {
    EVERYONE("everyone"),
    REGISTERED_ONLY("registered_only"),
    NOBODY("nobody"),
}

/** `report_label`. */
enum class ReportLabel(
    override val sqlValue: String,
) : PgEnumValue {
    SPAM("spam"),
    NOT_SPAM("not_spam"),
}

/** `review_status`. */
enum class ReviewStatus(
    override val sqlValue: String,
) : PgEnumValue {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
}

/** A column of the PostgreSQL enum type [sqlType] mapped to [E]. */
inline fun <reified E> Table.pgEnum(
    name: String,
    sqlType: String,
): Column<E> where E : Enum<E>, E : PgEnumValue =
    customEnumeration(
        name = name,
        sql = sqlType,
        fromDb = { value -> enumValues<E>().first { it.sqlValue == value.toString() } },
        toDb = { value ->
            PGobject().apply {
                type = sqlType
                this.value = value.sqlValue
            }
        },
    )
