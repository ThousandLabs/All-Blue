package com.thousandlabs.allblue.pdf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.thousandlabs.allblue.data.Prefs
import java.util.Locale

/**
 * Lecture à voix haute, hors ligne, avec la voix française installée sur le téléphone.
 * Lit une page, puis passe automatiquement à la suivante.
 */
class Tts(context: Context, private val onPageStart: (Int) -> Unit, private val onStop: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var pages: List<String> = emptyList()
    private var page = 0
    private var stopped = true

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts.setLanguage(Locale.FRANCE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.FRENCH)
                main.post { pending?.invoke(); pending = null }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("fin-") == true) main.post { next() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { stop() } }
        })
    }

    val isSpeaking: Boolean get() = !stopped

    fun start(allPages: List<String>, from: Int) {
        pages = allPages
        page = from
        stopped = false
        val go = { speakPage() }
        if (ready) go() else pending = go
    }

    private fun speakPage() {
        if (stopped) return
        // Saute les pages vides (images, pages blanches)
        while (page < pages.size && pages[page].isBlank()) page++
        if (page >= pages.size) { stop(); return }
        onPageStart(page)
        tts.setSpeechRate(Prefs.getFloat("tts_rate", 1.0f))
        val chunks = chunk(pages[page])
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "fin-$page" else "p$page-$i"
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private fun next() {
        if (stopped) return
        page++
        speakPage()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        tts.stop()
        onStop()
    }

    fun shutdown() {
        stopped = true
        runCatching { tts.stop(); tts.shutdown() }
    }

    /** Découpe en phrases de moins de 3 500 caractères (limite d'Android). */
    private fun chunk(text: String): List<String> {
        val clean = text.replace(Regex("-\\n(?=\\p{L})"), "").replace(Regex("\\s*\\n\\s*"), " ").trim()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in clean.split(Regex("(?<=[.!?;:])\\s+"))) {
            if (sb.length + sentence.length > 3500 && sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
            if (sentence.length > 3500) sentence.chunked(3500).forEach { out.add(it) } else sb.append(sentence).append(' ')
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out.ifEmpty { listOf(" ") }
    }
}
