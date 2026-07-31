package app.perde

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
 * Tek bir yüksek kare bloklamaya ASLA yetmez — HARD eşiği bile iki ardışık
 * kare istiyor. Bu kasıtlı: kaydırırken denk gelen tek kare, reklam,
 * thumbnail ya da modelin tek karelik sapması tetiklememeli.
 *
 * Motor skorun NEREDEN geldiğini bilmiyor. Görsel sınıflandırıcı da metin
 * analizi de aynı ölçeğe eşlenip buraya giriyor; böylece iki kanal da
 * aynı yanlış-pozitif katmanlarından geçiyor.
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

    /** HARD eşiğini üst üste kaç kare geçti. */
    private var hardStreak = 0

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

        if (ema >= Hassasiyet.aktif.hard) hardStreak++ else hardStreak = 0

        val previousState = state

        when (state) {
            State.CLEAR -> {
                val inCooldown = now < cooldownUntil

                // HARD: tartışmasız durum, pencere oylamasını atlar.
                //
                // Eskiden TEK kare yetiyordu ve "anında blok" buradan
                // geliyordu: model bir çöp adam çizimini 0.99 hentai
                // sayınca ekran o saniye kapanıyordu. Tek karelik bir
                // model çıktısı hiçbir zaman bu kadar güvenilir değil.
                // İki ardışık kare ~1.2 saniye demek; hızlı yol duruyor,
                // tek karelik sapmalar eleniyor.
                val hardHit = hardStreak >= Config.HARD_FRAMES_REQUIRED

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
                    hardStreak = 0
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

    /**
     * Soğumayı dışarıdan başlatır.
     *
     * NEDEN GEREKLİ: blok ekranını döngü kaldırıyor (zorunlu süre
     * dolunca) ve soğumayı da döngü tutuyor. Motorun kendi
     * `cooldownUntil`'i yalnızca BLOCKED -> CLEAR geçişinde kuruluyordu,
     * ama o geçiş hiç yaşanmıyor: overlay açıkken tick() en başta
     * dönüyor, yani motor blok boyunca hiç güncellenmiyor ve sonunda
     * reset() ile temizleniyor.
     *
     * Sonuç bir kilitlenmeydi: reset sonrası motor CLEAR oluyor, aynı
     * içerik hâlâ ekranda olduğu için iki karede yeniden BLOCKED'a
     * geçiyor, fakat döngü kendi soğuması dolmadığı için overlay'i
     * açmıyor. Motor "ekransız BLOCKED"da asılı kalıyor ve oradan
     * çıkmak için skorun düşmesini bekliyor — kullanıcı aynı sayfada
     * olduğu için skor hiç düşmüyor. Uygulama oturum başına bir kez
     * bloklayıp sessizce koruma bırakıyordu.
     */
    fun startCooldown(now: Long) {
        cooldownUntil = now + Config.COOLDOWN_MS
    }

    /** Uygulama değiştiğinde / yakalama durduğunda çağır. */
    fun reset() {
        window.clear()
        ema = 0f
        emaInitialized = false
        state = State.CLEAR
        consecutiveClearFrames = 0
        blockStartedAt = 0L
        hardStreak = 0
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
