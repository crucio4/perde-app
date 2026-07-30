package com.berke.perde

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Tespit döngüsü — İKİ KANAL.
 *
 *   ┌─ PİKSEL  : kare al -> NsfwClassifier -> ImageEvidence ile doğrula
 *   │            FLAG_SECURE'da körleşir.
 *   └─ İÇERİK  : erişilebilirlik ağacından metin -> ContentAnalyzer
 *                FLAG_SECURE'dan ETKİLENMEZ.
 *
 * İkisi de aynı ölçeğe eşlenip tek bir DetectionEngine'e giriyor; yani
 * yanlış-pozitif katmanları (EMA, pencere oylaması, histerezis, soğuma)
 * her iki kanal için de aynen işliyor.
 *
 * GİZLİ SEKME BURADA ÇÖZÜLÜYOR. Eski yaklaşım "tarayıcıda ekranı
 * göremiyorsam gizli sekmedir, blokla" idi — bu bir tahmindi ve
 * bankacılık/DRM gibi meşru FLAG_SECURE kullanıcılarını ayırmak için
 * isim listesine ya da davranış tahminine muhtaçtı. Artık gerek yok:
 * ekranı göremesek de OKUYABİLİYORUZ, kararı okuduğumuz şey veriyor.
 * Bankacılık uygulaması da okunuyor; okunan şey bankacılık olduğu için
 * skoru sıfır çıkıyor ve bloklanmıyor.
 *
 * @param sourceFactory kaynak, döngünün kendi worker Handler'ını
 *        kullanabilsin diye fabrika olarak alınıyor
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
    private val imageEvidence = ImageEvidence()
    private val analyzer = ContentAnalyzer()

    private val diag = ctx.getSharedPreferences(
        ScreenGuardService.DIAG_PREFS, Context.MODE_PRIVATE
    )

    private val power = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    private var lastWatchedPackage: String? = null
    private var lastLoggedPackage: String? = "<baslangic>"
    private var lastProbs: FloatArray? = null
    private var lastEvidence = 1f
    private var lastImageLabel = "-"
    /** Kac ardisik tick'tir piksel kanali kor. Yalnizca tani icin. */
    private var korTicks = 0
    private var calmFrames = 0
    private var currentInterval = Adaptive.FAST_INTERVAL_MS

    private var analyzedFrames = 0
    private var maxRaw = 0f

    /** Kör olduğumuz VE hiç metin de okuyamadığımız ardışık tick sayısı. */
    private var blindMuteTicks = 0

    /** Blok ekranı ne zaman açıldı. Zorunlu kalkma süresini ölçmek için. */
    private var overlayShownAt = 0L

    /** Blok kalktıktan sonra bu ana kadar yeniden bloklanmaz. */
    private var blockCooldownUntil = 0L

    /** En son ne zaman kullanılabilir kare işlendi. Gözcü bunu izliyor. */
    private var lastGoodFrameAt = 0L

    /**
     * Kaynağın en son ne zaman kurulduğu.
     *
     * Gözcünün "hiç kare gelmedi" halini ölçebilmesi için şart: ilk kare
     * hiç gelmezse lastGoodFrameAt sıfır kalıyor ve kıyaslanacak bir an
     * olmuyordu.
     */
    private var sourceStartedAt = 0L

    /** Peş peşe kaç kez kare alamadan yeniden kurduk. Geri çekilme için. */
    private var sourceRebuilds = 0

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
        ScreenReader.clear()
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
        lastEvidence = 1f
        korTicks = 0
        blindMuteTicks = 0

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

        // --- Ekran kapalıyken hiçbir şey yapma ---
        // Kapalı ekranda takeScreenshot ya hata veriyor ya siyah kare
        // döndürüyor; bakılmayan bir ekranı analiz etmenin de anlamı yok.
        if (!power.isInteractive) {
            korTicks = 0
            blindMuteTicks = 0
            lastGoodFrameAt = 0L
            ScreenReader.clear()
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
                lastEvidence = 1f
                korTicks = 0
                blindMuteTicks = 0
                lastGoodFrameAt = 0L
                ScreenReader.clear()
            }
            lastWatchedPackage = null
            return
        }

        if (pkg != lastWatchedPackage && engine.currentState() == DetectionEngine.State.CLEAR) {
            engine.reset()
            lastWatchedPackage = pkg
            maxRaw = 0f
            analyzedFrames = 0
            blindMuteTicks = 0
        }

        if (!source.isRunning()) {
            if (!source.start()) return
            sourceStartedAt = System.currentTimeMillis()
        }

        // ==============================================================
        // KANAL 2 — İÇERİK
        // Piksel kanalından ÖNCE ve ondan bağımsız. Kare gelsin gelmesin
        // her tick'te çalışıyor: gizli sekmede tek kanal bu.
        // ==============================================================
        ScreenReader.requestRefresh()
        val content = ScreenReader.latest
        val contentFresh = content.pkg.isNotEmpty() && content.pkg == pkg &&
                SystemClock.uptimeMillis() - content.at <= Config.CONTENT_STALE_MS
        val textVerdict = if (contentFresh && !content.isEmpty) {
            analyzer.analyze(content)
        } else {
            ContentAnalyzer.Verdict.NONE
        }
        val textComponent = mapTextScore(textVerdict.score)

        // --- Gözcü ---
        // Kaynak açık ama kare akmıyorsa boru hattı takılmıştır. Asenkron
        // yapı birçok şekilde takılabiliyor (geri çağrı düşer, oturum
        // bayatlar, pencere geçişinde istek kaybolur); her birini ayrı
        // kovalamak yerine kaynağı baştan kuruyoruz.
        //
        // Korumalı içerikte kare gelmemesi normaldir, orada gözcü susmalı.
        //
        // Referans an: son iyi kare, hiç gelmediyse kaynağın kurulduğu an.
        // Eski koşul `lastGoodFrameAt != 0L` şartına bağlıydı, yani gözcü
        // "çalışıyordu, durdu" halinden kurtarabiliyor ama "hiç çalışmadı"
        // halinden kurtaramıyordu. İlk karesi hiç gelmeyen bir cihazda
        // kurtarma mekanizması ömrü boyunca hiç tetiklenmiyordu.
        val simdi = System.currentTimeMillis()
        val referans = if (lastGoodFrameAt != 0L) lastGoodFrameAt else sourceStartedAt
        val karesizSure = if (referans == 0L) 0L else simdi - referans

        if (!source.isSecureBlocked() && karesizSure > gozcuAraligi()) {
            sourceRebuilds++
            Log.w(TAG, "Kaynak ${karesizSure}ms'dir kare vermiyor ($sourceRebuilds. kurulum), sebep=${source.lastError()}")
            source.stop()
            source.start()
            sourceStartedAt = System.currentTimeMillis()
            differ.reset()
            lastProbs = null
            lastEvidence = 1f
            lastGoodFrameAt = 0L
            return
        }

        // ==============================================================
        // KANAL 1 — PİKSEL
        // ==============================================================
        // SIRA KRİTİK: grabFrame() aynı zamanda bir sonraki ekran görüntüsü
        // isteğini başlatan pompadır, koşulsuz çalışmalı. Eskiden korumalı
        // durum kontrolü bunun üstündeydi ve tek bir başarısız istek
        // yakalamayı kalıcı olarak öldürüyordu.
        //
        // NOT: dönen Bitmap kaynağa ait, burada recycle EDİLMEZ.
        val frame: Bitmap? = source.grabFrame()

        // Körlüğün üç biçimi. Hiçbiri tek başına blok sebebi DEĞİL —
        // yalnızca "piksel kanalı kapalı" demek. Ayrı ayrı isimleri var
        // çünkü tanı ekranında hangisi olduğunu görmek gerekiyor:
        // korumalı pencere gizli sekmedir, kare yok boru hattı takılmasıdır.
        val black = frame != null && blackDetector.analyze(frame).isSecureBlack
        val korBicim: String? = when {
            source.isSecureBlocked() -> "korumalı pencere"
            frame == null -> "kare yok"
            black -> "siyah kare"
            else -> null
        }
        if (korBicim == null) {
            korTicks = 0
            lastGoodFrameAt = System.currentTimeMillis()
            // Kaynak toparlandı; geri çekilme sayacı sıfırlanmalı, yoksa
            // bir kez geri çekilen kaynak sonsuza kadar yavaş kalır.
            sourceRebuilds = 0
        } else {
            korTicks++
        }

        var visualComponent = 0f
        var probs: FloatArray? = null

        if (korBicim == null && frame != null) {
            // --- Kare farkı: ekran değişmediyse inference'i atla ---
            val diff = differ.check(frame)
            if (!diff.changed && lastProbs != null) {
                probs = lastProbs
            } else {
                probs = classifier.classify(frame)?.also { lastProbs = it }
                // Ten/renk kanıtı da kare başına: değişmeyen karede
                // yeniden ölçmenin anlamı yok.
                val stats = imageEvidence.analyze(frame)
                lastEvidence = stats.multiplier()
                lastImageLabel = stats.label()
            }
            if (probs != null) {
                // Modelin iddiası pikselle destekleniyor mu? Çöp adam
                // burada eleniyor: çizgi çizimde çarpan 0.
                visualComponent = engine.weighScore(probs) * lastEvidence
            }
        }

        // ==============================================================
        // KARAR — iki kanalın güçlüsü
        // ==============================================================
        val raw = maxOf(visualComponent, textComponent)
        val decision = engine.update(raw, System.currentTimeMillis())

        // --- Son çare: kör VE sessiz ---
        // Ne piksel var ne metin. Varsayılan davranış BLOKLAMAMAK: bu
        // durumda olan çok meşru uygulama var (DRM'li video, bazı
        // bankacılık ekranları, 2FA). Ayarı açan kullanıcı tarayıcılarda
        // bunu blok sebebi saymayı seçmiş oluyor.
        val sessiz = !contentFresh || content.isEmpty
        if (korBicim != null && sessiz) {
            blindMuteTicks++
            if (SecurePolicy.blockOnSecure && Config.isBrowser(pkg) &&
                blindMuteTicks >= SecurePolicy.BLIND_TICKS_REQUIRED
            ) {
                blokla("kör ve sessiz ($blindMuteTicks tick, $korBicim)")
            }
        } else {
            blindMuteTicks = 0
        }

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
            .putInt(ScreenGuardService.D_STARVED, blindMuteTicks)
            .putString(
                ScreenGuardService.D_BLIND,
                if (korBicim == null) "-" else "$korBicim ($korTicks)"
            )
            // "kare yok"un ardındaki sebep. Cihazda log okunamadığında
            // piksel kanalının neden ölü olduğunu söyleyen tek satır bu.
            .putString(
                ScreenGuardService.D_SOURCE_ERR,
                if (sourceRebuilds > 0) "${source.lastError()} · $sourceRebuilds kurulum"
                else source.lastError()
            )
            .putString(ScreenGuardService.D_IMAGE, if (korBicim == null) lastImageLabel else "-")
            .putFloat(ScreenGuardService.D_TEXT_RAW, textVerdict.score)
            .putString(
                ScreenGuardService.D_TEXT_INFO,
                when {
                    PerdeAccessibilityService.instance == null -> "a11y kapali"
                    !contentFresh -> "okuma yok"
                    content.isEmpty -> "metin yok (${content.nodes} dugum)"
                    else -> textVerdict.label
                }
            )
        if (raw > maxRaw) {
            maxRaw = raw
            e.putFloat(ScreenGuardService.D_MAX_RAW, raw)
            probs?.let {
                e.putString(
                    ScreenGuardService.D_MAX_PROBS,
                    it.joinToString(" ") { p -> "%.2f".format(p) }
                )
            }
        }
        e.apply()

        if (decision.justChanged) {
            when (decision.state) {
                DetectionEngine.State.BLOCKED -> blokla(
                    if (textComponent >= visualComponent)
                        "içerik %.2f (%s)".format(textVerdict.score, textVerdict.label)
                    else "görüntü %.3f".format(raw)
                )
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

    /**
     * Metin skorunu görsel skorun ölçeğine eşler.
     *
     * İki kanalın "0.7" değeri aynı şeyi ifade etmiyor: biri softmax
     * çıktısı, diğeri kanıt birleşimi. Eşleme, metin eşiğinin TAM OLARAK
     * görsel SOFT eşiğine denk gelmesini sağlıyor — böylece metin kanalı
     * da aynı pencere oylamasından geçiyor, kendi ayrı kuralına ihtiyaç
     * duymuyor.
     */
    private fun mapTextScore(score: Float): Float {
        if (score <= 0f) return 0f
        val th = Hassasiyet.aktif.textSoft
        val soft = Hassasiyet.aktif.soft
        val mapped = if (score >= th) {
            soft + (1f - soft) * ((score - th) / (1f - th))
        } else {
            soft * (score / th)
        }
        return mapped.coerceIn(0f, 1f)
    }

    /**
     * Gözcünün bekleme süresi.
     *
     * Kaynak hiç kare vermiyorsa peş peşe yeniden kurmanın faydası yok:
     * bazı cihazlarda takeScreenshot üçüncü taraf erişilebilirlik
     * servislerine hiç izin vermiyor ve her 5 saniyede bir yeniden kurmak
     * yalnızca pil yakıyor. Birkaç denemeden sonra aralık açılıyor —
     * vazgeçmiyoruz, çünkü arıza geçici de olabilir. Metin kanalı bu
     * sırada normal çalışmaya devam ediyor.
     */
    private fun gozcuAraligi(): Long =
        if (sourceRebuilds >= REBUILD_BACKOFF_AFTER) SOURCE_RETRY_SLOW_MS
        else Config.SOURCE_WATCHDOG_MS

    companion object {
        private const val TAG = "DetectionLoop"

        /** Bu kadar sonuçsuz kurulumdan sonra gözcü seyrekleşir. */
        private const val REBUILD_BACKOFF_AFTER = 3

        /** Geri çekilmiş gözcü aralığı. */
        private const val SOURCE_RETRY_SLOW_MS = 60_000L
    }
}
