package com.thousandlabs.allblue.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Réglages, fichiers récents, favoris et dernière page lue. Tout reste sur le téléphone. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** Incrémenté à chaque changement de réglage, pour que l'interface se mette à jour. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences("lecteur", Context.MODE_PRIVATE)
        _recents.value = readList("recents")
        _favorites.value = readList("favorites").toSet()
    }

    private fun readList(key: String): List<String> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeList(key: String, list: Collection<String>) {
        sp.edit().putString(key, JSONArray(list.toList()).toString()).apply()
    }

    fun addRecent(path: String) {
        val list = listOf(path) + _recents.value.filter { it != path }
        _recents.value = list.take(80)
        writeList("recents", _recents.value)
    }

    fun removeRecent(path: String) {
        _recents.value = _recents.value.filter { it != path }
        writeList("recents", _recents.value)
    }

    fun clearRecents() {
        _recents.value = emptyList()
        writeList("recents", emptyList())
    }

    fun isFavorite(path: String) = path in _favorites.value

    fun toggleFavorite(path: String) {
        _favorites.value = if (path in _favorites.value) _favorites.value - path else _favorites.value + path
        writeList("favorites", _favorites.value)
    }

    /** Quand un fichier est remplacé ou renommé, on garde son historique. */
    fun movePath(from: String, to: String) {
        if (from == to) return
        if (from in _favorites.value) {
            _favorites.value = _favorites.value - from + to
            writeList("favorites", _favorites.value)
        }
        _recents.value = _recents.value.map { if (it == from) to else it }.distinct()
        writeList("recents", _recents.value)
        val page = lastPage(from)
        if (page > 0) setLastPage(to, page)
    }

    fun lastPage(path: String): Int = sp.getInt("page:$path", 0)
    fun setLastPage(path: String, page: Int) = sp.edit().putInt("page:$path", page).apply()

    fun getBool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
        _version.value++
    }

    fun getInt(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, value: Int) {
        sp.edit().putInt(key, value).apply()
        _version.value++
    }

    fun getFloat(key: String, def: Float) = sp.getFloat(key, def)
    fun putFloat(key: String, value: Float) {
        sp.edit().putFloat(key, value).apply()
        _version.value++
    }
}

/** Fonctions activables et désactivables dans les réglages. */
enum class Feature(val key: String, val label: String, val description: String, val default: Boolean) {
    TEXT_MODE("f_text", "Mode texte adaptatif", "Le texte du PDF se remet à la largeur de l'écran.", true),
    TTS("f_tts", "Lecture à voix haute", "Lit la page à voix haute avec la voix française du téléphone.", true),
    ANNOTATE("f_annotate", "Annotations", "Stylo, surligneur, texte et signature sur les PDF.", true),
    PAGE_TOOLS("f_pages", "Outils des pages", "Pivoter, supprimer, extraire et réorganiser les pages.", true),
    BACKUP("f_backup", "Copie de secours automatique", "Garde l'original avant chaque modification.", true),
    RESUME("f_resume", "Reprendre à la dernière page", "Rouvre chaque document là où vous l'aviez laissé.", true),
    KEEP_SCREEN("f_screen", "Garder l'écran allumé", "Pendant la lecture d'un document.", true),
    WHATSAPP("f_whatsapp", "Raccourci Documents WhatsApp", "Affiche les fichiers reçus sur WhatsApp dans Parcourir.", true);

    val enabled: Boolean get() = Prefs.getBool(key, default)
}

/** Couleur de lecture des pages. */
enum class ReadingTheme(val label: String) { NORMAL("Normal"), NIGHT("Nuit"), SEPIA("Sépia") }

fun readingTheme(): ReadingTheme = ReadingTheme.entries.getOrElse(Prefs.getInt("reading_theme", 0)) { ReadingTheme.NORMAL }
fun setReadingTheme(t: ReadingTheme) = Prefs.putInt("reading_theme", t.ordinal)
