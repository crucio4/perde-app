package com.berke.perde

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ekranın o anki metin içeriği. Ham hâliyle tutulur, ASLA diske yazılmaz,
 * ASLA loglanmaz — yalnızca bellekte, yalnızca bir sonraki okumaya kadar.
 */
class ScreenContent(
    val pkg: String,
    val url: String,
    val title: String,
    val body: String,
    val nodes: Int,
    /** SystemClock.uptimeMillis() */
    val at: Long
) {
    val isEmpty: Boolean get() = url.isEmpty() && title.isEmpty() && body.isEmpty()

    companion object { val EMPTY = ScreenContent("", "", "", "", 0, 0L) }
}

/**
 * Erişilebilirlik ağacından ekrandaki metni toplar.
 *
 * BURASI KÖR NOKTANIN KAPANDIĞI YER.
 *
 * FLAG_SECURE render edilmiş YÜZEYİ korur — ekran görüntüsü siyah gelir,
 * MediaProjection kare vermez, takeScreenshot hata döner. Ama erişilebilirlik
 * ağacı yüzey değil, ayrı bir yapıdır ve o bayraktan etkilenmez. Gizli
 * sekmede ekran görüntüsü alınamazken adres çubuğu, sayfa başlığı, başlıklar,
 * bağlantı metinleri, görsel alt metinleri okunmaya devam eder.
 *
 * Yani gizli sekmede "hiçbir şey göremiyoruz" doğru değil: PİKSELİ
 * göremiyoruz, İÇERİĞİ görüyoruz. Karar da içerikten veriliyor
 * (bkz. ContentAnalyzer) — "tarayıcı + göremiyorum = blokla" tahmininden
 * değil. Bankacılık uygulaması da okunuyor, okunan şey bankacılık olduğu
 * için bloklanmıyor. İsim listesine gerek kalmıyor.
 *
 * MALİYET: ağaç yürüyüşü uygulama sınırını aşan IPC demek. Bu yüzden üç
 * bütçe birden var (düğüm, derinlik, süre) ve iki okuma arasında en az
 * MIN_INTERVAL_MS bekleniyor.
 */
object ScreenReader {

    /** En son okunan içerik. Tespit döngüsü buradan okur. */
    @Volatile
    var latest: ScreenContent = ScreenContent.EMPTY
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var pending = false
    @Volatile private var lastHarvestAt = 0L

    /**
     * Yeni okuma ister. Kısıtlanmış ve asenkron: çağıran iş parçacığını
     * bloklamaz, sonucu bir sonraki tick'te [latest] üzerinden görür.
     * Yakalama boru hattındaki bir tick gecikmeyle aynı takas.
     */
    fun requestRefresh() {
        val svc = PerdeAccessibilityService.instance ?: return
        if (pending) return
        if (SystemClock.uptimeMillis() - lastHarvestAt < MIN_INTERVAL_MS) return
        pending = true
        main.post {
            try {
                harvest(svc)
            } catch (e: Exception) {
                Log.w(TAG, "Okuma başarısız: ${e.javaClass.simpleName}")
            } finally {
                pending = false
            }
        }
    }

    fun clear() { latest = ScreenContent.EMPTY }

    // ------------------------------------------------------------------

    private fun harvest(svc: AccessibilityService) {
        lastHarvestAt = SystemClock.uptimeMillis()

        val root = try { svc.rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val pkg = root.packageName?.toString().orEmpty()
            // Kendi overlay'imizi ve dışlanan uygulamaları hiç okumuyoruz.
            if (pkg.isEmpty() || pkg in Config.EXCLUDED_PACKAGES) {
                latest = ScreenContent.EMPTY
                return
            }

            val sink = Sink()
            sink.title = runCatching { root.window?.title?.toString() }.getOrNull().orEmpty()

            val budget = Budget(SystemClock.uptimeMillis() + MAX_MILLIS)
            walk(root, 0, budget, sink)

            latest = ScreenContent(
                pkg = pkg,
                url = sink.url.ifEmpty { sink.urlGuess },
                title = sink.title,
                body = sink.body.toString(),
                nodes = budget.nodes,
                at = SystemClock.uptimeMillis()
            )
        } finally {
            runCatching { @Suppress("DEPRECATION") root.recycle() }
        }
    }

    private class Budget(val deadline: Long) { var nodes = 0 }

    private class Sink {
        /** Adres çubuğu id'sinden gelen adres — yüksek güven. */
        var url = ""
        /** Adres gibi görünen ilk metin — id bulunamazsa kullanılır. */
        var urlGuess = ""
        var title = ""
        val body = StringBuilder(2048)
        private val seen = HashSet<String>(256)

        fun add(raw: CharSequence?) {
            if (raw.isNullOrBlank()) return
            if (body.length >= MAX_BODY) return
            val s = raw.toString().trim()
            if (s.length > MAX_FIELD) return
            if (!seen.add(s)) return          // liste/RecyclerView tekrarları
            body.append(s).append(' ')
        }

        companion object {
            /** Toplam metin tavanı. Ötesi analiz için bilgi taşımıyor. */
            private const val MAX_BODY = 8000

            /** Tek düğümden alınacak en uzun metin. Uzun bloklar genelde
             *  gizlenmiş içerik listeleri; yoğunluk hesabını bozuyorlar. */
            private const val MAX_FIELD = 600
        }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, b: Budget, sink: Sink) {
        if (b.nodes >= MAX_NODES || depth > MAX_DEPTH) return
        // Süre bütçesi: her 32 düğümde bir bak, saat okumak da bedava değil
        if ((b.nodes and 31) == 0 && SystemClock.uptimeMillis() > b.deadline) {
            b.nodes = MAX_NODES
            return
        }
        b.nodes++

        collect(node, sink)

        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            try {
                walk(child, depth + 1, b, sink)
            } finally {
                runCatching { @Suppress("DEPRECATION") child.recycle() }
            }
            if (b.nodes >= MAX_NODES) return
        }
    }

    private fun collect(node: AccessibilityNodeInfo, sink: Sink) {
        val text = node.text
        val desc = node.contentDescription
        val hint = runCatching { node.hintText }.getOrNull()

        // --- Adres çubuğu ---
        // Önce bilinen id'ler, sonra davranışa dayalı yedek. Yedek şart:
        // bilmediğimiz bir tarayıcı da kapsansın diye. Kimlik id'de değil,
        // "adres çubuğu gibi davranan düğüm" tanımında.
        if (sink.url.isEmpty()) {
            val id = node.viewIdResourceName
            if (id != null && URL_BAR_HINTS.any { it in id }) {
                val candidate = (text ?: desc)?.toString()?.trim().orEmpty()
                if (candidate.isNotEmpty()) sink.url = candidate
            } else if (sink.urlGuess.isEmpty() && text != null && looksLikeUrl(text)) {
                // Sayfa içindeki bir bağlantı da olabilir. Id'li eşleşme
                // gelirse bu düşüyor; gelmezse bilmediğimiz bir tarayıcıda
                // tek adres kaynağımız bu.
                sink.urlGuess = text.toString().trim()
            }
        }

        sink.add(text)
        sink.add(desc)
        sink.add(hint)
    }

    private fun looksLikeUrl(s: CharSequence): Boolean {
        if (s.length !in 4..300) return false
        return URL_RE.matches(s.toString().trim())
    }

    private const val TAG = "ScreenReader"

    /** İki okuma arasındaki en kısa süre. Döngü 600 ms'te bir tick atıyor. */
    private const val MIN_INTERVAL_MS = 500L

    private const val MAX_NODES = 1400
    private const val MAX_DEPTH = 45
    private const val MAX_MILLIS = 90L

    private val URL_BAR_HINTS = listOf(
        "url_bar", "url_view", "location_bar", "omnibar", "address_bar",
        "urlbar", "search_box_text", "toolbar_url"
    )

    private val URL_RE = Regex(
        """^(https?://)?(www\.)?[a-z0-9][a-z0-9-]*(\.[a-z0-9-]+)+(:\d+)?(/[^\s]*)?$""",
        RegexOption.IGNORE_CASE
    )
}
