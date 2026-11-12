package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Optional
import javax.inject.Inject

/** Whether the call assistant can be offered, and if not, why. */
enum class AssistantOffer {
    /** Signed in, the server has assistant numbers and this build has LiveKit. */
    AVAILABLE,

    /** The user isn't signed in (or no server is configured). */
    SIGNED_OUT,

    /** The server has no assistant numbers (`GET /v1/server`). */
    NOT_ON_SERVER,

    /** This build has no LiveKit (F-Droid), so the live screen can't follow a call. */
    NOT_IN_BUILD,
}

/** What the setup screen offers ([ServerAssistantAvailability]; a fake in tests). */
fun interface AssistantAvailability {
    /** The current offer. */
    fun offer(): Flow<AssistantOffer>
}

/** [AssistantAvailability] from the sign-in state, the server's features and this build's LiveKit. */
class ServerAssistantAvailability
    @Inject
    constructor(
        private val registrar: PushRegistrar,
        private val account: AccountStatus,
        room: Optional<AssistantRoomClient>,
    ) : AssistantAvailability {
        private val buildHasLive = room.isPresent

        override fun offer(): Flow<AssistantOffer> =
            combine(registrar.features, account.signedIn) { features, signedIn ->
                when {
                    !signedIn -> AssistantOffer.SIGNED_OUT
                    !features.assistant -> AssistantOffer.NOT_ON_SERVER
                    !buildHasLive -> AssistantOffer.NOT_IN_BUILD
                    else -> AssistantOffer.AVAILABLE
                }
            }
    }
