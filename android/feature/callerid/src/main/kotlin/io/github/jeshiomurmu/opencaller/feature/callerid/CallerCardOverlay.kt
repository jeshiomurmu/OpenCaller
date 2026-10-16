package io.github.jeshiomurmu.opencaller.feature.callerid

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.CallerCard
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardSurface
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Draws the caller card over the system's call screen (`TYPE_APPLICATION_OVERLAY`) while OpenCaller is not the
 * default phone app and the user allowed drawing over other apps. The window never takes focus, so the phone app
 * underneath stays fully usable. Call from the main thread.
 */
@Singleton
class CallerCardOverlay
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var shown: Pair<View, OverlayOwner>? = null

        /** True while a card is on screen. */
        val isShowing: Boolean get() = shown != null

        /** Shows [card]; returns false when it can't be drawn (no permission, or the system refused the window). */
        fun show(
            card: CallerCardModel,
            onClose: () -> Unit,
        ): Boolean {
            dismiss()
            if (!Settings.canDrawOverlays(context)) return false
            val windowContext = overlayContext()
            val owner = OverlayOwner()
            val view =
                ComposeView(windowContext).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent { CardContent(card, onClose) }
                }
            return try {
                windowContext.getSystemService(WindowManager::class.java).addView(view, layoutParams())
                owner.resume()
                shown = view to owner
                true
            } catch (e: WindowManager.BadTokenException) {
                Timber.w(e, "Overlay window refused")
                owner.destroy()
                false
            } catch (e: SecurityException) {
                Timber.w(e, "Overlay not allowed")
                owner.destroy()
                false
            }
        }

        /** Removes the card, if any. */
        fun dismiss() {
            val (view, owner) = shown ?: return
            shown = null
            try {
                view.context.getSystemService(WindowManager::class.java).removeView(view)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Overlay already removed")
            }
            owner.destroy()
        }

        // Android 11+ wants overlay windows added through a window context for the display.
        private fun overlayContext(): Context =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                context
            }

        private fun layoutParams() =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP
                    title = TITLE
                }

        private companion object {
            const val TITLE = "OpenCaller caller card"
        }
    }

@Composable
private fun CardContent(
    card: CallerCardModel,
    onClose: () -> Unit,
) {
    OpenCallerTheme {
        CallerCardSurface(modifier = Modifier.padding(OpenCallerSpacing.xs)) {
            CallerCard(caller = card.caller, formattedNumber = card.formattedNumber) {
                TextButton(onClick = onClose) { Text(text = stringResource(R.string.callerid_close)) }
            }
        }
    }
}

/** Lifecycle and saved state for a Compose view that lives in its own window instead of an activity. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
