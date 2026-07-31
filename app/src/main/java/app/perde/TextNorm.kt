package app.perde

/**
 * Metin normalizasyonu — içerik analizinin ilk katmanı.
 *
 * NEDEN GEREKLİ:
 * Ham metinde eşleştirme yapmak "listede aynen var mı" demektir; o yüzden
 * kırılgandır. `PORNO`, `pоrno` (Kiril o ile), `p0rn0`, `p o r n o`,
 * `pornosu` — hepsi aynı şeydir ama hiçbiri diğerine eşit değildir.
 *
 * Burada hepsini tek kanonik biçime indiriyoruz. Analiz bundan sonra
 * yazılış varyantlarıyla değil, metnin kendisiyle uğraşıyor.
 *
 * Kapsanan varyantlar:
 *   - büyük/küçük harf, Türkçe karakterler (ş ı ğ ü ö ç)
 *   - aksanlar (é, ñ, å, ø ...)
 *   - Kiril ve Yunan görsel ikizleri (о, е, а, р, с, х, α, ο ...)
 *   - leet (p0rn, s3x) — yalnızca HARF ARASINDAKİ rakamlar çevrilir,
 *     yoksa "18+" ve "4chan" bozulur
 *   - ayraçla parçalanmış kelimeler (p o r n, p-o-r-n, p.o.r.n)
 */
object TextNorm {

    /** Kanonik biçime indirgenmiş metin + analiz için hazır türevleri. */
    class Prepared(
        /** Boşlukla ayrılmış, yalnızca ASCII harf/rakam içeren metin. */
        val text: String,
        val tokens: List<String>,
        /** Tüm boşlukları atılmış hâli — ayraçla gizlenmiş kelimeler için. */
        val compact: String
    ) {
        val tokenCount: Int get() = tokens.size
        val isEmpty: Boolean get() = tokens.isEmpty()

        companion object {
            val EMPTY = Prepared("", emptyList(), "")
        }
    }

    fun prepare(raw: CharSequence?): Prepared {
        if (raw.isNullOrEmpty()) return Prepared.EMPTY
        val normalized = deObfuscate(normalize(raw))
        if (normalized.isEmpty()) return Prepared.EMPTY
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Prepared.EMPTY
        return Prepared(normalized, tokens, tokens.joinToString(""))
    }

    /** Küçük harf + katlama + leet. Harf/rakam olmayan her şey boşluk olur. */
    fun normalize(raw: CharSequence): String {
        val buf = CharArray(raw.length)
        for (i in raw.indices) {
            val lower = raw[i].lowercaseChar()
            val folded = FOLD[lower] ?: lower
            // Katlamadan sonra ASCII dışında kalan her şey (CJK, Arapça,
            // emoji, noktalama) ayraç sayılır. Sözlük Latin alfabesinde.
            buf[i] = if (folded.code < 128 && (folded.isLetter() || folded.isDigit())) folded else ' '
        }

        // Leet: yalnızca iki harfin ARASINDAKİ rakam harfe çevrilir.
        // "p0rn" -> "porn" ama "18+" ve "4chan" olduğu gibi kalır.
        for (i in buf.indices) {
            val letter = LEET[buf[i]] ?: continue
            val prevIsLetter = i > 0 && buf[i - 1].isLetter()
            val nextIsLetter = i + 1 < buf.size && buf[i + 1].isLetter()
            if (prevIsLetter && nextIsLetter) buf[i] = letter
        }

        // Çoklu boşlukları tekle
        val out = StringBuilder(buf.size)
        var lastWasSpace = true
        for (c in buf) {
            if (c == ' ') {
                if (!lastWasSpace) out.append(' ')
                lastWasSpace = true
            } else {
                out.append(c)
                lastWasSpace = false
            }
        }
        return out.toString().trim()
    }

    /**
     * Ayraçla parçalanmış kelimeleri geri birleştirir: "p o r n o" -> "porno".
     *
     * Kural bilerek dar: ARDIŞIK en az [MIN_SPLIT_RUN] adet TEK HARFLİK
     * parça. Normal metinde tek harflik kelimeler yan yana bu kadar
     * dizilmez; İngilizce'de "a", Türkçe'de "o" ve "e" tek başına
     * geçebilir ama dördü peş peşe gelmez.
     *
     * Bunu yapmazsak metnin tamamını boşluksuz birleştirip alt dizi
     * aramamız gerekirdi — o da "class" içinde "ass", "grape" içinde
     * "rape" bulan klasik hatayı doğurur.
     */
    private fun deObfuscate(normalized: String): String {
        if (normalized.isEmpty()) return normalized
        val parts = normalized.split(' ')
        if (parts.size < MIN_SPLIT_RUN) return normalized

        val out = StringBuilder(normalized.length)
        val run = StringBuilder()

        fun flush() {
            if (run.isEmpty()) return
            // run: birleştirilmiş tek harfler
            if (run.length >= MIN_SPLIT_RUN) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(run)
            } else {
                for (c in run) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(c)
                }
            }
            run.setLength(0)
        }

        for (p in parts) {
            if (p.length == 1 && p[0].isLetter()) {
                run.append(p)
            } else {
                flush()
                if (p.isEmpty()) continue
                if (out.isNotEmpty()) out.append(' ')
                out.append(p)
            }
        }
        flush()
        return out.toString()
    }

    private const val MIN_SPLIT_RUN = 4

    private val LEET: Map<Char, Char> = hashMapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't'
    )

    private val FOLD: Map<Char, Char> = buildFoldTable()

    private fun buildFoldTable(): Map<Char, Char> {
        val t = HashMap<Char, Char>(160)
        fun map(src: String, target: Char) { for (c in src) t[c] = target }
        fun put(src: Char, target: Char) { t[src] = target }
        // Türkçe ('İ' -> 'i' dönüşümünü lowercaseChar zaten yapıyor)
        put('ı', 'i'); put('ş', 's'); put('ğ', 'g'); put('ç', 'c')
        // Latin aksanlar
        map("àáâãäåāăą", 'a')
        map("èéêëēĕėęě", 'e')
        map("ìíîïĩīĭįı", 'i')
        map("òóôõöøōŏő", 'o')
        map("ùúûüũūŭůűų", 'u')
        map("ýÿŷ", 'y'); map("ñńņň", 'n'); map("ćĉċč", 'c'); map("ĝğġģ", 'g')
        map("śŝşš", 's'); map("ţťț", 't'); map("źżž", 'z'); map("ďđ", 'd')
        map("ĺļľł", 'l'); map("ŕŗř", 'r'); map("ĥħ", 'h'); map("ĵ", 'j')
        put('ß', 's'); put('æ', 'a'); put('œ', 'o'); put('þ', 'p')
        // Kiril görsel ikizler — "pоrn" ortadaki o Kiril olabilir
        put('а', 'a'); put('в', 'b'); put('е', 'e'); put('к', 'k'); put('м', 'm')
        put('н', 'h'); put('о', 'o'); put('р', 'p'); put('с', 'c'); put('т', 't')
        put('у', 'y'); put('х', 'x'); put('ѕ', 's'); put('і', 'i'); put('ј', 'j')
        put('ԁ', 'd'); put('ɡ', 'g')
        // Yunan görsel ikizler
        put('α', 'a'); put('ο', 'o'); put('ρ', 'p'); put('ε', 'e'); put('ι', 'i')
        put('κ', 'k'); put('ν', 'v'); put('τ', 't'); put('χ', 'x'); put('υ', 'u')
        // Tam genişlik (fullwidth) formlar
        for (c in 'ａ'..'ｚ') put(c, 'a' + (c - 'ａ'))
        for (c in '０'..'９') put(c, '0' + (c - '０'))
        return t
    }
}
