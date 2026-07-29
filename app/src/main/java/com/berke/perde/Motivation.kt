package com.berke.perde

import android.content.Context

/**
 * Blok ekranı mesajları — inanç profili + dil seçimli.
 *
 * ---------------------------------------------------------------
 * TELİF DURUMU
 * ---------------------------------------------------------------
 * Kutsal metinlerin ORİJİNALLERİ kamu malıdır (Arapça Kur'an,
 * İbranice Tanah, Yunanca Yeni Ahit — hepsi yüzyıllarca eski).
 *
 * Telifli olan şey MODERN ÇEVİRİLERDİR. Diyanet meali, Kitab-ı
 * Mukaddes Şirketi çevirisi, NIV, ESV — hepsi telifli.
 *
 * Bu yüzden aşağıdaki Türkçe ve İngilizce metinler mevcut hiçbir
 * çeviriden alınmadı, orijinallerden yeniden yazıldı. Dağıtımda
 * telif sorunu çıkarmaz.
 *
 * ---------------------------------------------------------------
 * DOĞRULUK UYARISI — ATLAMA
 * ---------------------------------------------------------------
 * Bu çeviriler yetkili meal değildir. Anlamı aktarmak için yazıldı,
 * fıkhi ya da teolojik referans olarak kullanılamaz.
 *
 * Uygulamayı yayınlayacaksan:
 *   - Her metni konusunda yetkin birine kontrol ettir
 *   - Sure:ayet / bölüm:ayet referanslarını ekranda göster (yapıldı)
 *     ki kullanıcı kendi tercih ettiği mealle karşılaştırabilsin
 *   - Uygulama içinde "bunlar yetkili meal değildir" notu koy
 *
 * Dini metni yanlış aktarmak, teknik bir hatadan çok daha ciddi
 * bir güven kaybıdır. Bu adımı atlama.
 */
object Motivation {

    enum class Profile { MUSLIM, CHRISTIAN, JEWISH, SECULAR, CUSTOM, NONE }
    enum class Lang { TR, EN }

    /**
     * @param original Kamu malı orijinal metin (Arapça/İbranice/Yunanca).
     *                 Seküler mesajlarda boş.
     * @param ref      Kaynak referansı — kullanıcı doğrulayabilsin diye.
     */
    data class Entry(
        val tr: String,
        val en: String,
        val original: String = "",
        val ref: String = ""
    )

    // ===============================================================
    // İSLAM — Arapça orijinal kamu malı, çeviriler özgün
    // ===============================================================
    private val MUSLIM = listOf(
        Entry(
            tr = "Mümin erkeklere söyle: gözlerini sakınsınlar ve iffetlerini korusunlar. Bu onlar için daha temizdir.",
            en = "Tell the believing men to lower their gaze and guard their chastity. That is purer for them.",
            original = "قُل لِّلْمُؤْمِنِينَ يَغُضُّوا مِنْ أَبْصَارِهِمْ وَيَحْفَظُوا فُرُوجَهُمْ ۚ ذَٰلِكَ أَزْكَىٰ لَهُمْ",
            ref = "Nûr 24:30"
        ),
        Entry(
            tr = "Zinaya yaklaşmayın. O bir hayâsızlıktır ve kötü bir yoldur.",
            en = "Do not go near unlawful intimacy. It is an indecency and an evil path.",
            original = "وَلَا تَقْرَبُوا الزِّنَىٰ ۖ إِنَّهُ كَانَ فَاحِشَةً وَسَاءَ سَبِيلًا",
            ref = "İsrâ 17:32"
        ),
        Entry(
            tr = "O, gözlerin hain bakışını ve göğüslerin gizlediğini bilir.",
            en = "He knows the treachery of the eyes and what the hearts conceal.",
            original = "يَعْلَمُ خَائِنَةَ الْأَعْيُنِ وَمَا تُخْفِي الصُّدُورُ",
            ref = "Mü'min 40:19"
        ),
        Entry(
            tr = "Bizim uğrumuzda çaba gösterenlere, yollarımızı mutlaka göstereceğiz.",
            en = "Those who strive for Our sake — We will surely guide them to Our paths.",
            original = "وَالَّذِينَ جَاهَدُوا فِينَا لَنَهْدِيَنَّهُمْ سُبُلَنَا",
            ref = "Ankebût 29:69"
        ),
        Entry(
            tr = "Allah hiç kimseye gücünün üstünde bir şey yüklemez.",
            en = "God does not burden any soul beyond its capacity.",
            original = "لَا يُكَلِّفُ اللَّهُ نَفْسًا إِلَّا وُسْعَهَا",
            ref = "Bakara 2:286"
        ),
        Entry(
            tr = "Allah bir topluluğun durumunu, onlar kendilerinde olanı değiştirmedikçe değiştirmez.",
            en = "God does not change the condition of a people until they change what is within themselves.",
            original = "إِنَّ اللَّهَ لَا يُغَيِّرُ مَا بِقَوْمٍ حَتَّىٰ يُغَيِّرُوا مَا بِأَنفُسِهِمْ",
            ref = "Ra'd 13:11"
        ),
        Entry(
            tr = "Şüphesiz güçlükle beraber bir kolaylık vardır.",
            en = "Indeed, with hardship comes ease.",
            original = "إِنَّ مَعَ الْعُسْرِ يُسْرًا",
            ref = "İnşirâh 94:6"
        )
    )

    // ===============================================================
    // HRİSTİYANLIK — Yunanca/İbranice orijinal kamu malı
    // ===============================================================
    private val CHRISTIAN = listOf(
        Entry(
            tr = "Bir kadına şehvetle bakan, yüreğinde onunla zina etmiş olur.",
            en = "Whoever looks at a woman with lust has already committed adultery with her in his heart.",
            original = "πᾶς ὁ βλέπων γυναῖκα πρὸς τὸ ἐπιθυμῆσαι αὐτὴν ἤδη ἐμοίχευσεν αὐτὴν ἐν τῇ καρδίᾳ αὐτοῦ",
            ref = "Matta 5:28"
        ),
        Entry(
            tr = "Gözlerimle bir antlaşma yaptım.",
            en = "I made a covenant with my eyes.",
            original = "בְּרִית כָּרַתִּי לְעֵינָי",
            ref = "Eyüp 31:1"
        ),
        Entry(
            tr = "Bedeniniz size ait değil. Öyleyse bedeninizle Tanrı'yı yüceltin.",
            en = "Your body is not your own. So glorify God with your body.",
            original = "οὐκ ἐστὲ ἑαυτῶν... δοξάσατε δὴ τὸν θεὸν ἐν τῷ σώματι ὑμῶν",
            ref = "1. Korintliler 6:19-20"
        ),
        Entry(
            tr = "Tanrı güvenilirdir; dayanabileceğinizden fazlasıyla sınanmanıza izin vermez, çıkış yolunu da sağlar.",
            en = "God is faithful; He will not let you be tested beyond your strength, but will provide a way out.",
            original = "πιστὸς δὲ ὁ θεός, ὃς οὐκ ἐάσει ὑμᾶς πειρασθῆναι ὑπὲρ ὃ δύνασθε",
            ref = "1. Korintliler 10:13"
        ),
        Entry(
            tr = "Bu çağın kalıbına uymayın; zihninizin yenilenmesiyle değişin.",
            en = "Do not be shaped by this age; be transformed by the renewing of your mind.",
            original = "μὴ συσχηματίζεσθε τῷ αἰῶνι τούτῳ, ἀλλὰ μεταμορφοῦσθε τῇ ἀνακαινώσει τοῦ νοός",
            ref = "Romalılar 12:2"
        )
    )

    // ===============================================================
    // YAHUDİLİK — İbranice Tanah orijinal kamu malı
    // ===============================================================
    private val JEWISH = listOf(
        Entry(
            tr = "Kendi yüreğinizin ve kendi gözlerinizin ardından gitmeyin.",
            en = "Do not follow after your own heart and your own eyes.",
            original = "וְלֹא תָתוּרוּ אַחֲרֵי לְבַבְכֶם וְאַחֲרֵי עֵינֵיכֶם",
            ref = "Sayılar 15:39"
        ),
        Entry(
            tr = "Gözlerimle bir antlaşma yaptım.",
            en = "I made a covenant with my eyes.",
            original = "בְּרִית כָּרַתִּי לְעֵינָי",
            ref = "Eyüp 31:1"
        ),
        Entry(
            tr = "Öfkesini geciktiren yiğitten, kendine hâkim olan şehir fethedenden üstündür.",
            en = "One slow to anger is better than a warrior; one who rules his spirit, than one who takes a city.",
            original = "טוֹב אֶרֶךְ אַפַּיִם מִגִּבּוֹר וּמֹשֵׁל בְּרוּחוֹ מִלֹּכֵד עִיר",
            ref = "Süleyman'ın Özdeyişleri 16:32"
        ),
        Entry(
            tr = "Yüreğini her şeyden çok koru; çünkü hayatın kaynağı odur.",
            en = "Guard your heart above all else, for from it flow the springs of life.",
            original = "מִכָּל־מִשְׁמָר נְצֹר לִבֶּךָ כִּי־מִמֶּנּוּ תּוֹצְאוֹת חַיִּים",
            ref = "Süleyman'ın Özdeyişleri 4:23"
        ),
        Entry(
            tr = "Günah kapıda pusuda bekliyor; sana yönelmiş. Ama sen ona hâkim olmalısın.",
            en = "Sin crouches at the door; its desire is for you, but you must master it.",
            original = "לַפֶּתַח חַטָּאת רֹבֵץ וְאֵלֶיךָ תְּשׁוּקָתוֹ וְאַתָּה תִּמְשָׁל־בּוֹ",
            ref = "Yaratılış 4:7"
        )
    )

    // ===============================================================
    // SEKÜLER — tamamen özgün, telif yok
    // ===============================================================
    private val SECULAR = listOf(
        Entry("Bunu sen kapattın. Sebebini de sen biliyorsun.",
              "You closed this yourself. You know why."),
        Entry("Şu an istediğin şey, olmak istediğin kişiyle aynı yöne bakmıyor.",
              "What you want right now isn't facing the same direction as who you want to be."),
        Entry("On dakika sonraki sen buna ne derdi?",
              "What would you ten minutes from now say about this?"),
        Entry("Dürtü geçici. Sen kalıcısın.",
              "The urge is temporary. You are not."),
        Entry("Bu ekranı buraya kendin koydun.",
              "You put this screen here yourself."),
        Entry("Kaçınmıyorsun — seçiyorsun. Fark bu.",
              "You're not avoiding. You're choosing. That's the difference."),
        Entry("Bu anı atlatmak, bu anla pazarlık etmekten kolay.",
              "Getting through this moment is easier than negotiating with it."),
        Entry("Alışkanlık tekrarla kurulur. Bu da bir tekrar.",
              "Habits are built by repetition. This is one too."),
        Entry("İrade bir kas değil, bir ortam meselesi. Ortamı sen kurdun.",
              "Willpower isn't a muscle, it's an environment. You built this one."),
        Entry("Kaybettiğin şey bir dakika. Kazandığın şey bir örüntü.",
              "You lose a minute. You gain a pattern.")
    )

    private val FALLBACK = listOf(Entry("Kapat.", "Close it."))

    // ===============================================================
    // Tercihler
    // ===============================================================
    private const val PREFS = "perde"
    private const val KEY_PROFILE = "motivation_profile"
    private const val KEY_LANG = "app_lang"
    private const val KEY_CUSTOM = "motivation_custom"
    private const val KEY_SHOW_ORIGINAL = "show_original"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setProfile(ctx: Context, p: Profile) =
        prefs(ctx).edit().putString(KEY_PROFILE, p.name).apply()

    fun getProfile(ctx: Context): Profile =
        runCatching { Profile.valueOf(prefs(ctx).getString(KEY_PROFILE, "NONE")!!) }
            .getOrDefault(Profile.NONE)

    fun setLang(ctx: Context, l: Lang) =
        prefs(ctx).edit().putString(KEY_LANG, l.name).apply()

    /** Seçim yoksa sistem diline bakar; Türkçe değilse İngilizce. */
    fun getLang(ctx: Context): Lang {
        val saved = prefs(ctx).getString(KEY_LANG, null)
        if (saved != null) return runCatching { Lang.valueOf(saved) }.getOrDefault(Lang.EN)
        val sys = java.util.Locale.getDefault().language
        return if (sys == "tr") Lang.TR else Lang.EN
    }

    /** Orijinal metin (Arapça vb.) blok ekranında gösterilsin mi? */
    fun setShowOriginal(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_ORIGINAL, v).apply()

    fun getShowOriginal(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_ORIGINAL, true)

    fun setCustom(ctx: Context, lines: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM, lines).apply()

    private fun customEntries(ctx: Context): List<Entry> =
        prefs(ctx).getString(KEY_CUSTOM, "")
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { Entry(it, it) } ?: emptyList()

    // ===============================================================
    // Seçim
    // ===============================================================
    private var lastIndex = -1

    /** Blok ekranında gösterilecek hazır metin. */
    fun pick(ctx: Context): String {
        val pool = when (getProfile(ctx)) {
            Profile.MUSLIM    -> MUSLIM
            Profile.CHRISTIAN -> CHRISTIAN
            Profile.JEWISH    -> JEWISH
            Profile.SECULAR   -> SECULAR
            Profile.CUSTOM    -> customEntries(ctx)
            Profile.NONE      -> SECULAR
        }.ifEmpty { SECULAR }.ifEmpty { FALLBACK }

        val e = if (pool.size == 1) pool[0] else {
            var i: Int
            do { i = pool.indices.random() } while (i == lastIndex)
            lastIndex = i
            pool[i]
        }

        val lang = getLang(ctx)
        val body = if (lang == Lang.TR) e.tr else e.en

        return buildString {
            if (getShowOriginal(ctx) && e.original.isNotEmpty()) {
                append(e.original).append("\n\n")
            }
            append(body)
            if (e.ref.isNotEmpty()) append("\n\n— ").append(e.ref)
        }
    }
}
