package com.berke.perde

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * MediaProjection tabanli yakalama icin foreground service.
 *
 * Bu yol artik YEDEK. Tercih edilen yol erisilebilirlik servisinin
 * takeScreenshot() cagrisi (bkz. A11yCapturer); sebepleri FrameSource'ta.
 * Burasi yalnizca API 30 altinda ya da erisilebilirlik servisi kapaliyken
 * devreye giriyor.
 *
 * Tespit mantigi DetectionLoop'ta — iki kaynak da ayni koda basiyor.
 */
class ScreenGuardService : Service() {

    private var projection: MediaProjection? = null
    private var loop: DetectionLoop? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection sistem tarafından durduruldu")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // DIKKAT: burada startForeground() CAGIRMA.
        // Manifest bu servisi mediaProjection turunde bildiriyor ve Android 14,
        // o turdeki bir foreground service'i gecerli projeksiyon token'i olmadan
        // baslatmayi SecurityException ile reddediyor. Token onStartCommand'a
        // gelen intent ile geliyor, yani onCreate'te henuz yok.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Projeksiyon verisi yok (resultCode=$resultCode), servis durduruluyor")
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) {
            // SIRA ONEMLI: Android 14+ once mediaProjection turunde calisan bir
            // foreground service istiyor, getMediaProjection() ondan sonra.
            startForegroundCompat()

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            if (proj == null) {
                Log.e(TAG, "getMediaProjection null dondu, servis durduruluyor")
                stopSelf()
                return START_NOT_STICKY
            }
            proj.registerCallback(projectionCallback, android.os.Handler(mainLooper))
            projection = proj

            val metrics = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(it)
            }

            loop = DetectionLoop(this) { worker ->
                ScreenCapturer(proj, metrics, worker)
            }.also { it.start() }

            Log.i(TAG, "Servis aktif (${metrics.widthPixels}x${metrics.heightPixels})")
        }

        // MediaProjection token'i servis yeniden baslatmasini atlatamaz.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Perde",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        loop?.stop()
        loop = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenGuardService"
        private const val CHANNEL_ID = "perde_guard"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.berke.perde.STOP"

        // --- Tani anahtarlari (MainActivity ve DetectionLoop de kullaniyor) ---
        const val DIAG_PREFS = "perde_diag"
        const val D_MODEL_OK = "model_ok"
        const val D_MODEL_ERR = "model_err"
        const val D_FRAMES = "frames"
        const val D_LAST_RAW = "last_raw"
        const val D_LAST_EMA = "last_ema"
        const val D_LAST_PKG = "last_pkg"
        const val D_MAX_RAW = "max_raw"
        const val D_MAX_PROBS = "max_probs"
        const val D_SENS = "sens"
        const val D_STARVED = "starved"
        const val D_WINDOW = "window"
        const val D_SOURCE = "source"
        /** Piksel kanalı neden kapalı: "korumalı pencere" / "siyah kare" / ... */
        const val D_BLIND = "blind"
        /**
         * Kaynağın son hata sebebi — "kare yok"un ARDINDAKİ sebep.
         *
         * D_BLIND yalnızca kare gelmediğini söylüyor; hata kodu logcat'e
         * yazılıyordu ve bazı üreticiler üçüncü taraf loglarını bastırdığı
         * için cihazda hiç okunamıyordu. Sebep artık burada.
         */
        const val D_SOURCE_ERR = "source_err"
        /** Ten/renk kanıtı özeti — çöp adam yanlış pozitifinin teşhisi. */
        const val D_IMAGE = "image_ev"
        /** İçerik analizi skoru (0..1). */
        const val D_TEXT_RAW = "text_raw"
        /** İçerik analizi özeti: kaç güçlü/belirsiz/destek/ters eşleşme. */
        const val D_TEXT_INFO = "text_info"

        /**
         * Son tick'in saati. Döngünün YAŞADIĞINI gösteren tek alan.
         *
         * D_FRAMES bunu cevaplamıyordu: yalnızca analize kadar gelinen
         * tick'lerde artıyor, dolayısıyla "izlenmeyen uygulamadayız" ile
         * "döngü öldü" orada aynı görünüyordu. Tespitin durduğu her
         * şikayette ilk bakılacak yer burası — sayı büyümüyorsa sorun
         * eşiklerde değil, döngünün kendisinde.
         */
        const val D_HEARTBEAT = "heartbeat"
        /** Soğumanın bitmesine kalan süre (ms). */
        const val D_COOLDOWN = "cooldown"
        /** Son bloğun saati. */
        const val D_LAST_BLOCK = "last_block"
        /** Son bloğu hangi kanalın ve hangi skorun tetiklediği. */
        const val D_LAST_BLOCK_WHY = "last_block_why"
    }
}
