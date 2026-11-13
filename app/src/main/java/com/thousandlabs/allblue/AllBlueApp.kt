package com.thousandlabs.allblue

import android.app.Application
import com.thousandlabs.allblue.data.Prefs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

class AllBlueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PDFBoxResourceLoader.init(this)
        // Nettoie les copies temporaires de plus de 3 jours (fichiers reçus d'autres applis)
        runCatching {
            val limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            File(cacheDir, "ouverts").listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
            File(cacheDir, "travail").listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
