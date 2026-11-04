package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.flash.FlashService
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationService
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.video.VideoCallerIdService

/** The Phase 11 services, for [voipRoutes]. */
class VoipRouteServices(
    val push: PushService,
    val notifications: NotificationService,
    val voip: VoipService,
    val flash: FlashService,
    val video: VideoCallerIdService,
)
