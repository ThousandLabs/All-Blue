package com.thousandlabs.allblue.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

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
        // nettoyage des doublons déjà enregistrés (lecture des fichiers : hors du fil principal)
        Thread {
            val before = _recents.value
            val cleaned = dedupe(before)
            // si un document a été ouvert entre-temps, on ne remplace pas la liste
            if (cleaned != before && _recents.compareAndSet(before, cleaned)) writeList("recents", cleaned)
        }.start()
    }

    // ---------- Un document = une seule ligne dans les récents ----------

    /**
     * Empreinte du contenu : taille + somme de contrôle du début et de la fin du fichier.
     * Le même document ouvert par deux chemins différents (vrai emplacement, copie faite à
     * l'ouverture depuis WhatsApp ou un gestionnaire de fichiers, /sdcard ou /storage/emulated/0)
     * a la même empreinte.
     */
    private fun fingerprint(f: File): String? = runCatching {
        if (!f.isFile) return@runCatching null
        val len = f.length()
        val crc = java.util.zip.CRC32()
        java.io.RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            val n1 = raf.read(buf).coerceAtLeast(0)
            crc.update(buf, 0, n1)
            if (len > buf.size * 2) {
                raf.seek(len - buf.size)
                val n2 = raf.read(buf).coerceAtLeast(0)
                crc.update(buf, 0, n2)
            }
        }
        "$len:${crc.value}"
    }.getOrNull()

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** Empreintes gardées en mémoire, et sur le téléphone pour les copies effacées entre-temps. */
    private val fpCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun fpOf(path: String): String? {
        val f = File(path)
        val stored = sp.getString("fp:$path", null)
        if (!f.exists()) return stored
        val key = "$path|${f.length()}|${f.lastModified()}"
        fpCache[key]?.let { return it }
        val fp = fingerprint(f) ?: return stored
        fpCache[key] = fp
        if (fp != stored) sp.edit().putString("fp:$path", fp).apply()
        return fp
    }

    /** Garde la première apparition de chaque document (la plus récente) et retire les autres. */
    private fun dedupe(list: List<String>): List<String> {
        val seenPaths = HashSet<String>()
        val seenFp = HashSet<String>()
        return list.filter { p ->
            val c = canonical(p)
            val fp = fpOf(p)
            val dup = c in seenPaths || (fp != null && fp in seenFp)
            seenPaths += c
            if (fp != null) seenFp += fp
            !dup
        }
    }

    private fun readList(key: String): List<String> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeList(key: String, list: Collection<String>) {
        sp.edit().putString(key, JSONArray(list.toList()).toString()).apply()
    }

    /**
     * Ajoute un document en tête des récents. S'il y était déjà (même chemin, même fichier
     * sous un autre chemin, ou même contenu), l'ancienne ligne est retirée : il remonte simplement en haut.
     */
    fun addRecent(path: String) {
        val c = canonical(path)
        val fp = fpOf(path)
        val list = listOf(path) + _recents.value.filter { p ->
            p != path && canonical(p) != c && (fp == null || sp.getString("fp:$p", null) != fp)
        }
        _recents.value = list.take(80)
        writeList("recents", _recents.value)
    }

    fun removeRecent(path: String) {
        // retire aussi les éventuelles autres lignes du même document
        val c = canonical(path)
        val fp = sp.getString("fp:$path", null)
        _recents.value = _recents.value.filter { p -> p != path && canonical(p) != c && (fp == null || sp.getString("fp:$p", null) != fp) }
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
        sp.getString("fp:$from", null)?.let { sp.edit().putString("fp:$to", it).apply() }
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
