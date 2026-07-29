package com.berke.perde

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log

/**
 * MediaProjection üzerinden ekran karesi alır.
 *
 * VirtualDisplay sürekli açık kalmaz — start()/stop() ile yönetilir.
 * Sadece izlenen uygulama öndeyken açılır (bkz. ForegroundAppWatcher).
 * Bu batarya farkının büyük kısmını buradan geliyor.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
    private val handler: Handler
) {

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var running = false

    /**
     * En son basariyla alinan kare.
     *
     * VirtualDisplay yalnizca ekran icerigi DEGISTIGINDE kare uretir, yani
     * kullanici kaydirmayi birakip icerige bakmaya basladigi anda
     * acquireLatestImage() null donmeye baslar. Onbellek olmadan tespit tam
     * o anda duruyordu: pencere oylamasi dolmuyor, blok hic gelmiyordu.
     *
     * Yeni kare yoksa ekranda hala ayni icerik duruyor demektir, dolayisiyla
     * son kareyi yeniden dondurmek hem dogru hem de bedava (FrameDiffer
     * degismedigini gorup inference'i zaten atliyor).
     */
    private var lastFrame: Bitmap? = null

    private val width = (metrics.widthPixels / Config.CAPTURE_DOWNSCALE).coerceAtLeast(1)
    private val height = (metrics.heightPixels / Config.CAPTURE_DOWNSCALE).coerceAtLeast(1)

    fun start(): Boolean {
        if (running) return true
        return try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "PerdeCapture",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler
            )
            running = true
            Log.i(TAG, "Yakalama başladı ${width}x$height")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Yakalama başlatılamadı: ${e.message}", e)
            stop()
            false
        }
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        lastFrame?.recycle()
        lastFrame = null
        running = false
        Log.i(TAG, "Yakalama durdu")
    }

    fun isRunning() = running

    /**
     * Ekrandaki guncel kare.
     *
     * SAHIPLIK: donen Bitmap bu sinifa aittir, cagiran taraf recycle ETMEZ.
     * Onbelleklenip yeniden dondurulebilecegi icin disaridan geri donusume
     * sokulmasi kullanim sonrasi serbest birakilmis bellek demek olurdu.
     * Temizligi stop() yapiyor.
     *
     * @return ekranda duran icerik; yakalama baslayali hic kare gelmediyse null
     */
    fun grabFrame(): Bitmap? {
        val fresh = acquireFresh()
        if (fresh != null) {
            lastFrame?.recycle()
            lastFrame = fresh
        }
        return lastFrame
    }

    /** Hic kare alinabildi mi? Kare gelmiyorsa icerik FLAG_SECURE olabilir. */
    fun hasFrame(): Boolean = lastFrame != null

    private fun acquireFresh(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val bmpWidth = width + rowPadding / plane.pixelStride

            val bmp = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)

            if (bmpWidth != width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                bmp.recycle()
                cropped
            } else bmp
        } catch (e: Exception) {
            Log.e(TAG, "Kare alınamadı: ${e.message}")
            null
        } finally {
            image?.close()
        }
    }

    companion object { private const val TAG = "ScreenCapturer" }
}
