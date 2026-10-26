package io.github.jeshiomurmu.opencaller.feature.callerid

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.ui.PhoneLinks
import timber.log.Timber
import javax.inject.Inject

/**
 * The after-call card as a screen, for the default phone app (docs/architecture.md §4.3): call back, message, save
 * contact, block, report or not spam. Opened by [AfterCallScreens] only while the phone is unlocked.
 */
@AndroidEntryPoint
class AfterCallActivity : ComponentActivity() {
    @Inject
    lateinit var placer: CallPlacer

    private val viewModel: AfterCallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val number = intent?.getStringExtra(EXTRA_NUMBER)?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)
        if (number == null) {
            finish()
            return
        }
        val links =
            AfterCallLinks(
                onCallBack = { callBack(number) },
                onMessage = { body -> PhoneLinks.message(this, number.e164, body) },
                onSaveContact = { open(saveContact(number)) },
                onReport = { report(number) },
                onClose = ::finish,
            )
        setContent { OpenCallerTheme { AfterCallRoute(viewModel = viewModel, links = links) } }
    }

    // Straight through Telecom when OpenCaller may place calls, else the dial pad with the number filled in.
    private fun callBack(number: PhoneNumber) {
        if (placer.canPlaceCalls() && placer.place(number.e164) is AppResult.Success) {
            finish()
        } else {
            open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.e164, null)).setPackage(packageName))
        }
    }

    private fun saveContact(number: PhoneNumber): Intent =
        Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number.e164)

    // Report opens the app's report sheet, as the after-call notification does. An app always sees its own package.
    @SuppressLint("QueryPermissionsNeeded")
    private fun report(number: PhoneNumber) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.putExtra(CallerIdIntents.EXTRA_REPORT_NUMBER, number.e164)
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        open(launch)
        finish()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app for the after-call action")
        }
    }

    /** Extras and the intent. */
    companion object {
        /** The E.164 number of the call that ended. */
        const val EXTRA_NUMBER = "io.github.jeshiomurmu.opencaller.extra.AFTER_CALL_NUMBER"

        /** Whether the call connected. */
        const val EXTRA_ANSWERED = "io.github.jeshiomurmu.opencaller.extra.AFTER_CALL_ANSWERED"

        /** The after-call screen for [number] (a new task: it is started from the in-call service). */
        fun intent(
            context: Context,
            number: PhoneNumber,
            answered: Boolean,
        ): Intent =
            Intent(context, AfterCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NUMBER, number.e164)
                .putExtra(EXTRA_ANSWERED, answered)
    }
}
