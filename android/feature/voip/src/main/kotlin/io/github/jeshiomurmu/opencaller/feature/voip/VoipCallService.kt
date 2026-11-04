package io.github.jeshiomurmu.opencaller.feature.voip

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps an app call in the foreground (type `phoneCall`, plus `microphone` once the microphone is allowed) and its
 * notification up to date; stops itself when the call ends. Notification actions (Decline, Hang up) come here.
 */
@AndroidEntryPoint
class VoipCallService : Service() {
    @Inject
    lateinit var controller: AppCallController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notifications = VoipNotifications(this)
        val current = controller.state.value
        if (current == null || !current.isLive) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(VoipNotifications.NOTIFICATION_ID, notifications.build(current), foregroundTypes())
        if (intent?.action == ACTION_HANG_UP) controller.hangUp()
        if (!watching) {
            watching = true
            scope.launch {
                controller.state.collect { ui ->
                    if (ui == null || !ui.isLive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notifications.show(ui)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // The microphone type needs the permission; before it is granted (a call ringing in), phoneCall alone.
    private fun foregroundTypes(): Int {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
    }

    /** Actions. */
    companion object {
        /** Decline, cancel or hang up. */
        const val ACTION_HANG_UP = "io.github.jeshiomurmu.opencaller.voip.HANG_UP"
    }
}
