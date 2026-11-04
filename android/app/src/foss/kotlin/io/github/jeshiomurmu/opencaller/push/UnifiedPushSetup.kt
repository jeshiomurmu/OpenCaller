package io.github.jeshiomurmu.opencaller.push

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject

/**
 * UnifiedPush in the foss build: a distributor app of the user's choice (ntfy, NextPush, …) delivers Web Push
 * encrypted for this phone. The distributor is picked once, with an activity (the user's default one, or the one
 * already used); then the app registers with the server's VAPID key. Without a distributor, pending items are still
 * fetched whenever the app opens.
 */
class UnifiedPushSetup
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PushSetup {
        override fun start(
            features: ServerFeatures,
            activity: Activity?,
        ) {
            val vapid = features.webPushVapidKey ?: return
            if (UnifiedPush.getAckDistributor(context) != null) {
                UnifiedPush.register(context, vapid = vapid)
                return
            }
            activity?.runOnUiThread {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok -> if (ok) UnifiedPush.register(context, vapid = vapid) }
            }
        }
    }
