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
     * HARD: tek kare bu eşiği geçerse anında blokla. Çok yüksek tut,
     * sadece tartışmasız durumlar için.
     */
    const val HARD_THRESHOLD = 0.94f

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
object SecurePolicy {

    /**
     * İzlenen uygulama öndeyken kare tamamen siyah geliyorsa
     * (= FLAG_SECURE aktif, gizli sekme ya da korumalı içerik)
     * ne yapılsın?
     *
     * true  : blokla. Sıkı ama tutarlı — "göremiyorsam izin vermem".
     * false : sadece logla, bloklama.
     *
     * Not: Netflix, bankacılık uygulaması gibi meşru FLAG_SECURE
     * kullanan uygulamaları WATCHED_PACKAGES'a koymazsan sorun olmaz.
     */
    const val BLOCK_ON_SECURE_BLACK = true

    /** Kaç ardışık siyah kare sonrası tetiklensin. Geçiş animasyonlarını eler. */
    const val SECURE_BLACK_FRAMES_REQUIRED = 4

    /**
     * Kaç ardışık tick hiç kare alamayınca "korumalı içerik" sayılsın.
     *
     * Gizli sekmede VirtualDisplay siyah kare değil, hiç kare üretmiyor —
     * siyah kare tespiti bu durumu göremiyor, çünkü inceleyecek kare yok.
     *
     * Siyah kare eşiğinden yüksek tutuldu: yakalama yeni başladığında ilk
     * karenin gelmesi bir iki saniye sürebiliyor ve o pencerede yanlış
     * tetiklenmek istemiyoruz.
     */
    const val SECURE_STARVED_TICKS_REQUIRED = 6
}

/**
 * Erişilebilirlik katmanı için URL anahtar kelimeleri.
 *
 * Bu bilinçli olarak KISA tutuldu. Amaç kapsamlı bir blocklist değil —
 * o savaşı kaybedersin, alan adı sonsuz. Amaç gizli sekme kör noktasında
 * en yaygın durumları yakalamak. Asıl iş görsel sınıflandırıcıda.
 */
val Config.URL_KEYWORDS: List<String>
    get() = listOf(
        // alan adlari
        "porn", "xvideo", "xnxx", "xham", "redtube", "youporn",
        "spankbang", "chaturbate", "onlyfans", "rule34", "nhentai",
        "hentai", "brazzers", "erome", "motherless",
        // platform ici yollar — Reddit, 4chan gibi siteler tek alan adi
        // altinda hem normal hem yetiskin icerik barindiriyor, o yuzden
        // alan adi degil YOL bazli eslesme gerekiyor
        "reddit.com/r/nsfw", "reddit.com/r/gonewild", "old.reddit.com/r/nsfw",
        "4chan.org/b/", "4chan.org/s/", "4chan.org/hc/", "4chan.org/gif/",
        "boards.4chan.org/b", "boards.4chan.org/s",
        "/nsfw", "nsfw=true", "over18"
    )

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
    val release: Float
) {
    DENGELI(0.35f, 0.68f, 0.94f, 0.40f),
    SIKI(0.85f, 0.55f, 0.90f, 0.32f),
    KATI(1.00f, 0.50f, 0.88f, 0.28f);

    companion object {
        /** Aktif profil. Kullanıcı seçimi Prefs'ten okunacaksa burayı bağla. */
        @Volatile var aktif: Hassasiyet = DENGELI
    }
}

// ---------------------------------------------------------------
// UYARLANABILIR ÖRNEKLEME (batarya)
// ---------------------------------------------------------------
object Adaptive {
    /** Skor uzun süre düşükse örnekleme aralığını uzat. */
    const val ENABLED = true

    /** Normal aralık (ms). */
    const val FAST_INTERVAL_MS = 1000L

    /** Sakin moddaki aralık (ms). */
    const val SLOW_INTERVAL_MS = 3000L

    /** Kaç ardışık düşük skorlu kareden sonra sakin moda geçilsin. */
    const val CALM_AFTER_FRAMES = 20

    /** Bu skorun altı "sakin" sayılır. */
    const val CALM_SCORE = 0.20f
}
