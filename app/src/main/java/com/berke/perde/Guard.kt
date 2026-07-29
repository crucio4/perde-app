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
