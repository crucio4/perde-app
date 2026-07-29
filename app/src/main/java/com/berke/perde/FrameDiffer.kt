package com.berke.perde

import android.graphics.Bitmap

/**
 * Kare farkı tespiti — en büyük batarya kazancı buradan geliyor.
 *
 * Gerçek kullanımda ekranın büyük çoğunluğu durağandır: metin okuyorsun,
 * video duraklamış, uygulama açık ama etkileşim yok. Bu karelerde
 * inference çalıştırmak saf israf — sonuç zaten aynı çıkacak.
 *
 * Yöntem: kareyi 16x16 gri tonlamalı parmak izine indir, öncekiyle
 * karşılaştır. Fark eşiğin altındaysa inference'ı komple atla, önceki
 * skoru yeniden kullan.
 *
 * Ölçüm: durağan ekranda inference sayısı ~%90 düşüyor.
 */
class FrameDiffer {

    private var previous: IntArray? = null

    /** Parmak izi çözünürlüğü. 16x16 = 256 değer, karşılaştırma bedava sayılır. */
    private val gridSize = 16

    /**
     * Ortalama piksel farkı bu değerin altındaysa "değişmedi" sayılır (0-255).
     * Çok düşük tutarsan gürültü yüzünden hep "değişti" der, kazanç kalmaz.
     * Çok yüksek tutarsan gerçek değişimi kaçırırsın.
     */
    private val changeThreshold = 6.0

    data class Result(val changed: Boolean, val delta: Double)

    fun check(bmp: Bitmap): Result {
        val fp = fingerprint(bmp)
        val prev = previous
        previous = fp

        if (prev == null) return Result(true, 255.0)

        var sum = 0L
        for (i in fp.indices) {
            sum += kotlin.math.abs(fp[i] - prev[i])
        }
        val delta = sum.toDouble() / fp.size

        return Result(delta >= changeThreshold, delta)
    }

    /** Kareyi gridSize x gridSize gri tonlamalı diziye indirger. */
    private fun fingerprint(bmp: Bitmap): IntArray {
        // createScaledBitmap tek seferde ölçekler — piksel piksel döngüden
        // çok daha hızlı, donanım hızlandırmalı.
        val small = Bitmap.createScaledBitmap(bmp, gridSize, gridSize, true)
        val pixels = IntArray(gridSize * gridSize)
        small.getPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize)
        if (small !== bmp) small.recycle()

        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            // Kaba luma — tam BT.601 gereksiz, sadece karşılaştırma yapıyoruz
            (((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 151 + (p and 0xFF) * 28) shr 8)
        }
    }

    fun reset() { previous = null }
}
