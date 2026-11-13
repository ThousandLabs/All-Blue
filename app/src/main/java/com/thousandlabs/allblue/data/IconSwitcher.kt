package com.thousandlabs.allblue.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Les quatre icônes de l'appli. */
enum class AppIcon(val alias: String, val label: String, val moment: String) {
    A("IconeA", "Poisson des 4 mers", "Nuit"),
    B("IconeB", "Bouteille à la mer", "Matin"),
    C("IconeC", "Barre du navire", "Après-midi"),
    D("IconeD", "Cap sur l'horizon", "Soir")
}

/**
 * Change l'icône de l'appli selon le moment de la journée (ou garde l'icône choisie).
 * Le changement n'a lieu que lorsque l'appli n'est pas à l'écran, pour ne jamais la fermer en cours d'utilisation.
 */
object IconSwitcher {
    private const val KEY = "icon_mode" // -1 : changeante, sinon index de l'icône fixe
    @Volatile var appVisible = false

    fun mode(): Int = Prefs.getInt(KEY, -1)
    fun setMode(m: Int) = Prefs.putInt(KEY, m)

    fun forHour(hour: Int): AppIcon = when (hour) {
        in 6..11 -> AppIcon.B
        in 12..17 -> AppIcon.C
        in 18..20 -> AppIcon.D
        else -> AppIcon.A
    }

    fun wanted(): AppIcon {
        val m = mode()
        return if (m in AppIcon.entries.indices) AppIcon.entries[m] else forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    fun current(context: Context): AppIcon? = AppIcon.entries.firstOrNull { isEnabled(context, it) }

    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "${context.packageName}.${icon.alias}")

    private fun isEnabled(context: Context, icon: AppIcon): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context, icon))
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> icon == AppIcon.C // état d'origine déclaré dans le manifeste
        }
    }

    /** Applique l'icône voulue si elle a changé (jamais quand l'appli est à l'écran). */
    fun apply(context: Context) {
        if (appVisible) return
        val target = wanted()
        if (current(context) == target) return
        runCatching {
            val pm = context.packageManager
            // on active d'abord la nouvelle, pour que l'appli reste toujours visible dans le tiroir
            pm.setComponentEnabledSetting(component(context, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            AppIcon.entries.filter { it != target }.forEach {
                pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }

    /** Vérification régulière en arrière-plan (Android peut la décaler un peu pour économiser la batterie). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<IconWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("icone", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class IconWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        IconSwitcher.apply(applicationContext)
        return Result.success()
    }
}
