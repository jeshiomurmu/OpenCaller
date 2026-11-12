package io.github.jeshiomurmu.opencaller.feature.voip

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantMessages
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomSession
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The assistant's live screen in LiveKit (ADR 0010): joins the call's room without subscribing to any audio and
 * publishes no track (the server's token doesn't allow it); only data messages on the assistant topic flow.
 */
class LiveKitAssistantRoom
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AssistantRoomClient {
        // LiveKit throws many unrelated exception types on a failed connect; each means "couldn't join".
        @Suppress("TooGenericExceptionCaught")
        override suspend fun connect(
            url: String,
            token: String,
        ): AppResult<AssistantRoomSession> {
            val room = LiveKit.create(appContext = context.applicationContext)
            return try {
                room.connect(url, token, ConnectOptions(autoSubscribe = false))
                AppResult.Success(Session(room))
            } catch (e: CancellationException) {
                room.release()
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't join the assistant's room")
                room.release()
                AppResult.Failure(AppError.Network)
            }
        }

        private class Session(
            private val room: Room,
        ) : AssistantRoomSession {
            override val messages: Flow<ByteArray> =
                callbackFlow {
                    val job =
                        launch {
                            room.events.events.collect { event ->
                                when {
                                    event is RoomEvent.DataReceived && event.topic == AssistantMessages.TOPIC -> trySend(event.data)
                                    event is RoomEvent.Disconnected -> close()
                                }
                            }
                        }
                    awaitClose { job.cancel() }
                }

            override suspend fun send(payload: ByteArray): AppResult<Unit> =
                room.localParticipant
                    .publishData(payload, topic = AssistantMessages.TOPIC)
                    .fold({ AppResult.Success(Unit) }, { e ->
                        Timber.w(e, "Couldn't send to the assistant")
                        AppResult.Failure(AppError.Network)
                    })

            override fun close() {
                room.disconnect()
                room.release()
            }
        }
    }
