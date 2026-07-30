package com.berke.perde

/**
 * Tüm eşik değerleri ve zamanlama parametreleri burada.
 * False positive ayarını yapacağın tek yer burası.
 */
object Config {

    // ---------- Yakalama ----------
    /** Saniyede kaç kare analiz edilecek. 1.0 = saniyede 1 kare. Batarya için 1.0 üstüne çıkma. */
    const val CAPTURE_FPS = 1.0

    /** Model giriş boyutu (nsfw_mobilenet_v2 için 224). */
    const val INPUT_SIZE = 224

    /** Ekran yakalamanın küçültme oranı. 4 = ekranın 1/4 çözünürlüğü yakalanır (CPU tasarrufu). */
    const val CAPTURE_DOWNSCALE = 4

    // ---------- Sınıf ağırlıkları ----------
    /**
     * Model 5 sınıf döndürür: [drawings, hentai, neutral, porn, sexy]
     * Ham skoru bunlarla ağırlıklandırıyoruz.
     *
     * "sexy" sınıfı false positive'lerin ana kaynağı — plaj, spor, moda, tişörtsüz
     * fotoğraf hepsi buraya düşüyor. Bu yüzden ağırlığı düşük.
     * "drawings" tek başına zararsız, ağırlığı 0.
     */
    const val W_DRAWINGS = 0.00f
    const val W_HENTAI   = 0.95f
    const val W_NEUTRAL  = 0.00f
    const val W_PORN     = 1.00f
    const val W_SEXY     = 0.35f

    // ---------- Eşikler ----------
    /**
     * HARD: pencere oylamasını atlayan hızlı yol. Çok yüksek tut,
     * sadece tartışmasız durumlar için.
     */
    const val HARD_THRESHOLD = 0.94f

    /**
     * HARD eşiğinin kaç ARDIŞIK karede geçilmesi gerektiği.
     *
     * 1 idi ve "anında blok" oradan geliyordu: model bir çöp adam
     * çizimini yüksek olasılıkla `hentai` sayınca ekran o saniye
     * kapanıyordu. Tek karelik bir softmax çıktısı hiçbir zaman o kadar
     * güvenilir değil. 2 kare ≈ 1.2 saniye; hızlı yol duruyor, tek
     * karelik sapmalar eleniyor.
     */
    const val HARD_FRAMES_REQUIRED = 2

    /**
     * SOFT: tek başına yetmez. Son N karenin K tanesi bu eşiği geçmeli.
     * Asıl çalışan mekanizma bu.
     */
    const val SOFT_THRESHOLD = 0.68f

    /** Pencere boyutu (kare sayısı). CAPTURE_FPS=1 ise 8 kare = 8 saniye. */
    const val WINDOW_SIZE = 8

    /** Pencere içinde SOFT eşiği geçmesi gereken minimum kare sayısı. */
    const val WINDOW_HITS_REQUIRED = 5

    /**
     * RELEASE: blok aktifken skor bu değerin altına inerse "temizlendi" sayılır.
     * HYSTERESIS: SOFT'tan belirgin düşük olmalı — yoksa blok açılıp kapanıp titrer.
     */
    const val RELEASE_THRESHOLD = 0.40f

    /** Bloğun kalkması için gereken ardışık temiz kare sayısı. */
    const val RELEASE_CONSECUTIVE_FRAMES = 5

    // ---------- EMA yumuşatma ----------
    /**
     * Üssel hareketli ortalama katsayısı. 0'a yakın = çok yumuşak/yavaş tepki,
     * 1'e yakın = ham skora yakın/agresif. 0.45 dengeli.
     */
    const val EMA_ALPHA = 0.45f

    // ---------- Soğuma ----------
    /** Blok kalktıktan sonra bu süre boyunca yeni blok tetiklenmez (ms). Flapping önler. */
    const val COOLDOWN_MS = 4_000L

    /** Blok ekranının minimum kalma süresi (ms). Anında kaybolmasın. */
    const val MIN_BLOCK_DURATION_MS = 3_000L

    /**
     * Blok ekranının ZORUNLU kalkma süresi (ms).
     *
     * Bu bir güvenlik sübabı, ayar değil. Blok ekranı açıkken ekran
     * görüntüsü artık içeriği değil kendi opak katmanımızı yakalıyor —
     * yani o sırada "içerik temizlendi mi" sorusunu güvenilir biçimde
     * cevaplayamıyoruz. Tespit mantığının herhangi bir dalı takılırsa
     * kullanıcı telefonunda kilitli kalır; geri tuşu, ana ekran tuşu ve
     * bildirim paneli overlay'in altında kaldığı için çıkış yolu da yok.
     *
     * Bu süre dolduğunda blok koşulsuz kalkar. İçerik hâlâ oradaysa
     * soğuma bitince yeniden tetiklenir — sürtünme korunur, kilit oluşmaz.
     */
    const val MAX_BLOCK_DURATION_MS = 8_000L

    /**
     * Kaynak bu süre boyunca hiç kullanılabilir kare vermezse baştan kurulur.
     *
     * Yakalama boru hattı asenkron ve sistem tarafında birçok şekilde
     * takılabiliyor — geri çağrı düşer, oturum bayatlar, pencere geçişinde
     * istek kaybolur. Her bir takılma biçimini ayrı ayrı kovalamak yerine
     * gözcü koyuyoruz: kare akmıyorsa kaynağı durdur, yeniden kur.
     *
     * Bu, kullanıcının "uygulamayı açıp Başlat'a basınca düzeliyor" diye
     * tarif ettiği manuel kurtarmanın otomatik hâli.
     */
    const val SOURCE_WATCHDOG_MS = 5_000L

    /**
     * Erişilebilirlik okuması bu yaştan sonra kullanılmaz (ms).
     *
     * Okuma asenkron: döngü her tick'te yeni okuma ister, sonucu bir
     * sonraki tick'te görür. Sakin moddaki 3 sn'lik aralık + okuma
     * gecikmesi bu sınırın altında kalıyor. Bayat metinle karar vermek,
     * kullanıcının çoktan çıktığı bir sayfa yüzünden bloklamak demek.
     */
    const val CONTENT_STALE_MS = 4_000L

    // ---------- Kendine karşı koruma ----------
    /**
     * Uygulamayı durdurmak istediğinde beklemen gereken süre (ms).
     * Anlık dürtüyle kapatmayı engellemek için. 15 dakika.
     * Bunu düşürürsen uygulamanın hiçbir anlamı kalmaz.
     */
    const val DISABLE_DELAY_MS = 15 * 60 * 1000L

    // ---------- İzleme kapsamı ----------
    /**
     * WHITELIST : sadece WATCHED_PACKAGES izlenir.
     *             Batarya dostu ama listede olmayan her uygulama kör nokta.
     * BLACKLIST : EXCLUDED dışındaki HER ŞEY izlenir.
     *             Gerçek kapsam bu. Reddit, 4chan, Telegram, galeri,
     *             dosya yöneticisi, bilmediğin bir tarayıcı — hepsi kapsanır.
     *
     * Varsayılan BLACKLIST. Whitelist yaklaşımı kavramsal olarak kusurlu:
     * listeyi ne kadar uzatırsan uzat her zaman eksik kalır ve tam da
     * atladığın uygulamadan bakılır.
     */
    enum class MonitorMode { WHITELIST, BLACKLIST }

    @Volatile var monitorMode = MonitorMode.BLACKLIST

    /**
     * BLACKLIST modunda ASLA izlenmeyecek paketler.
     *
     * Buraya konması gerekenler:
     *   - Hassas veri gösterenler (bankacılık, şifre yöneticisi, sağlık)
     *   - Yakalamanın anlamsız olduğu yerler (telefon, ayarlar, klavye)
     *   - Yanlış tetiklenmenin gerçekten zarar vereceği yerler (harita
     *     kullanırken araba sürüyorsundur, kamera acil bir an olabilir)
     */
    val EXCLUDED_PACKAGES = setOf(
        // sistem
        "com.android.systemui",
        "com.android.settings",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.maps",
        "com.android.deskclock",
        "com.google.android.deskclock",
        // klavyeler
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        // bankacılık / ödeme (FLAG_SECURE zaten kullanırlar,
        // siyah kare tespitiyle yanlış tetiklenmesin diye)
        "com.google.android.apps.walletnfcrel",
        // kendi uygulamamız
        "com.berke.perde"
    )

    /**
     * Tarayıcılar.
     *
     * TESPİT İÇİN KULLANILMIYOR. İçerik analizi paketin ne olduğuna
     * bakmıyor — hangi uygulama olursa olsun ekranda ne yazdığına bakıyor.
     * Listede olmayan bir tarayıcı da, hiç duyulmamış bir uygulama da
     * aynı şekilde kapsanıyor.
     *
     * Bu liste yalnızca son çare kuralında geçiyor: "ne piksel ne metin
     * okunabiliyor" durumunda (varsayılan olarak kapalı) sınırlamayı
     * tarayıcılarla tutmak için. Orada eksik kalması bir şey kaybettirmez.
     */
    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.brave.browser",
        "org.mozilla.firefox", "org.mozilla.focus", "org.mozilla.fenix",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.microsoft.emmx",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.yandex.browser",
        "com.UCMobile.intl",
        "mark.via.gp",
        "org.torproject.torbrowser"
    )

    fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in BROWSER_PACKAGES

    /**
     * WHITELIST modunda izlenecek paketler.
     * BLACKLIST modunda kullanılmaz.
     */
    val WATCHED_PACKAGES = setOf(
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.google.android.youtube",
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "org.telegram.messenger",
        "com.discord"
    )
}

// ---------------------------------------------------------------
// FLAG_SECURE / kör nokta politikası
// ---------------------------------------------------------------
/**
 * ARTIK ASIL SAVUNMA BURASI DEĞİL.
 *
 * Gizli sekme kör noktası içerik analiziyle kapanıyor: ekranın PİKSELİ
 * görünmese de METNİ okunabiliyor (bkz. ScreenReader) ve karar okunan
 * içerikten veriliyor (bkz. ContentAnalyzer). "Göremiyorum" durumu
 * kendi başına bir blok sebebi değil.
 *
 * Bu obje geriye kalan tek uç durumu yönetiyor: ne piksel var ne metin.
 * DRM'li video oynatıcı, bazı 2FA ve bankacılık ekranları buraya düşüyor
 * — hepsi meşru. Bu yüzden varsayılan KAPALI.
 */
object SecurePolicy {

    private const val PREFS = "perde"
    private const val KEY = "block_on_secure"

    /**
     * Tarayıcıda ne piksel ne metin okunabiliyorsa bloklansın mı?
     *
     * false (varsayılan) : hayır. Kanıt yoksa blok yok.
     * true               : evet — yalnızca tarayıcılarda, yalnızca hiçbir
     *                      şey okunamadığında. "Göremiyorsam izin vermem."
     *
     * Netflix'in oynatma ekranı bunun klasik yanlış tetiklenmesiydi;
     * artık varsayılan kapalı olduğu için gelmiyor.
     */
    @Volatile
    var blockOnSecure = false

    fun load(ctx: android.content.Context) {
        blockOnSecure = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    fun save(ctx: android.content.Context, value: Boolean) {
        blockOnSecure = value
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }

    /**
     * Kaç ardışık tick boyunca hem kör hem sessiz kalınca tetiklensin.
     *
     * Yüksek tutuldu: uygulama açılışı, sekme geçişi ve tam ekran video
     * başlangıcı gibi anlarda hem kare hem erişilebilirlik ağacı bir iki
     * saniyeliğine boş kalabiliyor. 8 tick ≈ 5 saniye kesintisiz sessizlik.
     */
    const val BLIND_TICKS_REQUIRED = 8
}

// ---------------------------------------------------------------
// HASSASİYET PROFİLLERİ
// ---------------------------------------------------------------
/**
 * Kapsam ve yanlış tetiklenme arasındaki takas ayarlanabilir değil,
 * ÖLÇÜLDÜ. Model "müstehcen ama giyimli" içeriği tek bir sınıfa
 * (sexy) atıyor ve o sınıfın içinde plaj fotoğrafıyla teşvik edici
 * içerik ayırt edilemiyor — ikisi de aynı skoru üretiyor.
 *
 * Yani "Instagram'ı da yakalasın ama tatil fotoğrafımı bloklamasın"
 * bu modelle mümkün değil. Seçim senin:
 *
 *  DENGELI : açık içerik yakalanır, Instagram/TikTok kaçar,
 *            yanlış tetiklenme ~sıfır
 *  SIKI    : güçlü müstehcen içerik de yakalanır,
 *            plaj/tatil fotoğrafları bazen tetikler
 *  KATI    : Instagram/TikTok dahil çoğu şey yakalanır,
 *            spor ve moda içerikleri de tetikler — günlük kullanımda
 *            can sıkıcı olur, bilerek seç
 */
enum class Hassasiyet(
    val wSexy: Float,
    val soft: Float,
    val hard: Float,
    val release: Float,
    /**
     * Metin analizinin blok eşiği (bkz. ContentAnalyzer).
     *
     * Görsel eşiklerden ayrı, çünkü iki skorun "0.7"si aynı şeyi ifade
     * etmiyor: biri softmax çıktısı, diğeri kanıt birleşimi. Döngü metin
     * skorunu bu eşik TAM OLARAK soft'a denk gelecek şekilde eşliyor.
     *
     * Ölçüm (tools/metin_sim.py): porno sayfaları 0.90+, bankacılık ve
     * sağlık içerikleri 0.30 altında. Aradaki boşluk geniş; 0.78 boşluğun
     * ortasında duruyor.
     */
    val textSoft: Float
) {
    DENGELI(0.35f, 0.68f, 0.94f, 0.40f, 0.78f),
    SIKI(0.85f, 0.55f, 0.90f, 0.32f, 0.68f),
    KATI(1.00f, 0.50f, 0.88f, 0.28f, 0.60f);

    companion object {
        /** Aktif profil. */
        @Volatile var aktif: Hassasiyet = DENGELI

        private const val PREFS = "perde"
        private const val KEY = "sensitivity"

        /**
         * Kalıcıya yazılmış profili yükler.
         *
         * Statik alan süreç ölümünü atlatamıyor ve tespit döngüsü artık
         * MainActivity hiç açılmadan da başlayabiliyor (erişilebilirlik
         * servisi yeniden bağlandığında). Okumazsak sessizce DENGELI'ye
         * düşer ve kullanıcı seçtiği profilin uygulandığını sanır.
         */
        fun load(ctx: android.content.Context) {
            val saved = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY, DENGELI.name) ?: DENGELI.name
            aktif = runCatching { valueOf(saved) }.getOrDefault(DENGELI)
        }

        fun save(ctx: android.content.Context, h: Hassasiyet) {
            aktif = h
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY, h.name).apply()
        }
    }
}

// ---------------------------------------------------------------
// UYARLANABILIR ÖRNEKLEME (batarya)
// ---------------------------------------------------------------
object Adaptive {
    /** Skor uzun süre düşükse örnekleme aralığını uzat. */
    const val ENABLED = true

    /**
     * Normal aralık (ms).
     *
     * Tespit süresinin büyük kısmı buradan geliyor: pencere oylaması
     * WINDOW_HITS_REQUIRED kare istiyor, yani en hızlı blok
     * 5 × aralık kadar sürüyor. 1000 ms'te bu 5 saniye, üstüne EMA ve
     * asenkron ekran görüntüsü gecikmesi binince fark ediliyordu.
     *
     * 600 ms, takeScreenshot'ın 333 ms'lik sistem sınırının rahatça
     * üstünde ve en hızlı bloğu 3 saniyeye indiriyor.
     */
    const val FAST_INTERVAL_MS = 600L

    /** Sakin moddaki aralık (ms). */
    const val SLOW_INTERVAL_MS = 3000L

    /** Kaç ardışık düşük skorlu kareden sonra sakin moda geçilsin. */
    const val CALM_AFTER_FRAMES = 20

    /** Bu skorun altı "sakin" sayılır. */
    const val CALM_SCORE = 0.20f

    /**
     * Kare bu yaştan sonra kullanılmaz.
     *
     * SLOW_INTERVAL_MS'ten BÜYÜK olmak ZORUNDA, sabit bir sayı olamaz.
     *
     * Sebebi yakalamanın asenkron oluşu: grabFrame() önce yeni bir istek
     * gönderiyor, sonra ELDEKİ kareyi döndürüyor — yani döndürülen kare
     * her zaman bir tick yaşında. Sınır tick aralığının altında kalırsa
     * sakin moda geçen döngü her kareyi "bayat" sayıp atar.
     *
     * Bu sessiz ve kalıcı bir arıza üretiyordu: 2500 ms sınır ile 3000 ms
     * sakin aralık çeliştiği için piksel kanalı 20. sakin kareden sonra
     * körleşiyor, kör kalmak skoru sıfır tuttuğu için döngü sakin moddan
     * hiç çıkamıyor ve kanal bir daha geri gelmiyordu. Ekran görüntüsü
     * başarıyla alınıyor, hata kodu dönmüyor, kare sadece çöpe gidiyordu —
     * bu yüzden tanı ekranında "kare yok" görünüp sebep boş kalıyordu.
     *
     * Pay, tick gecikmesi ve ekran görüntüsünün kendi gecikmesi için.
     */
    const val FRAME_STALE_MS = SLOW_INTERVAL_MS + 1_500L
}
