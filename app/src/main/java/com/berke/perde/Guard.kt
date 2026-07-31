package com.berke.perde

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Korumanin acik/kapali durumu ve baslatilmasi tek yerde.
 *
 * "Acik" bayragi kaliciya yaziliyor: surec olduruldugunde (kullanici
 * uygulamayi son kullanilanlardan sildiginde ya da ureticinin agresif
 * gorev sonlandirmasi devreye girdiginde) sistem erisilebilirlik
 * servisini yeniden bagliyor, o da bu bayraga bakip korumayi kendiliginden
 * geri getiriyor.
 *
 * MediaProjection yolunda bu mumkun degil: token surec olumunu atlatamiyor,
 * kullaniciya yeniden izin ekrani gostermek gerekiyor. Arka planda kalicilik
 * isteniyorsa erisilebilirlik yolu sart.
 */
object Guard {

    private const val PREFS = "perde"
    private const val KEY_ENABLED = "guard_enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * Koruma GERCEKTEN calisiyor mu?
     *
     * isEnabled() bunu soylemiyor: o "acik olmali" diyen kalici bir niyet
     * bayragi. Bayrak true iken korumanin fiilen olmadigi en az iki durum
     * var — MediaProjection yolunda surec olunce token kayboluyor, bir de
     * kullanici erisilebilirlik servisini sistem ayarlarindan kapatabiliyor.
     *
     * Ana ekranin ustundeki satir bu ayrimi yapmak zorunda: "ACIK" yazip
     * aslinda korumasiz olmak, kullaniciyi hic korumamaktan daha kotu.
     */
    fun isRunning(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false

        // Erisilebilirlik yolu: dongu nesnesi elimizde, sagligini kendi
        // biliyor (henuz ilk tick'ini atmamis olmasina da izin veriyor).
        if (PerdeAccessibilityService.instance?.loopSaglikli() == true) return true

        // MediaProjection yolu: dongu baska bir serviste yasiyor. Iki yolun
        // da yazdigi tek ortak isaret kalp atisi — tick()'te butun erken
        // donuslerden ONCE yaziliyor, yani "calisiyor mu" sorusunu tam
        // olarak cevapliyor.
        val d = ctx.getSharedPreferences(ScreenGuardService.DIAG_PREFS, Context.MODE_PRIVATE)
        val simdi = System.currentTimeMillis()
        val hb = d.getLong(ScreenGuardService.D_HEARTBEAT, 0L)
        if (hb != 0L && simdi - hb < DetectionLoop.OLU_SAYILMA_MS) return true

        // Yeni kurulmus dongu henuz ilk tick'ini atmamis olabilir; o aralikta
        // eski/bayat kalp atisina bakip "kapali" demek yanlis alarm olurdu.
        val kurulus = d.getLong(ScreenGuardService.D_LOOP_STARTED_AT, 0L)
        return kurulus != 0L && simdi - kurulus < DetectionLoop.OLU_SAYILMA_MS
    }

    /**
     * takeScreenshot yolu kullanilabilir mi?
     *
     * Bu kontrol bilerek A11yCapturer'in DISINDA: o sinif API 30'da gelen
     * tiplere (TakeScreenshotCallback, ScreenshotResult) referans veriyor ve
     * eski cihazlarda sinifi yuklemeye calismak dogrulama hatasi verebilir.
     * Kontrolun kendisi cokmeye sebep olmamali.
     */
    fun a11yCaptureAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                PerdeAccessibilityService.instance != null

    /**
     * Erisilebilirlik tabanli baslatma. Ekran yakalama izni sormaz,
     * foreground service gerektirmez, gorevlerden silinmeye dayaniklidir.
     *
     * @return devralindiysa true; false ise MediaProjection yoluna dusulmeli
     */
    fun startViaA11y(ctx: Context): Boolean {
        if (!a11yCaptureAvailable()) return false
        setEnabled(ctx, true)
        PerdeAccessibilityService.instance?.startLoop() ?: return false
        return true
    }

    fun stop(ctx: Context) {
        setEnabled(ctx, false)
        PerdeAccessibilityService.instance?.stopLoop()
        ctx.stopService(Intent(ctx, ScreenGuardService::class.java))
    }
}
