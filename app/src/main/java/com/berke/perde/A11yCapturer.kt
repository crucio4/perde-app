package com.berke.perde

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * AccessibilityService.takeScreenshot() uzerinden kare alir (API 30+).
 *
 * MediaProjection'dan farklari:
 *   - Ekran kaydi gostergesi yok, bildirimler gizlenmiyor, izin sorulmuyor
 *   - FLAG_SECURE'da ERROR_TAKE_SCREENSHOT_SECURE_WINDOW donuyor, yani
 *     "goremiyorum" durumu tahmin degil kesin bilgi
 *   - Sistem cagrilari 333 ms araliga sinirliyor; Config.CAPTURE_FPS = 1.0
 *     oldugu icin sinira yaklasmiyoruz bile
 *
 * API asenkron, dongu senkron. Köprü: her grabFrame() cagrisi yeni bir
 * istek baslatir ve EN SON tamamlanan kareyi dondurur. 1 fps'de bu bir
 * tick gecikme demek, kabul edilebilir.
 */
@RequiresApi(Build.VERSION_CODES.R)
class A11yCapturer(private val executor: Executor) : FrameSource {

    private var running = false

    @Volatile private var latest: Bitmap? = null
    @Volatile private var inFlight = false
    @Volatile private var secureBlocked = false

    /** En son karenin alindigi an. Bayat kare dondurmemek icin. */
    @Volatile private var latestAt = 0L

    /** Istegin gonderildigi an. Geri cagri kaybolursa kurtulmak icin. */
    @Volatile private var inFlightSince = 0L

    /**
     * Son basarisizligin insan okunur hali. Tani ekrani icin.
     *
     * Bu alan olmadan "kare yok" durumunun sebebi cihazda hic
     * anlasilamiyor: hata kodu yalnizca logcat'e yaziliyordu ve bazi
     * ureticiler ucuncu taraf uygulamalarin loglarini bastirdigi icin
     * orasi bos kaliyor.
     */
    @Volatile private var errorText = "-"

    override fun start(): Boolean {
        if (running) return true
        if (PerdeAccessibilityService.instance == null) {
            Log.e(TAG, "Erisilebilirlik servisi bagli degil, kare alinamaz")
            return false
        }
        running = true
        secureBlocked = false
        Log.i(TAG, "Yakalama basladi (takeScreenshot)")
        return true
    }

    override fun stop() {
        running = false
        inFlight = false
        secureBlocked = false
        latest?.recycle()
        latest = null
        Log.i(TAG, "Yakalama durdu")
    }

    override fun isRunning() = running

    override fun isSecureBlocked() = secureBlocked

    override fun lastError() = errorText

    /**
     * @return YALNIZCA taze kare. takeScreenshot her cagrida guncel ekrani
     *         verdigi icin bayat kare tutmanin bir gerekcesi yok — dahasi
     *         tehlikeli: istek boru hatti bir kez takilirsa donmus kare
     *         sonsuza kadar puanlanir ve tespit sessizce olur. Bayatladiysa
     *         null donuyoruz, dongu bunu "goremiyorum" olarak isliyor.
     */
    override fun grabFrame(): Bitmap? {
        if (!running) return null
        request()
        val f = latest ?: return null
        return if (System.currentTimeMillis() - latestAt <= STALE_AFTER_MS) f else null
    }

    private fun request() {
        val svc = PerdeAccessibilityService.instance
        if (svc == null) {
            errorText = "servis yok"
            return
        }

        val now = System.currentTimeMillis()
        if (inFlight) {
            // Geri cagri kayboldu: sistem pencere gecislerinde istegi
            // dusurebiliyor. Zaman asimi olmasa bayrak sonsuza kadar true
            // kalir, bir daha hic istek gonderilmez ve yakalama sessizce olur.
            if (now - inFlightSince < IN_FLIGHT_TIMEOUT_MS) return
            // Bu ayri bir ariza sinifi: istek sisteme ulasti ama ne onSuccess
            // ne onFailure geldi. Hata kodu dondurenden bambaska bir sebep.
            errorText = "cevap gelmedi"
            Log.w(TAG, "takeScreenshot geri cagrisi gelmedi, istek sifirlaniyor")
        }
        inFlight = true
        inFlightSince = now

        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        inFlight = false
                        secureBlocked = false
                        errorText = "-"
                        store(result)
                    }

                    override fun onFailure(errorCode: Int) {
                        inFlight = false
                        // Kor noktanin kesin sinyali. MediaProjection'da bu
                        // bilgi hic yoktu, sessizce hicbir sey olmuyordu.
                        secureBlocked =
                            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                        errorText = errorName(errorCode)
                        Log.w(TAG, "takeScreenshot basarisiz: kod=$errorCode secure=$secureBlocked")
                    }
                }
            )
        } catch (e: Exception) {
            inFlight = false
            errorText = "cagrilamadi: ${e.javaClass.simpleName}"
            Log.e(TAG, "takeScreenshot cagrilamadi: ${e.message}")
        }
    }

    /**
     * Hata kodunu okunur hale getirir.
     *
     * Kodun kendisi de yaziliyor: liste surumden surume genisliyor ve
     * taninmayan bir kod "bilinmeyen(7)" olarak da olsa gorunmeli.
     */
    private fun errorName(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "ic hata($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "erisim yok($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "cok sik istek($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "gecersiz ekran($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "korumali pencere($code)"
        else -> "bilinmeyen($code)"
    }

    private fun store(result: AccessibilityService.ScreenshotResult) {
        val buffer = result.hardwareBuffer
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return

            // wrapHardwareBuffer HARDWARE config'li bitmap verir; ondan piksel
            // okunamaz ve yazilim canvas'ina cizilemez. Once yazilim kopyasi
            // sart, ardindan CAPTURE_DOWNSCALE ile kucultuyoruz.
            val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            if (soft == null) return

            val w = (soft.width / Config.CAPTURE_DOWNSCALE).coerceAtLeast(1)
            val h = (soft.height / Config.CAPTURE_DOWNSCALE).coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
            if (scaled !== soft) soft.recycle()

            latest?.recycle()
            latest = scaled
            latestAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Kare donusturulemedi: ${e.message}")
        } finally {
            runCatching { buffer.close() }
        }
    }

    // Kullanilabilirlik kontrolu bilerek burada DEGIL, Guard'da:
    // bu sinifi yuklemek API 30 tiplerine dokunuyor.
    companion object {
        private const val TAG = "A11yCapturer"

        /** Bu yastan eski kare kullanilmaz. */
        private const val STALE_AFTER_MS = 2_500L

        /** Bu sure gecince cevapsiz istek dusmus sayilir ve yenisi gonderilir. */
        private const val IN_FLIGHT_TIMEOUT_MS = 2_000L
    }
}
