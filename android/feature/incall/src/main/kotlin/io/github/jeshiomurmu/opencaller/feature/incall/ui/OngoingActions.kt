package io.github.jeshiomurmu.opencaller.feature.incall.ui

import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent

/** What the ongoing-call screen can open: the keypad, the audio picker, and a new call. */
internal class OngoingActions(
    val onEvent: (InCallEvent) -> Unit,
    val onKeypad: () -> Unit,
    val onAudio: () -> Unit,
    val onAddCall: () -> Unit,
)
