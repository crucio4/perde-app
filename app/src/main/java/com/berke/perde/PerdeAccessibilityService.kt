package com.berke.perde

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Erişilebilirlik servisi — uygulamanın iki gözü de buradan geçiyor.
 *
 *   1. takeScreenshot()  -> piksel kanalı (A11yCapturer)
 *   2. node tree         -> içerik kanalı (ScreenReader)
 *
 * İKİNCİSİ NEDEN ÖNEMLİ:
 * FLAG_SECURE render edilmiş yüzeyi korur, erişilebilirlik ağacını
 * korumaz. Gizli sekmede ekran görüntüsü siyah gelirken adres çubuğu,
 * sayfa başlığı ve sayfa metni okunmaya devam eder. Kör noktayı root
 * gerektirmeden kapatan tek yol bu.
 *
 * Burada ARTIK KARAR VERİLMİYOR. Eskiden bu sınıf adres çubuğunu okuyup
 * anahtar kelime listesiyle karşılaştırıyor ve doğrudan blok basıyordu —
 * yani ikinci ve bambaşka kurallara sahip bir tespit yolu vardı. Şimdi
 * tek işi okumak; okunan içerik DetectionLoop'a gidiyor, karar orada,
 * diğer kanalla aynı yanlış-pozitif katmanlarından geçerek veriliyor.
 *
 * GİZLİLİK: okunan metin yalnızca bellekte tutuluyor, bir sonraki
 * okumada üzerine yazılıyor. Diske yazılmıyor, loglanmıyor, cihazdan
 * çıkmıyor.
 *
 * KURULUM: Ayarlar > Erişilebilirlik > Perde. Manuel, runtime prompt yok.
 */
class PerdeAccessibilityService : AccessibilityService() {

    /**
     * Görsel tespit döngüsü. MediaProjection yolunda bu döngüyü
     * ScreenGuardService sürüyor; erişilebilirlik yolunda buraya taşındı
     * çünkü ekran görüntüsü yalnızca bu servis örneği üzerinden alınabiliyor.
     */
    private var loop: DetectionLoop? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        ScreenReader.clear()
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

    /**
     * Olaylar okumayı TETİKLER, karar vermez.
     *
     * Döngü zaten her tick'te okuma istiyor; buradaki tetikleme sayfa
     * değiştiği anda taze içerik almak için. İkisi de aynı kısıtlayıcıya
     * giriyor (ScreenReader.MIN_INTERVAL_MS), yani olay yağmuru maliyet
     * doğurmuyor.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Koruma kapalıyken ekran OKUNMAZ. Gizlilik açısından kritik:
        // servis açık kalabilir, okuma yalnızca koruma açıkken olur.
        // (Döngü MediaProjection yolunda başka bir serviste yaşıyor,
        // o yüzden `loop != null` kontrolü yetmez.)
        if (!Guard.isEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in Config.EXCLUDED_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> ScreenReader.requestRefresh()
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "PerdeA11y"

        /**
         * Bagli servis ornegi. takeScreenshot() ve node tree okumasi
         * yalnizca servis ornegi uzerinden yapilabiliyor, o yuzden
         * disariya bu sekilde aciliyor. Sistem servisi baglar/koparir;
         * null ise ne ekran goruntusu ne icerik okunabilir.
         */
        @Volatile
        var instance: PerdeAccessibilityService? = null
            private set
    }
}
