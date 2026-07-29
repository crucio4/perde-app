package com.berke.perde

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Ana servis. Döngü:
 *
 *   öndeki uygulama izleniyor mu?
 *     hayır -> yakalamayı durdur, uyu (batarya)
 *     evet  -> kare al -> sınıflandır -> DetectionEngine -> blokla/kaldır
 */
class ScreenGuardService : Service() {

    private lateinit var classifier: NsfwClassifier
    private lateinit var appWatcher: ForegroundAppWatcher
    private lateinit var overlay: OverlayManager
    private val engine = DetectionEngine()

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private var lastWatchedPackage: String? = null
    private var secureBlackStreak = 0
    private val differ = FrameDiffer()
    private val blackDetector = BlackFrameDetector()
    private var lastProbs: FloatArray? = null
    private var calmFrames = 0
    private var currentInterval = Adaptive.FAST_INTERVAL_MS

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection sistem tarafından durduruldu")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        classifier = NsfwClassifier(this)
        appWatcher = ForegroundAppWatcher(this)
        overlay = OverlayManager(this)

        workerThread = HandlerThread("perde-worker").also { it.start() }
        worker = Handler(workerThread.looper)

        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && data != null && projection == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)?.apply {
                registerCallback(projectionCallback, worker)
            }

            val metrics = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(it)
            }

            projection?.let { capturer = ScreenCapturer(it, metrics, worker) }
            worker.post(loop)
            Log.i(TAG, "Servis aktif")
        }

        return START_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.e(TAG, "tick hatası", e) }
            worker.postDelayed(this, currentInterval)
        }
    }

    private fun tick() {
        val pkg = appWatcher.currentForegroundPackage()

        // Kendi overlay'imiz açıkken öndeki paket değişmiş görünebilir; blok
        // durumundayken paket kontrolünü atla.
        if (engine.currentState() == DetectionEngine.State.CLEAR &&
            !appWatcher.shouldMonitor(pkg)
        ) {
            if (capturer?.isRunning() == true) {
                capturer?.stop()
                engine.reset()
                differ.reset()
                lastProbs = null
            }
            lastWatchedPackage = null
            return
        }

        // İzlenen uygulama değiştiyse durumu sıfırla
        if (pkg != lastWatchedPackage && engine.currentState() == DetectionEngine.State.CLEAR) {
            engine.reset()
            lastWatchedPackage = pkg
        }

        if (capturer?.isRunning() != true && !(capturer?.start() ?: false)) return

        val frame: Bitmap = capturer?.grabFrame() ?: return

        // --- FLAG_SECURE kör noktası ---
        // Kare tamamen siyahsa sınıflandırmanın anlamı yok: gizli sekme
        // ya da korumalı içerik. "Göremiyorum" durumunu sinyal say.
        val black = blackDetector.analyze(frame)
        if (black.isSecureBlack) {
            frame.recycle()
            secureBlackStreak++
            if (SecurePolicy.BLOCK_ON_SECURE_BLACK &&
                secureBlackStreak >= SecurePolicy.SECURE_BLACK_FRAMES_REQUIRED &&
                !overlay.isShowing()
            ) {
                Log.i(TAG, "FLAG_SECURE tespit edildi (${secureBlackStreak} kare) -> blok")
                overlay.show(Motivation.pick(this))
            }
            return
        }
        secureBlackStreak = 0
        if (overlay.isShowing() && engine.currentState() == DetectionEngine.State.CLEAR) {
            overlay.hide()
        }

        // --- Kare farki: ekran degismediyse inference'i atla ---
        // En buyuk batarya kazanci burada. Duragan ekranda inference
        // sayisi ~%90 dusuyor cunku sonuc zaten ayni cikacak.
        val diff = differ.check(frame)
        val probs: FloatArray? = if (!diff.changed && lastProbs != null) {
            lastProbs
        } else {
            classifier.classify(frame)?.also { lastProbs = it }
        }
        frame.recycle()
        if (probs == null) return

        val raw = engine.weighScore(probs)
        val decision = engine.update(raw, System.currentTimeMillis())

        if (decision.justChanged) {
            when (decision.state) {
                DetectionEngine.State.BLOCKED -> overlay.show(Motivation.pick(this))
                DetectionEngine.State.CLEAR -> overlay.hide()
            }
        }

        // --- Uyarlanabilir ornekleme ---
        // Skor uzun suredir dusukse aralik uzuyor. Riskli bolgeye
        // yaklasinca aninda hizlaniyor, yani tepki suresi bozulmuyor.
        if (Adaptive.ENABLED) {
            if (decision.smoothedScore < Adaptive.CALM_SCORE) calmFrames++ else calmFrames = 0
            currentInterval = if (calmFrames >= Adaptive.CALM_AFTER_FRAMES)
                Adaptive.SLOW_INTERVAL_MS else Adaptive.FAST_INTERVAL_MS
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
        worker.removeCallbacksAndMessages(null)
        overlay.hide()
        capturer?.stop()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        classifier.close()
        workerThread.quitSafely()
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
    }
}
