package app.perde

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Ekrandaki METNİN gerçek analizi.
 *
 * Bu, görsel sınıflandırıcının kardeşi olan ikinci motor. Aynı soruyu
 * soruyor — "bu ekranda ne var?" — ama piksele değil metne bakıyor.
 * Kritik farkı: FLAG_SECURE metni gizlemiyor. Gizli sekmede ekran
 * görüntüsü siyah gelir, metin gelmeye devam eder.
 *
 * NEDEN BU BİR "LİSTE KONTROLÜ" DEĞİL:
 *
 *  1. Eşleşme kanıttır, karar değil. Tek kelime hiçbir zaman bloklamaz.
 *     Karar için birbirini destekleyen birden fazla kanıt gerekiyor
 *     (corroboration çarpanı).
 *  2. YOĞUNLUK ölçülüyor. 600 kelimelik haber yazısında geçen üç kelime
 *     ile 150 kelimelik sayfayı dolduran kırk kelime aynı şey değil.
 *     Ayrımı yapan şey listenin içeriği değil, dağılımı.
 *  3. TERS KANIT var. Bankacılık, sağlık, eğitim, alışveriş kelimeleri
 *     skoru aşağı çekiyor. "Cinsel yolla bulaşan hastalıklar" makalesi
 *     bu yüzden bloklanmıyor.
 *  4. YAPI okunuyor. Süre etiketleri + kısa bağlantı yığını = video
 *     ızgarası. Metnin ne dediği kadar nasıl dizildiği de sinyal.
 *  5. Sözlükte tek bir alan adı yok. Hiç duyulmamış bir site de aynı
 *     kelimelerle yazılmış olduğu için yakalanıyor.
 *
 * Skor 0..1. Eşik Hassasiyet profilinden geliyor.
 */
class ContentAnalyzer {

    class Verdict(
        val score: Float,
        val strong: Int,
        val medium: Int,
        val support: Int,
        val safe: Int,
        /** 100 kelimede kaç eşleşme. */
        val density: Float,
        val tokens: Int,
        /** Tanı satırı. Ham metin ASLA yer almaz — yalnızca sayılar. */
        val label: String
    ) {
        companion object {
            val NONE = Verdict(0f, 0, 0, 0, 0, 0f, 0, "-")
        }
    }

    private class Hit(var weight: Float, var count: Int)

    /** Terimleri eşleştirme biçimine göre ayırıp aramayı ucuzlatır. */
    private class Bank(terms: List<Lexicon.Term>) {
        val tokens = HashMap<String, Lexicon.Term>()
        val prefixes = ArrayList<Lexicon.Term>()
        val phrases = ArrayList<Lexicon.Term>()
        val subs = ArrayList<Lexicon.Term>()

        init {
            for (t in terms) when (t.mode) {
                Lexicon.Mode.TOKEN -> tokens[t.key] = t
                Lexicon.Mode.PREFIX -> prefixes.add(t)
                Lexicon.Mode.PHRASE -> phrases.add(t)
                Lexicon.Mode.SUB -> subs.add(t)
            }
        }
    }

    private val strongBank = Bank(Lexicon.STRONG)
    private val mediumBank = Bank(Lexicon.MEDIUM)
    private val supportBank = Bank(Lexicon.SUPPORT)
    private val safeBank = Bank(Lexicon.SAFE)

    fun analyze(c: ScreenContent): Verdict {
        val url = TextNorm.prepare(c.url)
        val title = TextNorm.prepare(c.title)
        val body = TextNorm.prepare(c.body)
        if (url.isEmpty && title.isEmpty && body.isEmpty) return Verdict.NONE

        val strong = HashMap<String, Hit>()
        val medium = HashMap<String, Hit>()
        val support = HashMap<String, Hit>()
        val safe = HashMap<String, Hit>()

        // Alanın kanıt değeri eşit değil: adres çubuğu ve sayfa başlığı
        // sayfanın NE OLDUĞUNU söyler, gövde metni içinde ne geçtiğini.
        for ((prep, factor) in listOf(url to W_URL, title to W_TITLE, body to W_BODY)) {
            if (prep.isEmpty) continue
            match(strongBank, prep, factor, strong)
            match(mediumBank, prep, factor, medium)
            match(supportBank, prep, factor, support)
            match(safeBank, prep, factor, safe)
        }

        // --- Birincil kanıt ---
        val strongEvidence = noisyOr(strong)
        val mediumEvidence = noisyOr(medium)
        val mediumWeight = medium.values.sumOf { it.weight.toDouble() }.toFloat()

        // Belirsiz terimler tek başına daha az güvenilir: güçlü kanıt
        // yokken etkileri kırpılıyor. "sexy" kelimesi bir sohbet
        // ekranında da geçer, porno sayfasında da.
        val mediumTrust = if (strong.isNotEmpty()) 1.0f else 0.75f
        var evidence = combine(strongEvidence, mediumEvidence * mediumTrust)

        // --- Destekleyici kanıt (kapılı) ---
        // "video, izle, hd, kategoriler" bir porno sayfasını doldurur ama
        // YouTube'u da doldurur. Bu yüzden ancak birincil kanıt varken
        // sayılıyorlar. Kapı kapalıysa hiç hesaba katılmıyorlar.
        val gateOpen = strong.isNotEmpty() || mediumWeight >= SUPPORT_GATE
        if (gateOpen) evidence = combine(evidence, noisyOr(support))

        // --- Destek çarpanı: tek kanıt karar veremez ---
        // Ayrı ayrı kaç FARKLI terim eşleşti? Biri 0.49, ikisi 0.74,
        // üçü 0.86, beşi 0.96. Tek kelimelik tesadüfleri bu söndürüyor.
        val distinct = strong.size + medium.size + 0.4f * (if (gateOpen) support.size else 0)
        var corroboration = 1f - exp(-distinct / CORROBORATION_SCALE)

        // İstisna: adres çubuğunda ya da başlıkta güçlü bir terim varsa
        // sayfanın kimliği zaten belli. Sayfa metni hiç okunamasa bile
        // (bazı uygulamalar yalnızca başlık veriyor) karar verilebilmeli.
        if (strongInHeader(url, title)) corroboration = maxOf(corroboration, HEADER_FLOOR)

        // --- Yoğunluk ---
        // Aynı kelimeyi 100 kere tekrarlamak skoru şişirmesin diye kanıt
        // BİR KEZ sayılıyor; ama metnin ne kadarının bu kelimelerden
        // oluştuğu ayrı bir sinyal ve tek başına çok ayırt edici:
        // haber yazısında ~1/100, porno sayfasında ~20/100.
        val occurrences = countOccurrences(strong) + countOccurrences(medium) +
                (if (gateOpen) countOccurrences(support) else 0)
        val totalTokens = maxOf(1, url.tokenCount + title.tokenCount + body.tokenCount)
        val density = 100f * occurrences / totalTokens
        var densityFactor = sqrt(density / DENSITY_TARGET).coerceIn(DENSITY_FLOOR, DENSITY_CEIL)
        // Yoğunluk tek başına ÖDÜL kazandırmaz: güçlü kanıt yokken en
        // fazla cezayı kaldırır. Anatomi terimleriyle dolu bir ansiklopedi
        // maddesi yoğunluk üzerinden eşiğe tırmanmasın.
        if (strong.isEmpty()) densityFactor = minOf(densityFactor, 1f)

        // --- Ters kanıt ---
        // Ayrımı yapan soru: sayfa konudan BAHSEDİYOR mu, yoksa konunun
        // KENDİSİ mi? Bahseden sayfa güvenli bağlamla açıklanabilir —
        // tıp yazısı da, haber de, porno bağımlılığıyla mücadele yazısı da
        // o kelimeleri kullanır. Konunun kendisi olan sayfa açıklanamaz:
        // orada kelimeler metnin dokusudur, yoğunluk beşe on katına çıkar.
        //
        // Bu yüzden ters kanıt yoğunluğa bakıyor, terimin gücüne değil.
        // Adresinde "porno" geçen bir mücadele blogu tam korumadan
        // yararlanıyor; yoğunluğu 20'yi aşan porno sayfası ise "güvenli
        // ödeme, üyelik, iade" yazarak bağışıklık kazanamıyor.
        val safeEvidence = noisyOr(safe)
        val safeDensity = 100f * countOccurrences(safe) / totalTokens
        val safeStrength = safeEvidence * sqrt(safeDensity / SAFE_DENSITY_TARGET).coerceIn(0f, 1f)
        val damp = if (density >= DENSITY_IMMUNE) 1f else 1f - SAFE_MAX_DAMP * safeStrength

        // --- Yapısal sinyal ---
        // Süre etiketi yığını (12:34) = video ızgarası. Tek başına hiçbir
        // şey demiyor, birincil kanıt varken anlam kazanıyor.
        val durations = countDurations(c)
        val structural = if (durations >= DURATION_MIN && evidence > 0.35f) STRUCTURAL_BONUS else 0f

        val score = (evidence * corroboration * densityFactor * damp + structural)
            .coerceIn(0f, 1f)

        return Verdict(
            score = score,
            strong = strong.size,
            medium = medium.size,
            support = if (gateOpen) support.size else 0,
            safe = safe.size,
            density = density,
            tokens = totalTokens,
            label = "s%d m%d d%d g%d yog%.1f tok%d".format(
                strong.size, medium.size,
                if (gateOpen) support.size else 0, safe.size, density, totalTokens
            )
        )
    }

    // ------------------------------------------------------------------

    private fun match(
        bank: Bank,
        prep: TextNorm.Prepared,
        factor: Float,
        out: HashMap<String, Hit>
    ) {
        for (tk in prep.tokens) {
            bank.tokens[tk]?.let { record(out, it, factor) }
            for (p in bank.prefixes) {
                if (tk.length >= p.key.length && tk.startsWith(p.key)) record(out, p, factor)
            }
        }
        if (bank.phrases.isNotEmpty()) {
            val padded = " ${prep.text} "
            for (ph in bank.phrases) if (padded.contains(" ${ph.key} ")) record(out, ph, factor)
        }
        for (s in bank.subs) if (prep.compact.contains(s.key)) record(out, s, factor)
    }

    private fun record(out: HashMap<String, Hit>, term: Lexicon.Term, factor: Float) {
        val eff = (term.weight * factor).coerceAtMost(MAX_TERM_WEIGHT)
        val h = out[term.key]
        if (h == null) out[term.key] = Hit(eff, 1)
        else {
            if (eff > h.weight) h.weight = eff
            h.count++
        }
    }

    /** Bağımsız kanıtların birleşimi: 1 - Π(1 - w). Hiçbiri 1.0'a ulaşamaz. */
    private fun noisyOr(hits: Map<String, Hit>): Float {
        var inv = 1.0
        for (h in hits.values) inv *= (1f - h.weight)
        return (1.0 - inv).toFloat()
    }

    private fun combine(a: Float, b: Float): Float = 1f - (1f - a) * (1f - b)

    private fun countOccurrences(hits: Map<String, Hit>): Int {
        var n = 0
        for (h in hits.values) n += h.count
        return n
    }

    private fun strongInHeader(url: TextNorm.Prepared, title: TextNorm.Prepared): Boolean {
        val header = HashMap<String, Hit>()
        if (!url.isEmpty) match(strongBank, url, 1f, header)
        if (!title.isEmpty) match(strongBank, title, 1f, header)
        return header.values.any { it.weight >= HEADER_STRONG_MIN }
    }

    /**
     * Süre etiketleri ham metinde aranır: normalizasyon ':' karakterini
     * attığı için normalize edilmiş metinde bulunamazlar.
     */
    private fun countDurations(c: ScreenContent): Int {
        if (c.body.isEmpty()) return 0
        var n = 0
        val m = Lexicon.DURATION.findAll(c.body).iterator()
        while (m.hasNext() && n < 64) { m.next(); n++ }
        return n
    }

    companion object {
        /** Alan ağırlıkları: adres ve başlık gövdeden daha çok şey söyler. */
        private const val W_URL = 1.35f
        private const val W_TITLE = 1.25f
        private const val W_BODY = 1.0f

        /** Hiçbir tek terim tek başına kesinlik iddia edemez. */
        private const val MAX_TERM_WEIGHT = 0.97f

        /** Destek terimlerinin sayılması için gereken belirsiz-terim ağırlığı. */
        private const val SUPPORT_GATE = 0.8f

        /** Kaç farklı terimde tam güvene ulaşılsın (yumuşak). */
        private const val CORROBORATION_SCALE = 1.5f

        /** Adres/başlıkta güçlü terim varsa destek çarpanının tabanı. */
        private const val HEADER_FLOOR = 0.85f
        private const val HEADER_STRONG_MIN = 0.70f

        /** Bu yoğunlukta (100 kelimede) tam güven. */
        private const val DENSITY_TARGET = 4f
        private const val DENSITY_FLOOR = 0.45f
        private const val DENSITY_CEIL = 1.15f

        /**
         * Bu yoğunluğun üstünde ters kanıt hiç dinlenmez.
         *
         * 8'di ve fazla düşüktü: anatomi terimleriyle dolu bir ansiklopedi
         * maddesi de bu eşiği geçip bağışıklık kazanıyordu. Ölçülen
         * porno sayfası yoğunlukları 23-79 aralığında, ansiklopedi
         * maddesi 9.5 — 20 ikisinin arasında.
         */
        private const val DENSITY_IMMUNE = 20f

        private const val SAFE_DENSITY_TARGET = 3f
        private const val SAFE_MAX_DAMP = 0.55f

        private const val DURATION_MIN = 5
        private const val STRUCTURAL_BONUS = 0.08f
    }
}
