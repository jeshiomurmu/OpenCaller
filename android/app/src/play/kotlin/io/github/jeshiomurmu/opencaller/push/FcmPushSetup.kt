package io.github.jeshiomurmu.opencaller.push

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.BuildConfig
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider

/**
 * FCM in the play build. Firebase is configured from Gradle properties (`opencaller.firebase.*`, no
 * `google-services.json`); without them, or when the server has no FCM, nothing is registered. Only Firebase Cloud
 * Messaging is used, and pushes carry nothing but a random id (docs/rules.md §2).
 */
class FcmPushSetup
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val registrar: PushRegistrar,
        private val startup: Provider<VoipStartup>,
    ) : PushSetup {
        override fun start(
            features: ServerFeatures,
            activity: Activity?,
        ) {
            if (!features.fcm || !initialized()) return
            FirebaseMessaging
                .getInstance()
                .token
                .addOnSuccessListener { token -> startup.get().scope.launch { registrar.onNewEndpoint(PushEndpoint.Fcm(token)) } }
                .addOnFailureListener { Timber.w(it, "No FCM token") }
        }

        private fun initialized(): Boolean {
            val configured = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()
            if (configured && FirebaseApp.getApps(context).isEmpty()) {
                val options =
                    FirebaseOptions
                        .Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build()
                FirebaseApp.initializeApp(context, options)
            }
            return configured
        }
    }
