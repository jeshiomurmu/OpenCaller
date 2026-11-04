package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.deviceId
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.flash.FlashService
import io.github.jeshiomurmu.opencaller.server.flash.SendFlashRequest
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationService
import io.github.jeshiomurmu.opencaller.server.push.PushRegistration
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.video.SubmitVideoRequest
import io.github.jeshiomurmu.opencaller.server.video.VideoCallerIdService
import io.github.jeshiomurmu.opencaller.server.video.VideoLinksRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.UUID

/** The Phase 11 services, for [voipRoutes]. */
class VoipRouteServices(
    val push: PushService,
    val notifications: NotificationService,
    val voip: VoipService,
    val flash: FlashService,
    val video: VideoCallerIdService,
)

/**
 * Push endpoints, notifications, app calls, Flash messages and video caller ID (ADR 0007). Everything needs an
 * access token except `POST /v1/videos/links`, which serves the clip ids found in (public) bucket entries.
 */
fun Route.voipRoutes(services: VoipRouteServices) {
    post("/v1/videos/links") {
        val request = call.receive<VideoLinksRequest>()
        call.noStore().respond(services.video.links(request, call.request.origin.remoteAddress))
    }
    authenticate(ACCESS_AUTH) {
        pushRoutes(services)
        callRoutes(services.voip)
        post("/v1/flash") { call.noStore().respond(services.flash.send(call.userId(), call.receive<SendFlashRequest>())) }
        videoRoutes(services.video)
    }
}

private fun Route.pushRoutes(services: VoipRouteServices) {
    put("/v1/push") {
        services.push.register(call.userId(), call.deviceId(), call.receive<PushRegistration>())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/v1/push") {
        services.push.unregister(call.userId(), call.deviceId())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/notifications") { call.noStore().respond(services.notifications.pending(call.userId())) }
    get("/v1/notifications/{id}") { call.noStore().respond(services.notifications.item(call.userId(), call.uuid("id"))) }
}

private fun Route.callRoutes(voip: VoipService) {
    post("/v1/voip/calls") { call.noStore().respond(voip.start(call.userId(), call.receive<StartCallRequest>())) }
    get("/v1/voip/calls/{id}") { call.noStore().respond(voip.view(call.userId(), call.uuid("id"))) }
    post("/v1/voip/calls/{id}/{action}") {
        val action =
            CallAction.entries.firstOrNull { it.name.equals(call.parameters["action"], ignoreCase = true) }
                ?: throw ApiProblem(HttpStatusCode.NotFound, "not_found", "Unknown call action")
        call.noStore().respond(voip.act(call.userId(), call.uuid("id"), action))
    }
    post("/v1/voip/token") {
        val request = call.receive<RoomTokenRequest>()
        call.noStore().respond(voip.token(call.userId(), parseUuid(request.inviteId)))
    }
    get("/v1/voip/settings") { call.noStore().respond(voip.settings(call.userId())) }
    put("/v1/voip/settings") { call.noStore().respond(voip.updateSettings(call.userId(), call.receive<VoipSettings>())) }
}

private fun Route.videoRoutes(video: VideoCallerIdService) {
    get("/v1/me/video") { call.noStore().respond(video.mine(call.userId())) }
    post("/v1/me/video/upload") { call.noStore().respond(video.upload(call.userId())) }
    post("/v1/me/video") { call.noStore().respond(video.submit(call.userId(), call.receive<SubmitVideoRequest>())) }
    delete("/v1/me/video") {
        video.delete(call.userId())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/videos/{id}/report") {
        video.report(call.userId(), call.uuid("id"))
        call.respond(HttpStatusCode.NoContent)
    }
}

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun parseUuid(value: String?): UUID =
    value?.takeIf(UUID_PATTERN::matches)?.let(UUID::fromString) ?: throw ApiProblem(HttpStatusCode.NotFound, "not_found", "Not found")

private fun ApplicationCall.uuid(name: String): UUID = parseUuid(parameters[name])

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }
