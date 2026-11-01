package io.github.jeshiomurmu.opencaller.feature.messages.ui

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A time offered for a scheduled message. */
enum class PresetKind {
    /** One hour from now. */
    IN_AN_HOUR,

    /** Today at 20:00 (offered until 19:00). */
    TONIGHT,

    /** Tomorrow at 08:00. */
    TOMORROW_MORNING,

    /** Tomorrow at 18:00. */
    TOMORROW_EVENING,
}

/** A preset [kind] at [atMillis]. */
data class SchedulePreset(
    val kind: PresetKind,
    val atMillis: Long,
)

private val TONIGHT = LocalTime.of(20, 0)
private val TONIGHT_UNTIL = LocalTime.of(19, 0)
private val MORNING = LocalTime.of(8, 0)
private val EVENING = LocalTime.of(18, 0)
private const val HOUR_MILLIS = 60 * 60 * 1000L

/** The times offered at [nowMillis] in [zone], soonest first (whole minutes; "in an hour" from now). */
fun schedulePresets(
    nowMillis: Long,
    zone: ZoneId,
): List<SchedulePreset> {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
    val tomorrow = now.toLocalDate().plusDays(1)

    fun at(time: ZonedDateTime) = time.toInstant().toEpochMilli()
    return buildList {
        add(SchedulePreset(PresetKind.IN_AN_HOUR, nowMillis + HOUR_MILLIS))
        if (now.toLocalTime().isBefore(
                TONIGHT_UNTIL,
            )
        ) {
            add(SchedulePreset(PresetKind.TONIGHT, at(now.with(TONIGHT).withSecond(0).withNano(0))))
        }
        add(SchedulePreset(PresetKind.TOMORROW_MORNING, at(tomorrow.atTime(MORNING).atZone(zone))))
        add(SchedulePreset(PresetKind.TOMORROW_EVENING, at(tomorrow.atTime(EVENING).atZone(zone))))
    }
}
