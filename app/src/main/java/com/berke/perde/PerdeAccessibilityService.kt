package com.berke.perde

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * İkinci savunma katmanı — piksel görmeye ihtiyaç duymaz.
 *
 * NEDEN ÇALIŞIR:
 * FLAG_SECURE render edilmiş yüzeyi (surface) korur. Accessibility node
 * tree ayrı bir yapıdır ve bu bayraktan etkilenmez. Yani gizli sekmede
 * ekran görüntüsü siyah gelirken, adres çubuğundaki metni hâlâ okuyabiliriz.
 *
 * SINIRI:
 * Bu bir alan-adı kontrolü — yani blocklist mantığı, içerik sınıflandırması
 * değil. Sen "database istemiyorum" dedin, haklısın; ama kör noktayı
 * kapatmanın root'suz tek yolu bu. Anahtar kelime listesini küçük tut,
 * asıl iş hâlâ görsel sınıflandırıcıda.
 *
 * KURULUM:
 * Ayarlar > Erişilebilirlik > Perde > Aç. Manuel, runtime prompt yok.
 * Sistem "bu servis ekranındaki metinleri okuyabilir" uyarısı gösterir.
 */
class PerdeAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayManager
    private var lastUrl: String? = null
    private var lastBlockAt = 0L

    /** URL bloğunun zorunlu kalkması için. */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Görsel tespit döngüsü. MediaProjection yolunda bu döngüyü
     * ScreenGuardService sürüyor; erişilebilirlik yolunda buraya taşındı
     * çünkü ekran görüntüsü yalnızca bu servis örneği üzerinden alınabiliyor.
     */
    private var loop: DetectionLoop? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayManager(this)
        Log.i(TAG, "Erişilebilirlik katmanı aktif")

        // Görevlerden silinme / süreç öldürülmesi sonrası toparlanma.
        // Sistem erişilebilirlik servislerini her zaman yeniden bağlar.
        // MediaProjection'da bunu yapmak anlamsızdı (token kayboluyor,
        // kullanıcıdan yeniden onay gerekiyordu); takeScreenshot yolunda
        // token yok, o yüzden koruma kullanıcı hiçbir şey yapmadan kaldığı
        // yerden devam edebiliyor.
        if (Guard.isEnabled(this)) {
            Log.i(TAG, "Koruma açık bırakılmıştı, döngü yeniden başlatılıyor")
            startLoop()
        }
    }

    fun startLoop() {
        if (loop != null) return
        if (!Guard.a11yCaptureAvailable()) {
            Log.w(TAG, "takeScreenshot kullanılamıyor (API < 30), döngü başlatılmadı")
            return
        }
        loop = DetectionLoop(this) { worker ->
            // takeScreenshot geri çağrısını döngünün kendi iş parçacığına
            // yönlendiriyoruz; tick() ile aynı yerde kalsın.
            A11yCapturer(java.util.concurrent.Executor { worker.post(it) })
        }.also { it.start() }
    }

    fun stopLoop() {
        loop?.stop()
        loop = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopLoop()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopLoop()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in Config.EXCLUDED_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> checkUrl(pkg)
        }
    }

    private fun checkUrl(pkg: String) {
        val root = rootInActiveWindow ?: return
        val url = extractUrl(root, pkg) ?: return
        root.recycle()

        if (url == lastUrl) return
        lastUrl = url

        val lower = url.lowercase()
        val hit = Config.URL_KEYWORDS.any { it in lower }

        if (hit) {
            val now = System.currentTimeMillis()
            if (now - lastBlockAt < 2000) return
            lastBlockAt = now
            Log.i(TAG, "URL bloğu tetiklendi")
            overlay.show("Kapat.")
            // Bu katmanın kapatma yolu YOKTU: bir kez tetiklenseydi ekran
            // kalıcı siyah kalırdı, geri/ana ekran/bildirim paneli overlay'in
            // altında olduğu için çıkış da olmazdı. Görsel katmandaki aynı
            // hatayı düzeltmiştik, burada gözden kaçmış.
            uiHandler.postDelayed({ overlay.hide() }, Config.MAX_BLOCK_DURATION_MS)
        }
    }

    /**
     * Tarayıcıya göre adres çubuğu view id'si değişir.
     * Yeni tarayıcı eklemek için: uiautomatorviewer veya
     * `adb shell dumpsys activity top` ile id'yi bul, listeye ekle.
     */
    private fun extractUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        val ids = URL_BAR_IDS[pkg] ?: URL_BAR_IDS_FALLBACK
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()
                nodes.forEach { it.recycle() }
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "PerdeA11y"

        /**
         * Bagli servis ornegi. takeScreenshot() yalnizca servis ornegi
         * uzerinden cagrilabiliyor, o yuzden disariya bu sekilde aciliyor.
         * Sistem servisi baglar/koparir; null ise ekran goruntusu alinamaz.
         */
        @Volatile
        var instance: PerdeAccessibilityService? = null
            private set

        private val URL_BAR_IDS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"
            ),
            "com.duckduckgo.mobile.android" to listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput"
            ),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
            )
        )

        private val URL_BAR_IDS_FALLBACK = listOf("url_bar", "location_bar_edit_text")
    }
}
