package com.berke.perde

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Tespit dongusu. Eskiden ScreenGuardService'in icindeydi; iki ayri
 * kare kaynagi (MediaProjection ve erisilebilirlik) ayni mantigi
 * kullanabilsin diye buraya alindi.
 *
 *   ondeki uygulama izleniyor mu?
 *     hayir -> yakalamayi durdur, uyu (batarya)
 *     evet  -> kare al -> siniflandir -> DetectionEngine -> blokla/kaldir
 *
 * @param sourceFactory kaynak, dongunun kendi worker Handler'ini
 *        kullanabilsin diye fabrika olarak aliniyor
 */
class DetectionLoop(
    private val ctx: Context,
    sourceFactory: (Handler) -> FrameSource
) {

    private val thread = HandlerThread("perde-worker").also { it.start() }
    private val worker = Handler(thread.looper)

    private val source: FrameSource = sourceFactory(worker)

    private val classifier = NsfwClassifier(ctx)
    private val appWatcher = ForegroundAppWatcher(ctx)
    private val overlay = OverlayManager(ctx)
    private val engine = DetectionEngine()
    private val differ = FrameDiffer()
    private val blackDetector = BlackFrameDetector()

    private val diag = ctx.getSharedPreferences(
        ScreenGuardService.DIAG_PREFS, Context.MODE_PRIVATE
    )

    private var lastWatchedPackage: String? = null
    private var lastLoggedPackage: String? = "<baslangic>"
    private var lastProbs: FloatArray? = null
    private var secureBlackStreak = 0
    private var secureErrorStreak = 0
    private var starvedTicks = 0
    private var calmFrames = 0
    private var currentInterval = Adaptive.FAST_INTERVAL_MS

    private var analyzedFrames = 0
    private var maxRaw = 0f

    /** Bu uygulamada bu oturumda kaç kare okunabildi. Paket değişince sıfırlanır. */
    private var readableFrames = 0

    /**
     * Bir kez "yeterince okunabilir" olmuş paketler.
     *
     * Bu kümedeki bir paket sonradan ekranı gizlemeye başlarsa, bu bilinçli
     * bir gizli moda geçiştir → bloklanır (Reddit anonim mod, Telegram gizli
     * sohbet). Kümeye hiç girmemiş paketler baştan sona korumalıdır
     * (bankacılık, şifre yöneticisi) → asla bloklanmaz.
     *
     * Bilerek bellekte, kalıcıya yazılmıyor: yanlışlıkla kümeye girmiş bir
     * uygulama sonsuza kadar orada kalmasın. Yeniden kurulan döngüde
     * uygulamanın birkaç saniye normal kullanılması kuralı zaten yeniden
     * hazırlıyor.
     */
    private val gecisAdaylari = mutableSetOf<String>()

    /** Blok ekranı ne zaman açıldı. Zorunlu kalkma süresini ölçmek için. */
    private var overlayShownAt = 0L

    /** Blok kalktıktan sonra bu ana kadar yeniden bloklanmaz. */
    private var blockCooldownUntil = 0L

    /** En son ne zaman kullanılabilir kare işlendi. Gözcü bunu izliyor. */
    private var lastGoodFrameAt = 0L

    private val runnable = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.e(TAG, "tick hatası", e) }
            worker.postDelayed(this, currentInterval)
        }
    }

    fun start() {
        // Servis MainActivity acilmadan da baslayabiliyor (erisilebilirlik
        // servisi yeniden baglaninca). Hassasiyet statik bir alanda tutuldugu
        // icin o durumda varsayilana donuyordu — prefs'ten okumak sart.
        Hassasiyet.load(ctx)
        SecurePolicy.load(ctx)

        diag.edit()
            .putBoolean(ScreenGuardService.D_MODEL_OK, classifier.isReady())
            .putString(ScreenGuardService.D_MODEL_ERR, classifier.lastError ?: "-")
            .apply()

        worker.post(runnable)
        Log.i(TAG, "Döngü başladı, kaynak=${source.javaClass.simpleName}")
    }

    fun stop() {
        worker.removeCallbacksAndMessages(null)
        overlay.hide()
        source.stop()
        classifier.close()
        thread.quitSafely()
        Log.i(TAG, "Döngü durdu")
    }

    /**
     * Blok ekranını açar. Tek giriş noktası: soğuma kontrolü ve açılış
     * zamanının kaydı burada, yoksa çağrı yerlerinden biri unutulur.
     */
    private fun blokla(sebep: String) {
        val now = System.currentTimeMillis()
        if (now < blockCooldownUntil) return
        if (overlay.isShowing()) return
        overlayShownAt = now
        Log.i(TAG, "BLOK: $sebep")
        overlay.show(Motivation.pick(ctx))
    }

    private fun blogonKaldir(sebep: String) {
        overlay.hide()
        blockCooldownUntil = System.currentTimeMillis() + Config.COOLDOWN_MS
        engine.reset()
        differ.reset()
        lastProbs = null
        secureBlackStreak = 0
        secureErrorStreak = 0
        starvedTicks = 0

        // Blok boyunca tick() en başta dönüyordu, yani kaynağa 8 saniye hiç
        // dokunulmadı. O aradan sonra kaynağı kaldığı yerden sürdürmek
        // güvenilir değil: kullanıcı "bir kez bloklandıktan sonra bir daha
        // tespit etmiyor, Başlat'a basınca düzeliyor" diye bildirdi ve
        // Başlat'ın yaptığı tam olarak buydu. Baştan kurmak bir tick'e mal
        // oluyor, karşılığında bütün bir takılma sınıfı ortadan kalkıyor.
        source.stop()
        lastGoodFrameAt = 0L

        Log.i(TAG, "Blok kaldırıldı ($sebep), kaynak yeniden kurulacak")
    }

    private fun tick() {
        // --- Blok ekranı açıkken ---
        // KRİTİK: overlay açıkken yakalanan kare artık ekrandaki içerik
        // değil, KENDİ opak katmanımız. Onu analiz etmek anlamsız — dahası
        // siyah kare tespiti onu "korumalı içerik" sanıp erken dönüyordu ve
        // blok kaldırma kodu o dönüşün altında kaldığı için hiç çalışmıyordu.
        // Sonuç: kullanıcı telefonunda kilitli kalıyordu; geri, ana ekran ve
        // bildirim paneli overlay'in altında olduğu için çıkış yolu da yoktu.
        //
        // Bu yüzden overlay açıkken tek yapılan işi süreyle bitirmek.
        if (overlay.isShowing()) {
            if (System.currentTimeMillis() - overlayShownAt >= Config.MAX_BLOCK_DURATION_MS) {
                blogonKaldir("süre doldu")
            }
            return
        }

        val pkg = appWatcher.currentForegroundPackage()

        if (pkg != lastLoggedPackage) {
            lastLoggedPackage = pkg
            Log.d(TAG, "ön planda: $pkg  izlenecek=${appWatcher.shouldMonitor(pkg)}")
        }

        // Kendi overlay'imiz açıkken öndeki paket değişmiş görünebilir; blok
        // durumundayken paket kontrolünü atla.
        if (engine.currentState() == DetectionEngine.State.CLEAR &&
            !appWatcher.shouldMonitor(pkg)
        ) {
            if (source.isRunning()) {
                source.stop()
                engine.reset()
                differ.reset()
                lastProbs = null
                starvedTicks = 0
                secureBlackStreak = 0
                secureErrorStreak = 0
                lastGoodFrameAt = 0L
            }
            lastWatchedPackage = null
            return
        }

        if (pkg != lastWatchedPackage && engine.currentState() == DetectionEngine.State.CLEAR) {
            engine.reset()
            lastWatchedPackage = pkg
            maxRaw = 0f
            analyzedFrames = 0
            // Isınma sayacı uygulama başına: yeni uygulamada sıfırdan başla,
            // yoksa bir uygulamada biriken okunabilirlik diğerine taşınır.
            readableFrames = 0
        }

        if (!source.isRunning() && !source.start()) return

        // --- Gözcü ---
        // Kaynak açık ama kare akmıyorsa boru hattı takılmıştır. Asenkron
        // yapı birçok şekilde takılabiliyor (geri çağrı düşer, oturum
        // bayatlar, pencere geçişinde istek kaybolur); her birini ayrı
        // kovalamak yerine kaynağı baştan kuruyoruz.
        //
        // Korumalı içerikte kare gelmemesi normaldir, orada gözcü susmalı —
        // yoksa bankacılık uygulamasındayken boşuna durdurup başlatır.
        if (!source.isSecureBlocked() && lastGoodFrameAt != 0L &&
            System.currentTimeMillis() - lastGoodFrameAt > Config.SOURCE_WATCHDOG_MS
        ) {
            Log.w(TAG, "Kaynak ${Config.SOURCE_WATCHDOG_MS}ms'dir kare vermiyor, yeniden kuruluyor")
            source.stop()
            source.start()
            differ.reset()
            lastProbs = null
            lastGoodFrameAt = 0L
            return
        }

        // "Göremiyorum" durumu iki halde blok sebebi sayılır:
        //
        //   1. Uygulama bir tarayıcı  -> gizli sekme
        //   2. Uygulama daha önce okunabiliyordu, sonra gizlemeye başladı
        //      -> bilinçli gizli mod geçişi (Reddit anonim, Telegram gizli)
        //
        // Bunun dışında kalan her şey — bankacılık, şifre yöneticisi, MDM,
        // 2FA — baştan sona korumalıdır, hiç okunamamıştır, dolayısıyla
        // kümeye hiç girmez ve asla bloklanmaz. Ayrım için isim listesi
        // tutmaya gerek yok: uygulamanın kendi davranışı ayırıyor.
        val gecisYapti = pkg != null && pkg in gecisAdaylari
        val korumaliBlokla = SecurePolicy.blockOnSecure &&
                (Config.isBrowser(pkg) || gecisYapti)

        // Tanı etiketi: "bankam neden bloklanmadı / gizli sekme neden
        // bloklandı" sorusunun cevabı. Üç dalda da yazılıyor, yoksa hiç
        // okunamayan bir uygulamada ekranda önceki uygulamadan kalan
        // değer görünür ve yanıltır.
        val kuralEtiketi = when {
            !SecurePolicy.blockOnSecure -> "kapali"
            Config.isBrowser(pkg) -> "tarayici"
            gecisYapti -> "gecis izleniyor"
            else -> "muaf ($readableFrames/${SecurePolicy.SECURE_WARMUP_FRAMES})"
        }

        // SIRA KRİTİK: grabFrame() aynı zamanda bir sonraki ekran görüntüsü
        // isteğini başlatan pompadır. Eskiden isSecureBlocked() kontrolü
        // bunun ÜSTÜNDEYDİ ve doğru çıkınca return ediyordu — yani tek bir
        // başarısız istek kalıcı "korumalı" durumuna kilitliyordu: bir daha
        // hiç istek gönderilmiyor, durum hiç güncellenmiyor, tespit sessizce
        // ölüyordu. Pompa artık her tick'te koşulsuz çalışıyor.
        //
        // NOT: dönen Bitmap kaynağa ait, burada recycle EDİLMEZ.
        val frame: Bitmap? = source.grabFrame()

        // --- FLAG_SECURE, 1. biçim: kaynak açıkça "göremiyorum" diyor ---
        // takeScreenshot ERROR_TAKE_SCREENSHOT_SECURE_WINDOW döndürüyor.
        // Bu tahmin değil kesin bilgi, o yüzden eşik düşük tutuldu.
        if (source.isSecureBlocked()) {
            secureErrorStreak++
            if (korumaliBlokla &&
                secureErrorStreak >= SecurePolicy.SECURE_ERROR_FRAMES_REQUIRED &&
                !overlay.isShowing()
            ) {
                blokla("korumalı içerik, kesin sinyal ($secureErrorStreak)")
            }
            diag.edit()
                .putInt(ScreenGuardService.D_STARVED, secureErrorStreak)
                .putString(ScreenGuardService.D_SECURE_RULE, kuralEtiketi)
                .putString(ScreenGuardService.D_LAST_PKG, pkg ?: "-")
                .apply()
            return
        }
        secureErrorStreak = 0

        // --- FLAG_SECURE, 2. biçim: hiç kare gelmiyor ---
        // MediaProjection gizli sekmede siyah kare değil, HİÇ kare üretmiyor.
        if (frame == null) {
            starvedTicks++
            if (korumaliBlokla &&
                starvedTicks >= SecurePolicy.SECURE_STARVED_TICKS_REQUIRED &&
                !overlay.isShowing()
            ) {
                blokla("hiç kare gelmiyor ($starvedTicks tick)")
            }
            diag.edit()
                .putInt(ScreenGuardService.D_STARVED, starvedTicks)
                .putString(ScreenGuardService.D_SECURE_RULE, kuralEtiketi)
                .putString(ScreenGuardService.D_LAST_PKG, pkg ?: "-")
                .apply()
            return
        }
        starvedTicks = 0

        // --- FLAG_SECURE, 3. biçim: kare siyah geliyor ---
        val black = blackDetector.analyze(frame)
        if (black.isSecureBlack) {
            secureBlackStreak++
            if (korumaliBlokla &&
                secureBlackStreak >= SecurePolicy.SECURE_BLACK_FRAMES_REQUIRED &&
                !overlay.isShowing()
            ) {
                blokla("FLAG_SECURE siyah kare ($secureBlackStreak)")
            }
            return
        }
        secureBlackStreak = 0
        lastGoodFrameAt = System.currentTimeMillis()

        // Buraya ulaştıysak kare gerçekten okunabildi (null değil, siyah değil).
        // Isınma eşiğini geçen uygulama "geçiş adayı" oluyor: bundan sonra
        // ekranını gizlemeye başlarsa bu bilinçli bir gizli mod geçişidir.
        readableFrames++
        if (pkg != null && readableFrames >= SecurePolicy.SECURE_WARMUP_FRAMES) {
            if (gecisAdaylari.add(pkg)) {
                Log.d(TAG, "$pkg okunabilir olarak işaretlendi ($readableFrames kare)")
            }
        }

        if (overlay.isShowing() && engine.currentState() == DetectionEngine.State.CLEAR) {
            overlay.hide()
        }

        // --- Kare farkı: ekran değişmediyse inference'i atla ---
        val diff = differ.check(frame)
        val probs: FloatArray? = if (!diff.changed && lastProbs != null) {
            lastProbs
        } else {
            classifier.classify(frame)?.also { lastProbs = it }
        }
        if (probs == null) return

        val raw = engine.weighScore(probs)
        val decision = engine.update(raw, System.currentTimeMillis())

        // --- Tanı kaydı ---
        analyzedFrames++
        val e = diag.edit()
            .putInt(ScreenGuardService.D_FRAMES, analyzedFrames)
            .putFloat(ScreenGuardService.D_LAST_RAW, raw)
            .putFloat(ScreenGuardService.D_LAST_EMA, decision.smoothedScore)
            .putString(ScreenGuardService.D_LAST_PKG, pkg ?: "-")
            .putString(ScreenGuardService.D_SENS, Hassasiyet.aktif.name)
            .putString(ScreenGuardService.D_WINDOW, engine.windowStatus())
            .putString(ScreenGuardService.D_SOURCE, source.javaClass.simpleName)
            .putInt(ScreenGuardService.D_STARVED, 0)
            .putString(ScreenGuardService.D_SECURE_RULE, kuralEtiketi)
        if (raw > maxRaw) {
            maxRaw = raw
            e.putFloat(ScreenGuardService.D_MAX_RAW, raw)
                .putString(
                    ScreenGuardService.D_MAX_PROBS,
                    probs.joinToString(" ") { "%.2f".format(it) }
                )
        }
        e.apply()

        if (decision.justChanged) {
            when (decision.state) {
                DetectionEngine.State.BLOCKED -> blokla("skor %.3f".format(raw))
                DetectionEngine.State.CLEAR -> overlay.hide()
            }
        }

        // --- Uyarlanabilir örnekleme ---
        if (Adaptive.ENABLED) {
            if (decision.smoothedScore < Adaptive.CALM_SCORE) calmFrames++ else calmFrames = 0
            currentInterval = if (calmFrames >= Adaptive.CALM_AFTER_FRAMES)
                Adaptive.SLOW_INTERVAL_MS else Adaptive.FAST_INTERVAL_MS
        }
    }

    companion object { private const val TAG = "DetectionLoop" }
}
