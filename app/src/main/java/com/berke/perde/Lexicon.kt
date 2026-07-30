package com.berke.perde

/**
 * İçerik analizinin sözlüğü.
 *
 * BU BİR SİTE LİSTESİ DEĞİL — bilerek. Sözlükte tek bir alan adı, tek bir
 * marka adı yok. Sebebi basit: alan adı sonsuzdur, o savaşı kaybedersin.
 * Burada olan şey DİL: bir sayfanın pornografik olduğunu söyleyen
 * kelimeler. Sitenin adı ne olursa olsun, sayfanın üstündeki metin aynı
 * kelimelerden kuruluyor. Hiç duyulmamış bir alan adı da yakalanır.
 *
 * DÖRT KATMAN, çünkü kelimelerin kanıt değeri eşit değil:
 *
 *   STRONG  Normal içerikte pratikte hiç geçmez. Tek başına ağır basar.
 *   MEDIUM  Pornografide de sağlık/haber/sözlük içeriğinde de geçer.
 *           Tek başına yetmez, destek ister.
 *   SUPPORT Bir porno sayfasını DOLDURAN ama tek başına hiçbir şey
 *           söylemeyen kelimeler (izle, video, hd, ücretsiz, kategoriler).
 *           Yalnızca birincil kanıt varken sayılır — yoksa YouTube'un
 *           ana sayfası da eşleşirdi.
 *   SAFE    Ters yönde kanıt. Bankacılık, sağlık, eğitim, alışveriş,
 *           yazılım. Bunlar skoru AŞAĞI çeker: "cinsel yolla bulaşan
 *           hastalıklar" makalesi ile porno sayfasını ayıran şey bu.
 *
 * AĞIRLIKLAR: 1.0 = tek başına kesin. 0.1 = neredeyse gürültü.
 * Eşleşen her terim BİR KEZ sayılır (bkz. ContentAnalyzer); aynı kelimeyi
 * yüz kere tekrarlamak skoru şişirmez, ama YOĞUNLUK ayrı ölçülür.
 */
object Lexicon {

    enum class Mode {
        /** Tam kelime eşleşmesi. */
        TOKEN,
        /** Kelime bu önekle başlıyorsa (Türkçe ekler + İngilizce çoğullar). */
        PREFIX,
        /** Birden fazla kelime; metinde kelime sınırlarıyla aranır. */
        PHRASE,
        /** Boşluksuz metinde alt dizi. Yalnızca uzun ve tek anlamlı olanlar. */
        SUB
    }

    class Term(val key: String, val weight: Float, val mode: Mode)

    private fun t(key: String, w: Float) = Term(key, w, Mode.TOKEN)
    private fun p(key: String, w: Float) = Term(key, w, Mode.PREFIX)
    private fun ph(key: String, w: Float) = Term(key, w, Mode.PHRASE)
    private fun sub(key: String, w: Float) = Term(key, w, Mode.SUB)

    // ------------------------------------------------------------------
    // STRONG — normal içerikte geçmeyen terimler
    // ------------------------------------------------------------------
    val STRONG: List<Term> = listOf(
        // "porn" öneki tek başına dört dili birden karşılıyor:
        // porn, porno, pornosu, pornocu, pornografi, pornstar, pornhub
        p("porn", 0.92f),
        p("hentai", 0.90f),
        p("doujin", 0.85f),
        p("masturbasyon", 0.85f), p("masturbat", 0.85f),
        t("xxx", 0.80f),
        t("blowjob", 0.90f), t("handjob", 0.88f), t("footjob", 0.88f),
        t("deepthroat", 0.90f), t("creampie", 0.88f), t("cumshot", 0.90f),
        t("gangbang", 0.90f), t("bukkake", 0.90f), t("titjob", 0.88f),
        t("fingering", 0.70f), t("squirting", 0.80f), t("rimjob", 0.88f),
        t("camgirl", 0.85f), t("camwhore", 0.88f), t("pornstar", 0.90f),
        t("striptease", 0.80f), t("nsfw", 0.75f), t("milf", 0.82f),
        t("bbw", 0.60f), t("hardcore", 0.55f), t("softcore", 0.75f),
        t("upskirt", 0.85f), t("voyeur", 0.75f), t("cuckold", 0.85f),
        p("nymph", 0.70f), t("erotica", 0.80f),
        // Türkçe
        t("sikis", 0.88f), t("sikise", 0.88f), t("sikisme", 0.88f),
        t("sikisen", 0.88f), t("sikisiyor", 0.88f), t("sikismek", 0.88f),
        t("sikisleri", 0.88f), t("sikerken", 0.88f),
        t("amcik", 0.88f), t("amcigi", 0.88f), t("amciklar", 0.88f),
        t("sakso", 0.88f), t("otuzbir", 0.75f), t("yarak", 0.80f),
        t("azdirici", 0.70f),
        // Ayrık yazılan kalıplar — tek kelimeler masum, birleşimi değil
        ph("anal sex", 0.92f), ph("oral sex", 0.88f), ph("group sex", 0.90f),
        ph("sex video", 0.90f), ph("sex videos", 0.90f), ph("sex tape", 0.88f),
        ph("free porn", 0.95f), ph("adult video", 0.85f), ph("adult videos", 0.85f),
        ph("live sex", 0.90f), ph("sex chat", 0.85f), ph("sex cam", 0.90f),
        ph("jerk off", 0.85f), ph("nude photos", 0.85f), ph("naked girls", 0.90f),
        ph("adults only", 0.70f), ph("xxx video", 0.92f),
        ph("anal seks", 0.92f), ph("oral seks", 0.88f), ph("grup seks", 0.90f),
        ph("seks videosu", 0.90f), ph("seks izle", 0.92f), ph("canli seks", 0.90f),
        ph("gizli cekim", 0.75f), ph("turbanli ifsa", 0.92f), ph("liseli ifsa", 0.92f),
        ph("ifsa videolari", 0.90f), ph("yetiskin video", 0.85f),
        ph("cinsel iliski videolari", 0.85f),
        // Boşluksuz metinde aranan uzun ve tek anlamlı diziler.
        // Asıl işlevi ADRES ÇUBUĞU: "freeporn", "sexvideos", "hardcoretube"
        // gibi birleşik yazımlar ancak böyle yakalanır.
        sub("pornofilm", 0.92f), sub("sexvideo", 0.90f), sub("sexfilm", 0.88f),
        sub("seksvideo", 0.90f), sub("adultvideo", 0.85f), sub("xxxvideo", 0.92f),
        sub("nudegirl", 0.88f), sub("nakedgirl", 0.88f), sub("livesex", 0.90f),
        sub("sexcam", 0.90f), sub("camsex", 0.90f), sub("sexchat", 0.85f),
        sub("hardcoresex", 0.92f), sub("teensex", 0.92f), sub("analsex", 0.92f)
    )

    // ------------------------------------------------------------------
    // MEDIUM — pornografide de meşru içerikte de geçer
    // ------------------------------------------------------------------
    val MEDIUM: List<Term> = listOf(
        t("sex", 0.45f), t("sexs", 0.45f), t("seks", 0.45f), t("sexo", 0.50f),
        t("sexual", 0.35f), t("cinsel", 0.35f), t("cinsellik", 0.40f),
        t("sexy", 0.40f), t("seksi", 0.45f), t("sexi", 0.45f),
        p("erotik", 0.60f), t("erotic", 0.60f), t("erotique", 0.60f),
        t("nude", 0.55f), t("nudes", 0.65f), t("naked", 0.50f),
        p("ciplak", 0.55f), t("soyunmus", 0.50f), t("ustsuz", 0.55f),
        t("topless", 0.65f), t("lingerie", 0.45f), t("thong", 0.45f),
        t("tanga", 0.40f), t("bikini", 0.25f),
        t("orgasm", 0.65f), t("orgazm", 0.65f), t("orgy", 0.80f), t("seksle", 0.55f),
        t("fetish", 0.60f), t("fetis", 0.60f), t("bdsm", 0.70f),
        t("bondage", 0.55f), t("kinky", 0.55f), t("horny", 0.65f),
        t("azgin", 0.55f), t("azmis", 0.50f), t("tahrik", 0.35f),
        t("escort", 0.55f), t("eskort", 0.60f), t("fahise", 0.60f),
        t("orospu", 0.50f), t("surtuk", 0.55f), t("kaltak", 0.55f),
        t("genelev", 0.60f),
        t("boobs", 0.70f), t("tits", 0.70f), t("titties", 0.75f),
        t("memeleri", 0.55f), t("gogusleri", 0.40f), t("kalcalari", 0.35f),
        t("booty", 0.50f), t("butt", 0.35f), t("ass", 0.40f), t("asses", 0.55f),
        t("penis", 0.40f), t("vagina", 0.45f), t("vajina", 0.45f),
        t("klitoris", 0.55f), t("clitoris", 0.55f), t("meni", 0.45f),
        p("ejakul", 0.65f), t("bosalma", 0.40f), t("bosaldi", 0.55f),
        t("sperm", 0.30f), t("cum", 0.55f), t("cumming", 0.75f),
        t("dick", 0.45f), t("cock", 0.45f), t("pussy", 0.70f),
        t("ensest", 0.70f), t("tecavuz", 0.40f), t("taciz", 0.20f),
        t("ifsa", 0.55f), t("ifsalar", 0.70f), t("ifsasi", 0.70f),
        t("adult", 0.25f), t("yetiskin", 0.25f), t("mustehcen", 0.60f),
        t("sansursuz", 0.50f), t("uncensored", 0.55f), t("yasakli", 0.25f),
        ph("18 plus", 0.40f), ph("cinsel iliski", 0.45f), ph("ic camasiri", 0.35f),
        ph("gay porn", 0.95f), ph("sikis izle", 0.95f)
    )

    // ------------------------------------------------------------------
    // SUPPORT — tek başına anlamsız, birincil kanıt varken sayılır
    //
    // Bunlar bir porno sitesinin İSKELETİ: video ızgarası, kategori
    // listesi, süre etiketleri, izlenme sayıları. Bir haber makalesinde
    // "seks" kelimesi geçer ama bu iskelet yoktur — ayrım buradan çıkıyor.
    // ------------------------------------------------------------------
    val SUPPORT: List<Term> = listOf(
        t("teen", 0.20f), t("teens", 0.22f), t("genc", 0.10f), t("liseli", 0.25f),
        t("amateur", 0.18f), t("amator", 0.18f), t("uvey", 0.20f),
        t("webcam", 0.22f), t("cam", 0.12f), t("cams", 0.20f), t("canli", 0.10f),
        t("izle", 0.15f), t("izleyin", 0.15f), t("watch", 0.10f),
        t("altyazili", 0.30f), t("subtitled", 0.20f),
        t("video", 0.08f), t("videos", 0.10f), t("videolar", 0.12f),
        t("videolari", 0.12f), t("film", 0.08f), t("filmi", 0.08f),
        t("kategoriler", 0.12f), t("categories", 0.12f), t("kategori", 0.10f),
        t("tags", 0.10f), t("etiketler", 0.10f), t("models", 0.12f),
        t("modeller", 0.12f), t("stars", 0.12f), t("yildizlar", 0.12f),
        t("hd", 0.08f), t("4k", 0.08f), t("full", 0.06f), t("premium", 0.10f),
        t("vip", 0.10f), t("ucretsiz", 0.10f), t("bedava", 0.12f),
        t("free", 0.06f), t("indir", 0.10f), t("download", 0.08f),
        t("izlenme", 0.10f), t("views", 0.08f), t("dakika", 0.06f),
        t("minutes", 0.06f), t("sure", 0.05f), t("populer", 0.08f),
        t("popular", 0.08f), t("trending", 0.08f), t("related", 0.08f),
        t("benzer", 0.08f), t("onerilen", 0.08f), t("yeni", 0.05f),
        t("sicak", 0.10f), t("hot", 0.12f), t("guzel", 0.05f),
        t("turk", 0.08f), t("turkish", 0.08f), t("turbanli", 0.35f),
        t("olgun", 0.20f), t("mature", 0.20f), t("yasli", 0.10f),
        t("lezbiyen", 0.35f), t("lesbian", 0.35f), t("gay", 0.20f),
        t("trans", 0.15f), t("travesti", 0.35f), t("shemale", 0.60f)
    )

    // ------------------------------------------------------------------
    // SAFE — ters yönde kanıt
    //
    // Bankacılık ekranını, sağlık makalesini, ders notunu, alışveriş
    // sepetini pornografiden ayıran kelimeler. Bu katman olmasa
    // "cinsel sağlık" yazısı ile porno sayfası aynı skoru alırdı.
    //
    // Not: bu, bloklamayı ENGELLEYEN bir liste; yanlış tarafta hata
    // yapması güvenli taraf. Yine de yoğunluk yüksekken devre dışı
    // kalıyor (bkz. ContentAnalyzer) — porno sayfası "ücretsiz üyelik"
    // yazarak bağışıklık kazanamasın.
    // ------------------------------------------------------------------
    val SAFE: List<Term> = listOf(
        // bankacılık / ödeme
        t("bakiye", 0.55f), t("iban", 0.60f), t("hesabim", 0.45f),
        t("hesaplarim", 0.50f), t("havale", 0.50f), t("eft", 0.45f),
        t("dekont", 0.55f), t("ekstre", 0.55f), t("taksit", 0.45f),
        t("kredi", 0.40f), t("faiz", 0.40f), t("fatura", 0.40f),
        t("odeme", 0.35f), t("odemeler", 0.40f), t("bakiyeniz", 0.60f),
        t("balance", 0.45f), t("account", 0.35f), t("payment", 0.35f),
        t("invoice", 0.45f), t("transaction", 0.45f), t("deposit", 0.40f),
        t("kart", 0.25f), t("kartlarim", 0.50f), t("banka", 0.45f),
        t("bank", 0.35f), t("banking", 0.45f), t("swift", 0.35f),
        // kimlik / güvenlik
        t("parola", 0.45f), t("password", 0.40f), t("sifreniz", 0.50f),
        t("dogrulama", 0.40f), t("verification", 0.40f), t("otp", 0.45f),
        t("2fa", 0.45f), t("oturum", 0.30f), t("login", 0.25f),
        // sağlık
        t("doktor", 0.40f), t("hastane", 0.45f), t("muayene", 0.45f),
        t("tedavi", 0.45f), t("hastalik", 0.40f), t("semptom", 0.45f),
        t("tani", 0.30f), t("recete", 0.45f), t("ilac", 0.35f),
        t("klinik", 0.35f), t("doctor", 0.35f), t("hospital", 0.40f),
        t("treatment", 0.40f), t("symptoms", 0.45f), t("diagnosis", 0.45f),
        t("patient", 0.40f), t("saglik", 0.30f), t("health", 0.30f),
        t("jinekolog", 0.45f), t("uroloji", 0.45f), t("enfeksiyon", 0.40f),
        // eğitim / haber / referans
        t("makale", 0.35f), t("arastirma", 0.35f), t("kaynakca", 0.45f),
        t("universite", 0.35f), t("ders", 0.30f), t("sinav", 0.35f),
        t("odev", 0.35f), t("haber", 0.30f), t("gazete", 0.35f),
        t("muhabir", 0.40f), t("aciklama", 0.20f), t("rapor", 0.30f),
        t("research", 0.35f), t("article", 0.30f), t("university", 0.35f),
        t("wikipedia", 0.50f), t("ansiklopedi", 0.50f), t("kaynak", 0.20f),
        t("mahkeme", 0.40f), t("savci", 0.45f), t("dava", 0.35f),
        t("yasa", 0.30f), t("kanun", 0.35f), t("madde", 0.20f),
        // yazılım / iş
        t("function", 0.40f), t("class", 0.30f), t("import", 0.30f),
        t("github", 0.45f), t("commit", 0.40f), t("gradle", 0.45f),
        t("android", 0.25f), t("kotlin", 0.45f), t("python", 0.40f),
        t("error", 0.25f), t("debug", 0.35f), t("server", 0.30f),
        t("toplanti", 0.40f), t("takvim", 0.30f), t("gorev", 0.25f),
        t("proje", 0.25f), t("sunum", 0.35f),
        // alışveriş / gündelik
        t("sepet", 0.40f), t("siparis", 0.40f), t("kargo", 0.45f),
        t("indirim", 0.30f), t("fiyat", 0.25f), t("urun", 0.25f),
        t("stok", 0.35f), t("iade", 0.40f), t("cart", 0.35f),
        t("shipping", 0.40f), t("order", 0.25f), t("price", 0.25f),
        t("restoran", 0.35f), t("yemek", 0.30f), t("tarif", 0.30f),
        t("rezervasyon", 0.40f), t("ucus", 0.40f), t("otel", 0.30f),
        t("bilet", 0.35f), t("hava", 0.20f), t("durumu", 0.15f),
        // spor
        t("mac", 0.30f), t("gol", 0.35f), t("takim", 0.30f),
        t("puan", 0.25f), t("lig", 0.35f), t("stadyum", 0.40f),
        t("antrenman", 0.35f), t("league", 0.35f), t("match", 0.20f)
    )

    /**
     * Video ızgarası imzası — ham metinde aranır, normalizasyondan ÖNCE.
     * "12:34" gibi süre etiketleri normalizasyonda ':' kaybolduğu için
     * sonradan bulunamaz.
     */
    val DURATION = Regex("""\b\d{1,2}:\d{2}\b""")
}
