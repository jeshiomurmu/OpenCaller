package io.github.jeshiomurmu.opencaller.feature.incall.calls

import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRoute
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import timber.log.Timber

/**
 * Call audio through the in-call service (developer.android.com, `InCallService`): on Android 14+ the call endpoint
 * API (`onCallEndpointChanged`, `onAvailableCallEndpointsChanged`, `onMuteStateChanged`,
 * `requestCallEndpointChange`); before that `CallAudioState` and `setAudioRoute`, which Android 14 deprecated.
 * Mute is `setMuted` on every version.
 */
internal class InCallAudio(
    private val service: InCallService,
    private val registry: CallRegistry,
) : AudioControl {
    // Android 14+: the endpoints Telecom offered, by their id (endpoints must come from Telecom, never be made up).
    private val endpoints = LinkedHashMap<String, Any>()

    /** Android 14+: audio now goes to [endpoint]. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun onCurrentEndpoint(endpoint: CallEndpoint) {
        registry.updateAudio { it.copy(current = endpoint.toRoute()) }
    }

    /** Android 14+: audio can go to [available]. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun onAvailableEndpoints(available: List<CallEndpoint>) {
        endpoints.clear()
        available.forEach { endpoints[it.identifier.toString()] = it }
        registry.updateAudio { it.copy(available = available.map { endpoint -> endpoint.toRoute() }) }
    }

    /** The microphone was muted or unmuted (Android 14+ callback; older versions report it in [onLegacyAudioState]). */
    fun onMuted(muted: Boolean) {
        registry.updateAudio { it.copy(muted = muted) }
    }

    /** Before Android 14: the whole audio state at once. */
    fun onLegacyAudioState(state: CallAudioState) {
        val available = LEGACY_ROUTES.filter { (route, _) -> state.supportedRouteMask and route != 0 }
        registry.updateAudio {
            CallAudio(
                current = LEGACY_ROUTES[state.route]?.let { type -> AudioRoute(state.route.toString(), type) },
                available = available.map { (route, type) -> AudioRoute(route.toString(), type) },
                muted = state.isMuted,
            )
        }
    }

    override fun setMuted(muted: Boolean) = service.setMuted(muted)

    override fun route(routeId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestEndpoint(routeId)
        } else {
            routeId.toIntOrNull()?.takeIf(LEGACY_ROUTES::containsKey)?.let { route ->
                @Suppress("DEPRECATION")
                service.setAudioRoute(route)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestEndpoint(routeId: String) {
        val endpoint = endpoints[routeId] as? CallEndpoint ?: return
        service.requestCallEndpointChange(
            endpoint,
            service.mainExecutor,
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit

                override fun onError(error: CallEndpointException) {
                    Timber.w(error, "Audio route change refused (code %d)", error.code)
                }
            },
        )
    }

    private companion object {
        // CallAudioState routes (before Android 14), in the order the route picker lists them.
        val LEGACY_ROUTES =
            linkedMapOf(
                CallAudioState.ROUTE_EARPIECE to AudioRouteType.EARPIECE,
                CallAudioState.ROUTE_SPEAKER to AudioRouteType.SPEAKER,
                CallAudioState.ROUTE_WIRED_HEADSET to AudioRouteType.WIRED_HEADSET,
                CallAudioState.ROUTE_BLUETOOTH to AudioRouteType.BLUETOOTH,
                CallAudioState.ROUTE_STREAMING to AudioRouteType.STREAMING,
            )
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun CallEndpoint.toRoute(): AudioRoute =
    AudioRoute(
        id = identifier.toString(),
        type =
            when (endpointType) {
                CallEndpoint.TYPE_EARPIECE -> AudioRouteType.EARPIECE
                CallEndpoint.TYPE_SPEAKER -> AudioRouteType.SPEAKER
                CallEndpoint.TYPE_BLUETOOTH -> AudioRouteType.BLUETOOTH
                CallEndpoint.TYPE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
                CallEndpoint.TYPE_STREAMING -> AudioRouteType.STREAMING
                else -> AudioRouteType.UNKNOWN
            },
        name = endpointName.toString().takeIf(String::isNotBlank),
    )
