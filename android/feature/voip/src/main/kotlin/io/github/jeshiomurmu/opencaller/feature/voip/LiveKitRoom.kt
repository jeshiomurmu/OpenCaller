package io.github.jeshiomurmu.opencaller.feature.voip

import android.content.Context
import io.github.jeshiomurmu.opencaller.core.model.AppCallRoom
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The call's LiveKit room: audio only (the microphone; no camera, no screen sharing). Audio routing (earpiece,
 * speaker, Bluetooth) belongs to Jetpack Telecom, so LiveKit's own routing is off ([NoAudioHandler]) and it plays on
 * the voice-call stream.
 */
internal class LiveKitRoom(
    private val context: Context,
) {
    private var room: Room? = null

    /** True once the other party is in the room. */
    val hasOtherParty: Boolean get() = room?.remoteParticipants?.isNotEmpty() == true

    /** Joins [access] with the microphone on; room events go to [onEvent] (on [scope]). */
    suspend fun join(
        access: AppCallRoom,
        scope: CoroutineScope,
        onEvent: (RoomEvent) -> Unit,
    ) {
        val created =
            LiveKit.create(
                appContext = context.applicationContext,
                overrides =
                    LiveKitOverrides(
                        audioOptions = AudioOptions(audioOutputType = AudioType.CallAudioType(), audioHandler = NoAudioHandler()),
                    ),
            )
        room = created
        scope.launch { created.events.events.collect(onEvent) }
        created.connect(access.url, access.token)
        created.localParticipant.setMicrophoneEnabled(true)
    }

    /** Mutes or unmutes the microphone. */
    suspend fun setMuted(muted: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(!muted)
    }

    /** Leaves the room and frees WebRTC. */
    fun leave() {
        room?.let {
            it.disconnect()
            it.release()
        }
        room = null
    }
}
