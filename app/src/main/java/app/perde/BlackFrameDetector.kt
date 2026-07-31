package app.perde

import android.graphics.Bitmap

/**
 * FLAG_SECURE aktifken MediaProjection tamamen siyah kare döndürür.
 *
 * Bu bir hata değil — bilgi. "Göremiyorum" durumunu görmezden gelmek
 * yerine sinyal olarak kullanıyoruz:
 *
 *   İzlenen bir tarayıcı önde + kare siyah = gizli sekme ya da
 *   korumalı içerik. Normal gezinmede bu durum oluşmaz.
 *
 * Yanlış pozitif kaynakları (bunları elemek zorundayız):
 *   - Ekran gerçekten siyah (koyu tema, video duraklatılmış, AMOLED siyah)
 *   - Ekran kapanma anı
 *   - Uygulama geçiş animasyonu
 *
 * Ayrım: FLAG_SECURE karesi *mükemmel* siyahtır (tüm pikseller 0).
 * Gerçek koyu ekranda her zaman biraz varyans olur — durum çubuğu,
 * gezinme çubuğu, antialiasing. Bu yüzden hem ortalama parlaklığa
 * hem de varyansa bakıyoruz.
 */
class BlackFrameDetector {

    data class Result(val isSecureBlack: Boolean, val mean: Double, val variance: Double)

    // Yeniden kullanılan tampon — her karede yeni dizi ayırmamak için
    private var buffer: IntArray? = null

    fun analyze(bmp: Bitmap): Result {
        // ÖNEMLİ: getPixel() piksel başına ayrı çağrı yapar ve çok yavaştır.
        // getPixels() ile tüm satırları tek seferde al, sonra örnekle.
        val w = bmp.width
        val h = bmp.height
        val buf = buffer?.takeIf { it.size >= w * h } ?: IntArray(w * h).also { buffer = it }
        bmp.getPixels(buf, 0, w, 0, 0, w, h)

        var sum = 0.0
        var sumSq = 0.0
        var n = 0

        for (y in 0 until h step SAMPLE_STEP) {
            val row = y * w
            for (x in 0 until w step SAMPLE_STEP) {
                val p = buf[row + x]
                // Luma (BT.601) — tam hesap yerine kaba ağırlık yeterli
                val lum = 0.299 * ((p shr 16) and 0xFF) +
                          0.587 * ((p shr 8) and 0xFF) +
                          0.114 * (p and 0xFF)
                sum += lum
                sumSq += lum * lum
                n++
            }
        }

        if (n == 0) return Result(false, 0.0, 0.0)

        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)

        return Result(
            isSecureBlack = mean < MEAN_THRESHOLD && variance < VARIANCE_THRESHOLD,
            mean = mean,
            variance = variance
        )
    }

    companion object {
        /** Kaç piksel örneklenecek (tüm kareyi taramak gereksiz pahalı). */
        private const val SAMPLE_STEP = 8

        /** Bu ortalamanın altı "siyah" sayılır (0-255). */
        private const val MEAN_THRESHOLD = 3.0

        /** Bu varyansın altı "yapay siyah" sayılır. Gerçek koyu ekranda varyans yüksektir. */
        private const val VARIANCE_THRESHOLD = 2.0
    }
}
