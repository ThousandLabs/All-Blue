package com.thousandlabs.allblue

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.DocType
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.docs.EditOfficeScreen
import com.thousandlabs.allblue.docs.ImageScreen
import com.thousandlabs.allblue.docs.OfficeScreen
import com.thousandlabs.allblue.docs.TextScreen
import com.thousandlabs.allblue.pdf.AnnotateScreen
import com.thousandlabs.allblue.pdf.PageToolsScreen
import com.thousandlabs.allblue.pdf.PdfScreen
import com.thousandlabs.allblue.ui.AllBlueTheme
import com.thousandlabs.allblue.ui.BackupsScreen
import com.thousandlabs.allblue.ui.BusyOverlay
import com.thousandlabs.allblue.ui.HomeScreen
import com.thousandlabs.allblue.ui.ImagesToPdfScreen
import com.thousandlabs.allblue.ui.MergeScreen
import com.thousandlabs.allblue.ui.Night
import com.thousandlabs.allblue.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Les écrans de l'appli. */
sealed interface Screen {
    data object Home : Screen
    data class Viewer(val doc: Doc, val key: Long = System.nanoTime()) : Screen
    data class PageTools(val doc: Doc) : Screen
    data class Annotate(val doc: Doc, val page: Int) : Screen
    data class EditOffice(val doc: Doc) : Screen
    data class Merge(val files: List<File>) : Screen
    data class ImagesToPdf(val files: List<File>) : Screen
    data object Backups : Screen
}

/** Navigation simple : une pile d'écrans. */
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val busy = mutableStateOf<String?>(null)

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    /** Remplace l'écran du document par une nouvelle version (après une modification). */
    fun reopen(doc: Doc) {
        val i = stack.indexOfLast { it is Screen.Viewer }
        while (stack.size - 1 > i && stack.size > 1) stack.removeAt(stack.lastIndex)
        if (i >= 0) stack[i] = Screen.Viewer(doc) else stack.add(Screen.Viewer(doc))
    }

    fun open(doc: Doc) {
        Prefs.addRecent(doc.file.absolutePath)
        // Évite d'empiler deux fois le même document
        if ((stack.lastOrNull() as? Screen.Viewer)?.doc?.file == doc.file) return
        push(Screen.Viewer(doc))
    }

    fun openFile(f: File) = open(Doc(f, f.name))
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AllBlueTheme {
                Box(Modifier.fillMaxSize().background(Night)) {
                    val top = Nav.stack.last()
                    BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
                    ScreenHost(top)
                    Nav.busy.value?.let { BusyOverlay(it) }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        Updater.autoCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Updater.onAppResumed(this)
    }

    @Composable
    private fun ScreenHost(s: Screen) {
        when (s) {
            Screen.Home -> HomeScreen()
            is Screen.Viewer -> androidx.compose.runtime.key(s.key) {
                when (s.doc.type) {
                    DocType.PDF -> PdfScreen(s.doc)
                    DocType.WORD, DocType.EXCEL, DocType.POWERPOINT, DocType.EPUB -> OfficeScreen(s.doc)
                    DocType.TEXT -> TextScreen(s.doc)
                    DocType.IMAGE -> ImageScreen(s.doc)
                    DocType.OTHER -> androidx.compose.runtime.LaunchedEffect(Unit) {
                        Files.openWithOther(this@MainActivity, s.doc.file); Nav.pop()
                    }
                }
            }
            is Screen.PageTools -> PageToolsScreen(s.doc)
            is Screen.Annotate -> AnnotateScreen(s.doc, s.page)
            is Screen.EditOffice -> EditOfficeScreen(s.doc)
            is Screen.Merge -> MergeScreen(s.files)
            is Screen.ImagesToPdf -> ImagesToPdfScreen(s.files)
            Screen.Backups -> BackupsScreen()
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamList(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val mime = intent.type
        lifecycleScope.launch {
            Nav.busy.value = "Ouverture…"
            val docs = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { Files.docFromUri(this@MainActivity, it, mime) }.getOrNull() }
            }
            Nav.busy.value = null
            when {
                docs.isEmpty() -> Files.toast(this@MainActivity, "Impossible d'ouvrir ce fichier.")
                docs.size == 1 -> Nav.open(docs[0])
                docs.all { it.type == DocType.IMAGE } -> Nav.push(Screen.ImagesToPdf(docs.map { it.file }))
                docs.all { it.type == DocType.PDF } -> Nav.push(Screen.Merge(docs.map { it.file }))
                else -> Nav.open(docs[0])
            }
        }
        setIntent(Intent()) // évite de rouvrir le fichier à la rotation de l'écran
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
