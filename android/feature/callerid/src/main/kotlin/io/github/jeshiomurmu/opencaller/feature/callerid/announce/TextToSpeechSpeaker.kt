package io.github.jeshiomurmu.opencaller.feature.callerid.announce

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Speaker] on Android's TextToSpeech, in the phone's language (the default voice when that language isn't
 * installed), on the ringtone stream so the ring volume applies. The engine starts per announcement and shuts down
 * once it has spoken, so nothing keeps running between calls.
 */
@Singleton
class TextToSpeechSpeaker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : Speaker {
        private var tts: TextToSpeech? = null

        override fun speak(text: String) {
            stop()
            var engine: TextToSpeech? = null
            engine =
                TextToSpeech(context) { status ->
                    val ready = engine
                    if (status != TextToSpeech.SUCCESS || ready == null) {
                        Timber.w("TextToSpeech unavailable (status %d)", status)
                        ready?.shutdown()
                        return@TextToSpeech
                    }
                    say(ready, text)
                }
            tts = engine
        }

        override fun stop() {
            tts?.let {
                it.stop()
                it.shutdown()
            }
            tts = null
        }

        private fun say(
            engine: TextToSpeech,
            text: String,
        ) {
            val language = engine.setLanguage(Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                Timber.i("No voice for %s; using the default voice", Locale.getDefault())
            }
            engine.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = engine.shutdown()

                    @Deprecated("Deprecated in Android", ReplaceWith("onError(utteranceId, errorCode)"))
                    override fun onError(utteranceId: String?) = engine.shutdown()
                },
            )
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }

        private companion object {
            const val UTTERANCE_ID = "opencaller-announce"
        }
    }
