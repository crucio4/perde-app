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
        running = false
        Log.i(TAG, "Yakalama durdu")
    }

    fun isRunning() = running

    /** En son kareyi Bitmap olarak döndürür. Kullanıcı recycle etmeli. */
    fun grabFrame(): Bitmap? {
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
