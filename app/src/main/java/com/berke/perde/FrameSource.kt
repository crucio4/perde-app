package com.berke.perde

import android.graphics.Bitmap

/**
 * Ekran karesi kaynagi. Iki uygulamasi var:
 *
 *   A11yCapturer   — AccessibilityService.takeScreenshot(), API 30+
 *   ScreenCapturer — MediaProjection + VirtualDisplay, her surumde
 *
 * Tercih A11yCapturer. Sebepleri:
 *   - Ekran kaydi gostergesi cikmiyor
 *   - Sistem bildirim iceriklerini gizlemiyor (MediaProjection aktifken
 *     gizliyor, kullanici mesajlarini goremiyor)
 *   - Her baslatmada ekran yakalama izni sorulmuyor
 *   - FLAG_SECURE'da TAHMIN degil, acik hata kodu donuyor
 *
 * Son madde onemli: MediaProjection gizli sekmede hicbir sey uretmiyor —
 * ne siyah kare ne hata. Duragan ekrandan ayirt edilemiyor, dolayisiyla
 * o kor nokta sezgisel yollarla kapatilamiyordu.
 */
interface FrameSource {

    /** @return baslatilabildi mi */
    fun start(): Boolean

    fun stop()

    fun isRunning(): Boolean

    /**
     * Ekranda duran icerik.
     *
     * SAHIPLIK: donen Bitmap kaynaga aittir, cagiran recycle ETMEZ.
     * Onbelleklenip yeniden dondurulebilir; temizligi stop() yapar.
     *
     * @return kare, hic alinamadiysa null
     */
    fun grabFrame(): Bitmap?

    /**
     * Icerik korumali oldugu icin mi okunamiyor?
     *
     * MediaProjection bunu bilemez (hep false doner) — kare gelmemesi
     * durgun ekran da olabilir. takeScreenshot ise kesin cevap verir.
     */
    fun isSecureBlocked(): Boolean
}
