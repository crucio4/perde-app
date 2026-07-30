package com.berke.perde

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Görsel destekleyici kanıt — sınıflandırıcının "ne gördüğünü" pikselle
 * doğrular.
 *
 * NEDEN VAR:
 * MobileNetV2 tabanlı NSFW modeli stil ile içeriği karıştırıyor. Çizgi
 * çizimler `drawings` ile `hentai` sınıfları arasında bölünüyor ve
 * `hentai` ağırlığı yüksek olduğu için basit bir ÇÖP ADAM çizimi
 * 0.94'ü aşıp anında blok tetikleyebiliyor. Model çizimi görüyor,
 * çizimin NE olduğunu görmüyor.
 *
 * Buradaki kural fizik: çıplaklık TEN gerektirir. Karede hiç ten yoksa
 * modelin "porno/hentai" iddiasını piksel desteklemiyor demektir.
 *
 * ÖLÇÜLENLER
 *   skinMax     En yoğun karodaki ten oranı. Kare geneli değil KARO bazlı:
 *               beyaz bir sayfadaki tek bir görsel de yakalanabilsin diye.
 *   colorfulness Hasler-Süsstrunk renklilik. Çizgi çizimde ~0, fotoğrafta 25+.
 *   flatRatio   Akromatik (gri/siyah/beyaz) piksel oranı. Çizim ve metin
 *               ekranlarında çok yüksek, fotoğrafta düşük.
 *
 * BİLİNEN BOŞLUK: siyah-beyaz fotoğrafta ten kromasi yoktur. Bu yüzden
 * kare genelinde renk yoksa ten kuralı devre dışı bırakılıyor — çizgi
 * çizim kuralı orada da çalışmaya devam ediyor (fotoğrafın yarım tonları
 * flatRatio'yu düşürür, çizimin düz beyazı yükseltir).
 */
class ImageEvidence {

    class Stats(
        val skinMax: Float,
        val skinMean: Float,
        val colorfulness: Float,
        val flatRatio: Float
    ) {
        /** Çizgi çizim / diyagram / düz metin ekranı. */
        val lineArt: Boolean
            get() = flatRatio >= FLAT_MIN && colorfulness < LINEART_COLOR_MAX && skinMax < 0.05f

        /** Kare bütünüyle renksiz (s/b fotoğraf, gri tema). */
        val monochrome: Boolean get() = colorfulness < MONO_MAX

        /**
         * Ham skor bununla çarpılıyor.
         *   0.00 -> çizgi çizim: modelin iddiası pikselle desteklenmiyor
         *   0.25 -> ten yok: neredeyse kesin yanlış pozitif
         *   1.00 -> ten var: model serbest
         */
        fun multiplier(): Float = when {
            lineArt -> 0f
            monochrome -> 1f            // ten kromasi ölçülemez, kuralı uygulama
            skinMax >= SKIN_FULL -> 1f
            skinMax <= SKIN_NONE -> 0.25f
            else -> {
                val t = (skinMax - SKIN_NONE) / (SKIN_FULL - SKIN_NONE)
                0.25f + 0.75f * t
            }
        }

        fun label(): String = "ten%.0f%% renk%.0f duz%.0f%%%s".format(
            skinMax * 100, colorfulness, flatRatio * 100,
            if (lineArt) " CIZIM" else ""
        )
    }

    private var buffer: IntArray? = null

    fun analyze(bmp: Bitmap): Stats {
        val w = bmp.width
        val h = bmp.height
        if (w < TILES_X || h < TILES_Y) return Stats(1f, 1f, 99f, 0f)

        val buf = buffer?.takeIf { it.size >= w * h } ?: IntArray(w * h).also { buffer = it }
        bmp.getPixels(buf, 0, w, 0, 0, w, h)

        val tileSkin = IntArray(TILES_X * TILES_Y)
        val tileTotal = IntArray(TILES_X * TILES_Y)

        var skinCount = 0
        var flatCount = 0
        var n = 0

        // Renklilik için birikimler (Hasler-Süsstrunk)
        var sumRg = 0.0; var sumRg2 = 0.0
        var sumYb = 0.0; var sumYb2 = 0.0

        var y = 0
        while (y < h) {
            val row = y * w
            val ty = (y * TILES_Y / h).coerceAtMost(TILES_Y - 1)
            var x = 0
            while (x < w) {
                val p = buf[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val rg = (r - g).toDouble()
                val yb = 0.5 * (r + g) - b
                sumRg += rg; sumRg2 += rg * rg
                sumYb += yb; sumYb2 += yb * yb

                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx - mn <= ACHROMATIC) flatCount++

                val tx = (x * TILES_X / w).coerceAtMost(TILES_X - 1)
                val idx = ty * TILES_X + tx
                tileTotal[idx]++
                if (isSkin(r, g, b, mx, mn)) {
                    tileSkin[idx]++
                    skinCount++
                }
                n++
                x += STEP
            }
            y += STEP
        }

        if (n == 0) return Stats(1f, 1f, 99f, 0f)

        val meanRg = sumRg / n
        val meanYb = sumYb / n
        val varRg = (sumRg2 / n) - meanRg * meanRg
        val varYb = (sumYb2 / n) - meanYb * meanYb
        val colorfulness = (sqrt(max(0.0, varRg) + max(0.0, varYb)) +
                0.3 * sqrt(meanRg * meanRg + meanYb * meanYb)).toFloat()

        var skinMax = 0f
        for (i in tileSkin.indices) {
            val total = tileTotal[i]
            if (total < MIN_TILE_PIXELS) continue
            val ratio = tileSkin[i].toFloat() / total
            if (ratio > skinMax) skinMax = ratio
        }

        return Stats(
            skinMax = skinMax,
            skinMean = skinCount.toFloat() / n,
            colorfulness = colorfulness,
            flatRatio = flatCount.toFloat() / n
        )
    }

    /**
     * Ten tonu testi: RGB kuralı (Kovac ve ark.) + YCbCr kroma kutusu.
     * İkisi birden gerekiyor — RGB kuralı tek başına ahşap, bej ve turuncu
     * arayüz öğelerini de ten sayıyor, kroma kutusu onları eliyor.
     */
    private fun isSkin(r: Int, g: Int, b: Int, mx: Int, mn: Int): Boolean {
        val bright = r > 95 && g > 40 && b > 20 && (mx - mn) > 15 &&
                abs(r - g) > 15 && r > g && r > b
        val dim = r > 60 && g > 30 && b > 20 && (mx - mn) > 10 &&
                r > g && r > b && (r - g) in 8..90
        if (!bright && !dim) return false

        val cb = 128.0 - 0.169 * r - 0.331 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.419 * g - 0.081 * b
        return cb in 77.0..135.0 && cr in 133.0..180.0
    }

    companion object {
        /** Örnekleme adımı. Kare zaten 1/4 çözünürlükte. */
        private const val STEP = 2

        private const val TILES_X = 4
        private const val TILES_Y = 6
        private const val MIN_TILE_PIXELS = 40

        /** Bu fark altındaki piksel gri/siyah/beyaz sayılır. */
        private const val ACHROMATIC = 18

        private const val FLAT_MIN = 0.62f
        private const val LINEART_COLOR_MAX = 22f
        private const val MONO_MAX = 10f

        /** Bu ten oranının üstünde model serbest. */
        private const val SKIN_FULL = 0.12f

        /** Bu oranın altında piksel modeli desteklemiyor. */
        private const val SKIN_NONE = 0.03f
    }
}
