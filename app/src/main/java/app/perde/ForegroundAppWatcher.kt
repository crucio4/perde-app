package app.perde

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Öndeki uygulamanın paket adını UsageStatsManager üzerinden çeker.
 *
 * AccessibilityService yerine bunu kullanıyoruz: daha az izin,
 * daha az batarya, Play Store politikası derdi yok (zaten sideload ama olsun).
 *
 * PACKAGE_USAGE_STATS izni Ayarlar'dan manuel verilmeli, runtime prompt yok.
 */
class ForegroundAppWatcher(context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastKnown: String? = null

    /** Öndeki paket adı, bilinemiyorsa son bilinen değer. */
    fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var latest: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        if (latest != null) lastKnown = latest
        return lastKnown
    }

    /**
     * Bu paket için yakalama çalışmalı mı?
     *
     * BLACKLIST (varsayılan): dışlananlar hariç her şey izlenir.
     * Reddit, 4chan (tarayıcıdan), Telegram, galeri, bilmediğin bir
     * tarayıcı — hepsi otomatik kapsanır. Liste bakımı gerektirmez.
     */
    fun shouldMonitor(pkg: String?): Boolean {
        if (pkg == null) return false
        return when (Config.monitorMode) {
            Config.MonitorMode.BLACKLIST -> pkg !in Config.EXCLUDED_PACKAGES
            Config.MonitorMode.WHITELIST -> pkg in Config.WATCHED_PACKAGES
        }
    }

    /** İzin verilmiş mi? queryEvents boş dönüyorsa büyük ihtimalle verilmemiş. */
    fun hasPermission(): Boolean {
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 1000 * 60 * 60, now
        )
        return !stats.isNullOrEmpty()
    }

    companion object { private const val LOOKBACK_MS = 10_000L }
}
