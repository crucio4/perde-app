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

    /** Bir önceki tick'te ekran kapalı mıydı. Uyanışta hızı geri almak için. */
    private var ekranKapaliydi = false

    /** Son tick'in saati. [saglikli] bunu okuyor. */
    @Volatile private var sonTick = 0L

    private val runnable = object : Runnable {
        override fun run() {
            // catch Exception YETMİYORDU. OutOfMemoryError bir Error'dır,
            // Exception değil — ve bitmap ölçekleme/inference tam olarak onu
            // fırlatabiliyor. Yakalanmayınca run() yarıda kesiliyor,
            // postDelayed hiç çalışmıyor ve DÖNGÜ SESSİZCE ÖLÜYOR: tek
            // kurtuluş uygulamayı yeniden başlatmak.
            //
            // postDelayed finally'de: hangi dalda çıkarsak çıkalım bir
            // sonraki tick mutlaka planlanır.
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick hatası", t)
            } finally {
                worker.postDelayed(this, currentInterval)
            }
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

    /**
     * Döngü gerçekten çalışıyor mu?
     *
     * "Başlat"a basmak eskiden var olan bir döngüyü hiç sorgulamadan
     * kabul ediyordu (`if (loop != null) return`). Döngü herhangi bir
     * sebeple durmuşsa kullanıcının elinde çare kalmıyordu: buton bir şey
     * yapmıyor, uygulama açık görünüyor, koruma yok. Artık ölü döngü
     * baştan kuruluyor.
     */
    fun saglikli(): Boolean {
        if (!thread.isAlive) return false
        // Henüz ilk tick atmadıysa fırsat ver; kurulum bir kaç yüz ms sürebilir.
        if (sonTick == 0L) return true
        return System.currentTimeMillis() - sonTick < OLU_SAYILMA_MS
    }

    fun stop() {
        worker.removeCallbacksAndMessages(null)
        overlay.hide()
        source.stop()
        classifier.close()
        ScreenReader.reset()
        thread.quitSafely()
        Log.i(TAG, "Döngü durdu")
    }

    /**
     * Blok ekranını açar. Tek giriş noktası: soğuma kontrolü ve açılış
     * zamanının kaydı burada, yoksa çağrı yerlerinden biri unutulur.
     */
    private fun blokla(sebep: String) {
        val now = System.currentTimeMillis()
        if (now < blockCooldownUntil) {
            // Motor blok istedi ama ekranı açmıyoruz. Motoru BLOCKED'da
            // bırakmak tam da kilitlenmeyi doğuruyordu: oradan çıkmak
            // skorun düşmesine bağlı, kullanıcı aynı sayfada olduğu için
            // skor düşmüyor ve tespit sessizce ölüyordu. İki durum ayrık
            // kalmamalı — ekran açılmadıysa motor da blokta sayılmaz.
            engine.reset()
            return
        }
        if (overlay.isShowing()) return
        overlayShownAt = now
        Log.i(TAG, "BLOK: $sebep")
        diag.edit()
            .putLong(ScreenGuardService.D_LAST_BLOCK, now)
            .putString(ScreenGuardService.D_LAST_BLOCK_WHY, sebep)
            .apply()
        overlay.show(Motivation.pick(ctx))
    }

    /**
     * Blok ekranını kaldırır ve döngüyü SOĞUK BAŞLANGIÇ durumuna döndürür.
     *
     * NEDEN HEPSİ BİRDEN: "bir kez blokladıktan sonra tespit ölüyor,
     * uygulamaya bir saniye girip çıkınca düzeliyor" arızası üç kez
     * düzeltildi ve her seferinde geri geldi. Sebep tek tek hatalar değil,
     * YAPI: normale dönüş beş ayrı yere dağılmıştı (blok kaldırma, paket
     * değişimi, izlenmeyen paket, gözcü, motor reseti) ve her biri farklı
     * bir alt kümeyi sıfırlıyordu. Hangi alan atlanırsa döngü o alanda
     * takılı kalıyor, kullanıcının "gir çık" hareketi ise izlenmeyen paket
     * dalını tetiklediği için HEPSİNİ sıfırlıyor ve arızayı gizliyordu.
     *
     * Bu yüzden [sifirla] tek yetkili nokta: kullanıcının elle yaptığı
     * kurtarmanın birebir aynısı, koşulsuz, her blok sonrasında.
     */
    private fun blogonKaldir(sebep: String) {
        val now = System.currentTimeMillis()
        overlay.hide()
        blockCooldownUntil = now + Config.COOLDOWN_MS
        // Soğuma İKİ yerde birden bilinmeli. Motor bunu bilmezse reset
        // sonrası aynı içerikte hemen BLOCKED'a geri dönüyor, döngü ise
        // kendi soğuması yüzünden overlay'i açmıyor; motor ekransız
        // BLOCKED'da kilitleniyor ve içerik değişmediği için oradan bir
        // daha çıkamıyordu.
        engine.startCooldown(now)
        sifirla("blok kaldırıldı: $sebep")
    }

    /**
     * Döngünün TÜM oynak durumunu başlangıç haline getirir.
     *
     * Buraya bir alan eklemeyi unutmak, arızanın geri gelmesi demek —
     * yeni bir durum alanı eklerken burayı da güncelle.
     */
    private fun sifirla(sebep: String) {
        engine.reset()
        differ.reset()
        // Erişilebilirlik okuması da bayat kalabiliyor: blok boyunca
        // ekranda bizim overlay'imiz vardı, elimizdeki metin o ana ait.
        ScreenReader.reset()

        lastProbs = null
        lastEvidence = 1f
        lastImageLabel = "-"
        korTicks = 0
        blindMuteTicks = 0
        maxRaw = 0f
        analyzedFrames = 0

        // Paket kimliğini de unutuyoruz: bir sonraki tick hangi uygulamada
        // olursak olalım "yeni uygulama" muamelesi yapsın, taze başlasın.
        lastWatchedPackage = null

        // Örnekleme hızı: blok sonrası sakin moda düşmüş olabiliriz, o
        // hâlde bir sonraki tespit 3 saniye gecikir. Hızlıdan başla.
        calmFrames = 0
        currentInterval = Adaptive.FAST_INTERVAL_MS

        // Kaynağı kaldığı yerden sürdürmek güvenilir değil: blok boyunca
        // tick() en başta döndüğü için kaynağa saniyelerce dokunulmadı.
        // Baştan kurmak bir tick'e mal oluyor, karşılığında bütün bir
        // takılma sınıfı ortadan kalkıyor.
        source.stop()
        sourceRebuilds = 0
        sourceStartedAt = 0L
        lastGoodFrameAt = 0L

        Log.i(TAG, "Döngü sıfırlandı ($sebep), kaynak yeniden kurulacak")
    }

    private fun tick() {
        // --- Kalp atışı ---
        // TÜM erken dönüşlerden önce yazılıyor, çünkü cevaplaması gereken
        // soru tam olarak şu: "döngü hâlâ çalışıyor mu, yoksa öldü mü?"
        // D_FRAMES bunu cevaplayamıyor — o yalnızca analize KADAR gelinen
        // tick'lerde artıyor, dolayısıyla erken dönen bir döngüyle ölmüş
        // bir döngü orada birbirinden ayırt edilemiyordu.
        sonTick = System.currentTimeMillis()
        diag.edit()
            .putLong(ScreenGuardService.D_HEARTBEAT, sonTick)
            .apply()

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

        // --- DEĞİŞMEZ KURAL: "ekransız BLOK" diye bir durum yoktur ---
        //
        // Buraya gelmişsek overlay kapalı. Motor hâlâ BLOCKED ise bu bir
        // arızadır ve tam olarak kullanıcının tarif ettiği ölümü üretir:
        // BLOCKED'dan çıkmak skorun düşmesine bağlı, kullanıcı aynı sayfada
        // olduğu için skor düşmüyor, yeni blok da açılmıyor çünkü blok
        // yalnızca DURUM DEĞİŞİMİNDE (justChanged) açılıyor. Motor sonsuza
        // kadar asılı kalıyor; üstelik aşağıdaki paket kontrolleri de
        // "CLEAR değilse atla" dediği için kendini toparlayamıyor.
        //
        // Bu duruma götüren yolları tek tek kovalamak yerine — üç kez
        // denendi, her seferinde yenisi çıktı — durumun kendisini yasaklıyoruz.
        // Overlay açılamamışsa (izin yok, addView hata verdi) da buraya
        // düşülür; soğuma o yüzden şart, yoksa her tick yeniden denenir.
        if (engine.currentState() != DetectionEngine.State.CLEAR) {
            val now = System.currentTimeMillis()
            Log.w(TAG, "Motor ekransız BLOKTA kalmış — sıfırlanıyor")
            engine.startCooldown(now)
            blockCooldownUntil = now + Config.COOLDOWN_MS
            sifirla("ekransız blok")
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
            // Kapalı ekranda 600 ms'te bir uyanmanın karşılığı yok.
            currentInterval = Adaptive.SLOW_INTERVAL_MS
            ekranKapaliydi = true
            return
        }

        // Ekran yeni açıldı: yavaşlatılmış aralıkla devam edersek ilk tespit
        // 3 saniye geç kalır. Kilit açıldığında tam hızda başlıyoruz.
        if (ekranKapaliydi) {
            ekranKapaliydi = false
            calmFrames = 0
            currentInterval = Adaptive.FAST_INTERVAL_MS
        }

        val pkg = appWatcher.currentForegroundPackage()

        if (pkg != lastLoggedPackage) {
            lastLoggedPackage = pkg
            Log.d(TAG, "ön planda: $pkg  izlenecek=${appWatcher.shouldMonitor(pkg)}")
        }

        // Bu iki dal eskiden ayrıca "motor CLEAR ise" diye kontrol
        // ediyordu. Motor takılınca kendini toparlayamamasının sebebi tam
        // olarak oydu: kurtarma yolu, kurtarılması gereken duruma kapalıydı.
        // Artık gerek de yok — yukarıdaki değişmez kural buraya CLEAR
        // olmadan gelinemeyeceğini garanti ediyor.
        if (!appWatcher.shouldMonitor(pkg)) {
            if (source.isRunning()) sifirla("izlenmeyen paket: $pkg")
            lastWatchedPackage = null
            // İzlenmeyen uygulamada hızlı örneklemenin karşılığı yok.
            currentInterval = Adaptive.SLOW_INTERVAL_MS
            return
        }

        if (pkg != lastWatchedPackage) {
            // Yeni uygulama: önceki uygulamanın skorları taşınmamalı.
            engine.reset()
            differ.reset()
            lastProbs = null
            lastEvidence = 1f
            lastWatchedPackage = pkg
            maxRaw = 0f
            analyzedFrames = 0
            blindMuteTicks = 0
            // İzlenmeyen bir uygulamadan dönmüş olabiliriz; orada aralığı
            // yavaşlatmıştık. Yeni uygulamaya tam hızda giriyoruz, yoksa
            // ilk tespit 3 saniye geç kalır.
            calmFrames = 0
            currentInterval = Adaptive.FAST_INTERVAL_MS
        }

        // Kaynak kurulamazsa da devam: piksel kanalı kapalı kalır ama
        // İÇERİK KANALI ÇALIŞIR. Eskiden burada return vardı ve piksel
        // tarafındaki bir arıza metin kanalını da susturuyordu — gizli
        // sekmede tek çalışan kanal o.
        if (!source.isRunning() && source.start()) {
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

        // Gözcü YALNIZCA piksel kanalını onarır. Eskiden burada return
        // vardı: kaynak her takıldığında o tick'te motor hiç
        // güncellenmiyordu, yani piksel arızası metin kanalının oyunu da
        // yutuyordu. Kaynağı yeniden kurup aynı tick içinde devam ediyoruz;
        // bu tick'te kare gelmez, gelmemesi de sorun değil.
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
            // Blok gelmiyorsa iki sebepten biridir: skor yetmiyor ya da
            // soğumadayız. İkisini ayırt etmenin cihazdaki tek yolu bu.
            .putLong(
                ScreenGuardService.D_COOLDOWN,
                maxOf(0L, blockCooldownUntil - System.currentTimeMillis())
            )
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
                // Emniyet ağı: değişmez kural sayesinde motorun ekransız
                // BLOCKED'da kalması artık mümkün değil, yani buraya normalde
                // gelinmiyor. Gelinirse de çıkış yolu diğerleriyle aynı
                // olmalı — çıplak overlay.hide() soğumaları kurmuyordu.
                DetectionEngine.State.CLEAR -> blogonKaldir("skor düştü")
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

        /**
         * Bu süredir tick atmayan döngü ölü sayılır.
         *
         * En yavaş aralık 3 sn; 30 sn, geçici bir sistem duraklamasını
         * ölümle karıştırmayacak kadar geniş.
         */
        private const val OLU_SAYILMA_MS = 30_000L
    }
}
