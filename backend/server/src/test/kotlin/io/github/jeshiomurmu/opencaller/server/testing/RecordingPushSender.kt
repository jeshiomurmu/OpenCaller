package io.github.jeshiomurmu.opencaller.server.testing

import io.github.jeshiomurmu.opencaller.server.config.LiveKitConfig
import io.github.jeshiomurmu.opencaller.server.config.VoipConfig
import io.github.jeshiomurmu.opencaller.server.push.PushOutcome
import io.github.jeshiomurmu.opencaller.server.push.PushSender
import io.github.jeshiomurmu.opencaller.server.push.PushTarget
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records pushes instead of sending them; [outcome] is what every push returns. */
class RecordingPushSender : PushSender {
    /** One push: where it went, the item id it carried, its TTL and urgency. */
    data class Sent(
        val target: PushTarget,
        val itemId: UUID,
        val ttlSeconds: Int,
        val urgent: Boolean,
    )

    val sent = CopyOnWriteArrayList<Sent>()

    @Volatile
    var outcome = PushOutcome.DELIVERED

    override suspend fun send(
        target: PushTarget,
        itemId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): PushOutcome {
        sent += Sent(target, itemId, ttlSeconds, urgent)
        return outcome
    }

    /** Pushes that went to FCM token [token]. */
    fun to(token: String): List<Sent> = sent.filter { (it.target as? PushTarget.Fcm)?.token == token }
}

/** A LiveKit secret for tests (not a secret). */
const val TEST_LIVEKIT_SECRET = "test-livekit-secret-with-at-least-32-chars"

/** [testConfig] with LiveKit configured. */
fun voipTestConfig() =
    testConfig().copy(voip = VoipConfig(liveKit = LiveKitConfig("ws://livekit.test:7880", "devkey", TEST_LIVEKIT_SECRET)))
