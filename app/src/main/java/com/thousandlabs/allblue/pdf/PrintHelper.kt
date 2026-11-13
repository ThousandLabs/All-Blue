package com.thousandlabs.allblue.pdf

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream

/**
 * Impression et conversion en PDF via le service d'impression d'Android.
 * Pour enregistrer en PDF : choisir « Enregistrer au format PDF » comme imprimante.
 */
object PrintHelper {

    fun printPdf(context: Context, file: File, name: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?, callback: WriteResultCallback
            ) {
                runCatching {
                    file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                    .onFailure { callback.onWriteFailed(it.message) }
            }
        }, null)
    }

    /** Word, Excel, PowerPoint, EPUB → PDF : la page affichée est « imprimée » en PDF. */
    fun printWebView(context: Context, webView: WebView, name: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = webView.createPrintDocumentAdapter(name)
        pm.print(name, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}
