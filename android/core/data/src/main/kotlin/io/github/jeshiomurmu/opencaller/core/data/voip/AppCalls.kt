package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.AppCall
import io.github.jeshiomurmu.opencaller.core.model.AppCallRoom
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.RoomTokenRequestDto
import io.github.jeshiomurmu.opencaller.core.network.StartCallDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.VoipSettingsDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives app-call pushes. Implemented by `feature:voip`; builds without app calls (F-Droid) have none, and their
 * call pushes are ignored.
 */
interface AppCallHandler {
    /** Someone calls: ring. */
    fun onInvite(call: AppCall)

    /** The other party answered, declined, cancelled or hung up. */
    fun onUpdate(call: AppCall)
}

/** What a party does to an app call. */
enum class AppCallAction(
    internal val path: String,
) {
    ACCEPT("accept"),
    DECLINE("decline"),
    CANCEL("cancel"),
    END("end"),
}

/** App-to-app calls on the server: invites, room tokens and reachability (docs/prd.md F-CM-09, ADR 0007). */
@Singleton
class AppCallRepository
    @Inject
    constructor(
        private val api: VoipApi,
        private val clock: Clock,
    ) {
        /** Calls [number]: the invite and the room the caller joins at once. */
        suspend fun start(number: PhoneNumber): AppResult<Pair<AppCall, AppCallRoom>> =
            apiCall { api.startCall(StartCallDto(number.e164)) }.flatMap { session ->
                call(session.inviteId).map { it to session.toRoom() }
            }

        /** Call [inviteId] as this user sees it now. */
        suspend fun call(inviteId: String): AppResult<AppCall> = apiCall { api.call(inviteId) }.map { it.toModel(clock.nowMillis()) }

        /** A token for call [inviteId]'s room (the callee asks once it answers). */
        suspend fun room(inviteId: String): AppResult<AppCallRoom> =
            apiCall { api.roomToken(RoomTokenRequestDto(inviteId)) }.map { it.toRoom(inviteId) }

        /** Applies [action] to call [inviteId]; the other party gets a push. */
        suspend fun act(
            inviteId: String,
            action: AppCallAction,
        ): AppResult<AppCall> = apiCall { api.act(inviteId, action.path) }.map { it.toModel(clock.nowMillis()) }

        /** Whether app calls and Flash messages reach this user. */
        suspend fun reachable(): AppResult<Boolean> = apiCall { api.settings() }.map { it.reachable }

        /** Turns reachability on or off. */
        suspend fun setReachable(reachable: Boolean): AppResult<Boolean> =
            apiCall { api.updateSettings(VoipSettingsDto(reachable)) }.map { it.reachable }
    }
