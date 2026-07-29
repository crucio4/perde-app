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

    /** Sadece tani logu icin. Sentinel deger, ilk tick'te pkg null olsa bile yazsin. */
    private var lastLoggedPackage: String? = "<baslangic>"

    // --- Tani ---
    // adb olmadan "model ne goruyor" sorusunu cevaplayabilmek icin
    // olculenler MainActivity'nin durum ekranina yaziliyor.
    private val diag by lazy { getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE) }
    private var analyzedFrames = 0
    private var maxRaw = 0f
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

        // Model yuklenemediyse hicbir sey calismaz ve bu sessiz bir hata —
        // kullanicinin bunu gorebilmesi gerekiyor.
        diag.edit().putBoolean(D_MODEL_OK, classifier.isReady()).apply()

        workerThread = HandlerThread("perde-worker").also { it.start() }
        worker = Handler(workerThread.looper)

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

        // Projeksiyon izni olmadan bu servisin yapabilecegi hicbir sey yok.
        // Sistem START_NOT_STICKY sayesinde bizi null intent ile yeniden
        // baslatmiyor, yani bu dala normalde sadece bozuk bir cagri duser.
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Projeksiyon verisi yok (resultCode=$resultCode), servis durduruluyor")
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) {
            // SIRA ONEMLI: Android 14+ once mediaProjection turunde calisan bir
            // foreground service istiyor, getMediaProjection() ondan sonra.
            // Ters sirada SecurityException firlatiyor.
            startForegroundCompat()

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)?.apply {
                registerCallback(projectionCallback, worker)
            }

            if (projection == null) {
                Log.e(TAG, "getMediaProjection null dondu, servis durduruluyor")
                stopSelf()
                return START_NOT_STICKY
            }

            val metrics = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(it)
            }

            projection?.let { capturer = ScreenCapturer(it, metrics, worker) }
            worker.post(loop)
            Log.i(TAG, "Servis aktif (${metrics.widthPixels}x${metrics.heightPixels})")
        }

        // MediaProjection token'i servis yeniden baslatmasini atlatamaz.
        // START_STICKY olsaydi sistem bizi null intent ile diriltir ve
        // hicbir sey yakalayamayan, sadece bildirim tutan bir zombi kalirdi.
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

    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.e(TAG, "tick hatası", e) }
            worker.postDelayed(this, currentInterval)
        }
    }

    private fun tick() {
        val pkg = appWatcher.currentForegroundPackage()

        // Tani logu: yakalama hic baslamiyorsa sebebi neredeyse her zaman
        // burasidir — pkg null geliyordur (kullanim erisimi yok) ya da paket
        // EXCLUDED_PACKAGES icindedir. Sadece paket degisince yaziyor.
        if (pkg != lastLoggedPackage) {
            lastLoggedPackage = pkg
            Log.d(TAG, "on planda: $pkg  izlenecek=${appWatcher.shouldMonitor(pkg)}")
        }

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
            // Tani sayaclari uygulama basina sifirlanir, yoksa en yuksek skor
            // ilk oturumdan kalir ve sonraki testte ne oldugunu goremezsin.
            maxRaw = 0f
            analyzedFrames = 0
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

        // --- Tani kaydi ---
        analyzedFrames++
        val e = diag.edit()
            .putInt(D_FRAMES, analyzedFrames)
            .putFloat(D_LAST_RAW, raw)
            .putFloat(D_LAST_EMA, decision.smoothedScore)
            .putString(D_LAST_PKG, pkg ?: "-")
            .putString(D_SENS, Hassasiyet.aktif.name)
        if (raw > maxRaw) {
            maxRaw = raw
            e.putFloat(D_MAX_RAW, raw)
                .putString(D_MAX_PROBS, probs.joinToString(" ") { "%.2f".format(it) })
        }
        e.apply()

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

        // --- Tani anahtarlari (MainActivity de okuyor) ---
        const val DIAG_PREFS = "perde_diag"
        const val D_MODEL_OK = "model_ok"
        const val D_FRAMES = "frames"
        const val D_LAST_RAW = "last_raw"
        const val D_LAST_EMA = "last_ema"
        const val D_LAST_PKG = "last_pkg"
        const val D_MAX_RAW = "max_raw"
        const val D_MAX_PROBS = "max_probs"
        const val D_SENS = "sens"
    }
}
