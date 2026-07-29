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
 * API asenkron, dongu senkron. Koprü: her grabFrame() cagrisi yeni bir
 * istek baslatir ve EN SON tamamlanan kareyi dondurur. 1 fps'de bu bir
 * tick gecikme demek, kabul edilebilir.
 */
@RequiresApi(Build.VERSION_CODES.R)
class A11yCapturer(private val executor: Executor) : FrameSource {

    private var running = false

    @Volatile private var latest: Bitmap? = null
    @Volatile private var inFlight = false
    @Volatile private var secureBlocked = false

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

    override fun grabFrame(): Bitmap? {
        if (running) request()
        return latest
    }

    private fun request() {
        val svc = PerdeAccessibilityService.instance ?: return
        if (inFlight) return
        inFlight = true

        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        inFlight = false
                        secureBlocked = false
                        store(result)
                    }

                    override fun onFailure(errorCode: Int) {
                        inFlight = false
                        // Kor noktanin kesin sinyali. MediaProjection'da bu
                        // bilgi hic yoktu, sessizce hicbir sey olmuyordu.
                        secureBlocked =
                            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                        Log.w(TAG, "takeScreenshot basarisiz: kod=$errorCode secure=$secureBlocked")
                    }
                }
            )
        } catch (e: Exception) {
            inFlight = false
            Log.e(TAG, "takeScreenshot cagrilamadi: ${e.message}")
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Kare donusturulemedi: ${e.message}")
        } finally {
            runCatching { buffer.close() }
        }
    }

    // Kullanilabilirlik kontrolu bilerek burada DEGIL, Guard'da:
    // bu sinifi yuklemek API 30 tiplerine dokunuyor.
    companion object { private const val TAG = "A11yCapturer" }
}
