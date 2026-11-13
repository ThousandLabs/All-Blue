package com.thousandlabs.allblue.data

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DocType(val label: String) {
    PDF("PDF"), WORD("Word"), EXCEL("Excel"), POWERPOINT("PowerPoint"),
    EPUB("EPUB"), TEXT("Texte"), IMAGE("Image"), OTHER("Fichier")
}

private val EXT = mapOf(
    "pdf" to DocType.PDF,
    "docx" to DocType.WORD, "docm" to DocType.WORD, "dotx" to DocType.WORD,
    "xlsx" to DocType.EXCEL, "xlsm" to DocType.EXCEL,
    "pptx" to DocType.POWERPOINT, "ppsx" to DocType.POWERPOINT, "pptm" to DocType.POWERPOINT,
    "epub" to DocType.EPUB,
    "txt" to DocType.TEXT, "csv" to DocType.TEXT, "md" to DocType.TEXT, "log" to DocType.TEXT,
    "json" to DocType.TEXT, "xml" to DocType.TEXT, "html" to DocType.TEXT, "htm" to DocType.TEXT,
    "jpg" to DocType.IMAGE, "jpeg" to DocType.IMAGE, "png" to DocType.IMAGE, "webp" to DocType.IMAGE,
    "gif" to DocType.IMAGE, "bmp" to DocType.IMAGE, "heic" to DocType.IMAGE
)

fun typeOf(name: String): DocType = EXT[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: DocType.OTHER

/** Formats proposés dans Parcourir et la recherche (les images restent dans la galerie). */
fun isListedDocument(name: String): Boolean = typeOf(name).let { it != DocType.OTHER && it != DocType.IMAGE }

/**
 * Un document ouvert. [file] est toujours un vrai fichier lisible ; quand le document vient
 * d'une autre appli (WhatsApp, Gmail…), c'est une copie et [sourceUri] permet d'y réécrire si possible.
 */
data class Doc(val file: File, val name: String, val sourceUri: Uri? = null) {
    val type: DocType get() = typeOf(name)
    val isCopy: Boolean get() = sourceUri != null
}

object Files {

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Dossiers où WhatsApp range les documents reçus (selon la version de WhatsApp). */
    fun whatsappFolders(): List<File> = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "WhatsApp/Media/WhatsApp Documents",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
        "WhatsApp Business/Media/WhatsApp Business Documents"
    ).map { File(root, it) }.filter { it.isDirectory }

    fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    fun documents(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    /** Dossier où l'appli range ce qu'elle crée quand elle ne peut pas écrire à côté de l'original. */
    fun outputFolder(): File = File(documents(), "All Blue").apply { mkdirs() }

    // ---------- Ouverture depuis une autre appli ----------

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "document").name
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Essaie de retrouver le vrai chemin d'un fichier partagé (pour pouvoir l'enregistrer sur place). */
    private fun realPath(context: Context, uri: Uri): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it) }?.takeIf { it.canRead() }
        if (!hasAllFilesAccess()) return null
        return runCatching {
            if (DocumentsContract.isDocumentUri(context, uri) && uri.authority == "com.android.externalstorage.documents") {
                val id = DocumentsContract.getDocumentId(uri) // ex. « primary:Download/cours.pdf »
                val (vol, rel) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val base = if (vol.equals("primary", true)) root else File("/storage/$vol")
                File(base, rel).takeIf { it.isFile && it.canRead() }
            } else {
                // Certains gestionnaires de fichiers donnent le chemin dans l'adresse
                uri.path?.let { p ->
                    val idx = p.indexOf("/storage/")
                    if (idx >= 0) File(p.substring(idx)).takeIf { it.isFile && it.canRead() } else null
                }
            }
        }.getOrNull()
    }

    fun docFromUri(context: Context, uri: Uri, mime: String? = null): Doc {
        realPath(context, uri)?.let { return Doc(it, it.name) }
        var name = displayName(context, uri)
        if (typeOf(name) == DocType.OTHER && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        val dir = File(context.cacheDir, "ouverts").apply { mkdirs() }
        val target = File(dir, sanitize(name))
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return Doc(target, name, uri)
    }

    fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)

    // ---------- Enregistrement ----------

    /** Nom libre à côté de [base] : « cours (modifié).pdf », « cours (modifié 2).pdf »… */
    fun siblingName(base: File, suffix: String, ext: String = base.extension): File {
        val dir = if (base.parentFile?.canWrite() == true && !base.absolutePath.startsWith("/data/")) base.parentFile!! else outputFolder()
        val stem = base.nameWithoutExtension
        var f = File(dir, "$stem ($suffix).$ext")
        var i = 2
        while (f.exists()) { f = File(dir, "$stem ($suffix $i).$ext"); i++ }
        return f
    }

    /** Copie de secours de l'original avant modification. On garde les 40 dernières. */
    fun backup(context: Context, file: File) {
        if (!Feature.BACKUP.enabled || !file.exists()) return
        runCatching {
            val dir = backupDir(context)
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(Date())
            file.copyTo(File(dir, "${stamp}__${file.name}"), overwrite = true)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        }
    }

    fun backupDir(context: Context) = File(context.filesDir, "secours").apply { mkdirs() }

    /**
     * Remplace le document par sa version modifiée [edited] (fichier temporaire).
     * Renvoie le fichier final, qui reste lisible par l'appli.
     */
    fun replaceDocument(context: Context, doc: Doc, edited: File): Doc {
        backup(context, doc.file)
        edited.copyTo(doc.file, overwrite = true)
        edited.delete()
        // Le document venait d'une autre appli : on essaie de réécrire l'original
        doc.sourceUri?.let { uri ->
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> doc.file.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (!ok) {
                val saved = File(outputFolder(), sanitize(doc.name)).let { if (it.exists()) siblingName(it, "modifié") else it }
                doc.file.copyTo(saved, overwrite = true)
                toast(context, "Enregistré dans Documents/All Blue")
                return Doc(saved, saved.name)
            }
        }
        return doc
    }

    // ---------- Partage ----------

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fichiers", file)

    fun mimeOf(file: File): String = when (typeOf(file.name)) {
        DocType.PDF -> "application/pdf"
        DocType.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        DocType.EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        DocType.POWERPOINT -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        DocType.EPUB -> "application/epub+zip"
        DocType.TEXT -> "text/plain"
        DocType.IMAGE -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/*"
        DocType.OTHER -> "*/*"
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Envoyer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ouvre le fichier dans une autre appli (pour les formats que Lecteur ne gère pas). */
    fun openWithOther(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(Intent.createChooser(intent, "Ouvrir avec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { toast(context, "Aucune appli ne peut ouvrir ce fichier.") }
    }

    // ---------- Parcourir et rechercher ----------

    fun listFolder(dir: File): List<File> {
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
        val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val docs = all.filter { it.isFile && typeOf(it.name) != DocType.OTHER }.sortedByDescending { it.lastModified() }
        return dirs + docs
    }

    /** Parcourt tout le stockage à la recherche de documents. [onFound] est appelé au fil de l'eau. */
    fun walkDocuments(maxFiles: Int = 5000, accept: (File) -> Boolean = { isListedDocument(it.name) }): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        val skip = setOf("Android/data", "Android/obb")
        while (stack.isNotEmpty() && out.size < maxFiles) {
            val dir = stack.removeLast()
            val rel = dir.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            if (skip.any { rel.startsWith(it) }) continue
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) stack.add(c) else if (accept(c)) out.add(c)
            }
        }
        // WhatsApp range ses documents dans Android/media : déjà couvert. On trie du plus récent au plus ancien.
        return out.sortedByDescending { it.lastModified() }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
        else -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1024.0 / 1024.0)
    }

    fun dateLabel(time: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(time))

    fun toast(context: Context, msg: String) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }
}
