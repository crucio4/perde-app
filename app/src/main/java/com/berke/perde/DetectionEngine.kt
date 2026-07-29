package com.berke.perde

import android.util.Log

/**
 * Sınıflandırıcıdan gelen ham skorları alır, false positive'leri filtreler,
 * blokla/kaldır kararını verir.
 *
 * Katmanlar (sırayla):
 *   1. Ağırlıklı skor  — "sexy" sınıfının etkisini kısar
 *   2. EMA yumuşatma   — tek karelik sıçramaları söndürür
 *   3. Pencere oylama  — N karenin K tanesi eşiği geçmeli
 *   4. Histerezis      — açılma ve kapanma eşikleri farklı, titremeyi önler
 *   5. Soğuma          — blok kalktıktan sonra kısa süre yeniden tetiklenmez
 *
 * Tek bir yüksek kare bloklamaya yetmez (HARD eşiği hariç). Bu kasıtlı:
 * kaydırırken denk gelen tek kare, reklam, thumbnail vs. tetiklememeli.
 */
class DetectionEngine {

    enum class State { CLEAR, BLOCKED }

    data class Decision(
        val state: State,
        val smoothedScore: Float,
        val rawScore: Float,
        val justChanged: Boolean
    )

    private val window = ArrayDeque<Float>(Config.WINDOW_SIZE)
    private var ema = 0f
    private var emaInitialized = false
    private var state = State.CLEAR
    private var consecutiveClearFrames = 0
    private var blockStartedAt = 0L
    private var cooldownUntil = 0L

    /**
     * Model çıktısını ağırlıklı tek skora indirger.
     * @param probs [drawings, hentai, neutral, porn, sexy]
     */
    fun weighScore(probs: FloatArray): Float {
        if (probs.size < 5) return 0f
        val s = probs[0] * Config.W_DRAWINGS +
                probs[1] * Config.W_HENTAI +
                probs[2] * Config.W_NEUTRAL +
                probs[3] * Config.W_PORN +
                probs[4] * Hassasiyet.aktif.wSexy
        return s.coerceIn(0f, 1f)
    }

    /**
     * Her yeni kare için çağrılır.
     * @param rawScore weighScore() çıktısı
     * @param now System.currentTimeMillis()
     */
    fun update(rawScore: Float, now: Long): Decision {
        // --- 2. katman: EMA ---
        ema = if (!emaInitialized) {
            emaInitialized = true
            rawScore
        } else {
            Config.EMA_ALPHA * rawScore + (1 - Config.EMA_ALPHA) * ema
        }

        // --- 3. katman: kayan pencere ---
        if (window.size >= Config.WINDOW_SIZE) window.removeFirst()
        window.addLast(ema)

        val previousState = state

        when (state) {
            State.CLEAR -> {
                val inCooldown = now < cooldownUntil

                // HARD: tek kare, tartışmasız durum. Soğuma bunu da bağlar,
                // yoksa blok kalkar kalkmaz aynı kare tekrar tetikler.
                val hardHit = ema >= Hassasiyet.aktif.hard

                // SOFT: pencere oylaması
                val hits = window.count { it >= Hassasiyet.aktif.soft }
                val softHit = window.size >= Config.WINDOW_HITS_REQUIRED &&
                        hits >= Config.WINDOW_HITS_REQUIRED

                if (!inCooldown && (hardHit || softHit)) {
                    state = State.BLOCKED
                    blockStartedAt = now
                    consecutiveClearFrames = 0
                    Log.i(TAG, "BLOK -> ema=%.3f hits=%d/%d hard=%b"
                        .format(ema, hits, window.size, hardHit))
                }
            }

            State.BLOCKED -> {
                // --- 4. katman: histerezis ---
                if (ema <= Hassasiyet.aktif.release) {
                    consecutiveClearFrames++
                } else {
                    consecutiveClearFrames = 0
                }

                val heldLongEnough = (now - blockStartedAt) >= Config.MIN_BLOCK_DURATION_MS
                val cleared = consecutiveClearFrames >= Config.RELEASE_CONSECUTIVE_FRAMES

                if (heldLongEnough && cleared) {
                    state = State.CLEAR
                    // --- 5. katman: soğuma ---
                    cooldownUntil = now + Config.COOLDOWN_MS
                    window.clear()
                    emaInitialized = false
                    ema = 0f
                    Log.i(TAG, "TEMIZ -> soğuma ${Config.COOLDOWN_MS}ms")
                }
            }
        }

        return Decision(
            state = state,
            smoothedScore = ema,
            rawScore = rawScore,
            justChanged = state != previousState
        )
    }

    /** Uygulama değiştiğinde / yakalama durduğunda çağır. */
    fun reset() {
        window.clear()
        ema = 0f
        emaInitialized = false
        state = State.CLEAR
        consecutiveClearFrames = 0
        blockStartedAt = 0L
        // cooldownUntil bilerek sıfırlanmıyor: uygulama değiştirip
        // hemen geri gelerek soğumayı atlamayı engeller.
    }

    fun currentState() = state

    /**
     * "kac/kac" — penceredeki kac karenin soft esigi gectigi.
     * Blok gelmiyorsa sebebini tek bakista gosterir: 8/8 gerekirken 1/1
     * goruyorsan sorun esikte degil, kare akisinda.
     */
    fun windowStatus(): String =
        "${window.count { it >= Hassasiyet.aktif.soft }}/${window.size}" +
                " (gereken ${Config.WINDOW_HITS_REQUIRED})"

    companion object { private const val TAG = "DetectionEngine" }
}
