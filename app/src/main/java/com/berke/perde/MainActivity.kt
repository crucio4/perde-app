package com.berke.perde

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var prefs: android.content.SharedPreferences

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenGuardService::class.java).apply {
                putExtra(ScreenGuardService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenGuardService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            Guard.setEnabled(this, true)
            toast(getString(R.string.toast_active))
            refresh()
        } else toast(getString(R.string.toast_denied))
    }

    /**
     * Bildirim izni reddedilse bile servis calisir — foreground service
     * bildirimi sistem tarafindan zorunlu tutuluyor, sadece kullaniciya
     * gorunmuyor. Yine de isteriz: servisin ayakta olup olmadigini
     * anlamanin tek gorsel yolu o bildirim.
     */
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("perde", Context.MODE_PRIVATE)
        status = findViewById(R.id.status)

        // Android 13+ POST_NOTIFICATIONS'i runtime izni yapti; manifestte
        // bildirmek yetmiyor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setupLanguage()
        setupProfile()
        setupSensitivity()

        findViewById<CheckBox>(R.id.checkOriginal).apply {
            isChecked = Motivation.getShowOriginal(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                Motivation.setShowOriginal(this@MainActivity, v)
            }
        }

        SecurePolicy.load(this)
        findViewById<CheckBox>(R.id.checkBlockSecure).apply {
            isChecked = SecurePolicy.blockOnSecure
            setOnCheckedChangeListener { _, v ->
                SecurePolicy.save(this@MainActivity, v)
            }
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnUsagePerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnA11yPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { start() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { requestStop() }
    }

    private fun setupLanguage() {
        val sp = findViewById<Spinner>(R.id.spinnerLang)
        val langs = listOf("Türkçe", "English")
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        sp.setSelection(if (Motivation.getLang(this) == Motivation.Lang.TR) 0 else 1)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                val yeni = if (i == 0) Motivation.Lang.TR else Motivation.Lang.EN
                if (yeni != Motivation.getLang(this@MainActivity)) {
                    Motivation.setLang(this@MainActivity, yeni)
                    recreate()   // dil değişti, arayüzü yeniden çiz
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * Spinner.setSelection() dinleyiciyi de tetikliyor. Dinleyici prefs'e
     * yazdigi icin, ekran her acildiginda kullanicinin secimi programatik
     * secimle EZILIYORDU. Bu bayrak ilk (programatik) geri cagriyi yutuyor;
     * sp.post{} pending layout'tan sonra calistigi icin dogru ani yakaliyor.
     */
    private fun <T : AdapterView<*>> T.ignoreFirstSelection(): BooleanArray {
        val flag = booleanArrayOf(true)
        post { flag[0] = false }
        return flag
    }

    private fun setupProfile() {
        val sp = findViewById<Spinner>(R.id.spinnerProfile)
        val etiketler = listOf(
            getString(R.string.profile_muslim),
            getString(R.string.profile_christian),
            getString(R.string.profile_jewish),
            getString(R.string.profile_secular),
            getString(R.string.profile_custom)
        )
        val profiller = listOf(
            Motivation.Profile.MUSLIM, Motivation.Profile.CHRISTIAN,
            Motivation.Profile.JEWISH, Motivation.Profile.SECULAR,
            Motivation.Profile.CUSTOM
        )
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, etiketler)

        // coerceAtLeast(3) idi: alt siniri 3'e cektigi icin KAYDEDILMIS profil
        // ne olursa olsun index 3'e ("Ateist / Diger") zipliyordu, sonra da
        // dinleyici bu yanlis secimi prefs'e yazip gercek secimi siliyordu.
        // Dogrusu 0: indexOf bulamazsa (-1) ilk maddeye dus.
        sp.setSelection(profiller.indexOf(Motivation.getProfile(this)).coerceAtLeast(0))
        val ilk = sp.ignoreFirstSelection()
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                if (ilk[0]) return
                Motivation.setProfile(this@MainActivity, profiller[i])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSensitivity() {
        val sp = findViewById<Spinner>(R.id.spinnerSens)
        val etiketler = listOf(
            getString(R.string.sens_balanced),
            getString(R.string.sens_strict),
            getString(R.string.sens_severe)
        )
        val seviyeler = listOf(Hassasiyet.DENGELI, Hassasiyet.SIKI, Hassasiyet.KATI)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, etiketler)

        Hassasiyet.load(this)
        sp.setSelection(seviyeler.indexOf(Hassasiyet.aktif).coerceAtLeast(0))

        val ilk = sp.ignoreFirstSelection()
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                if (ilk[0]) return
                Hassasiyet.save(this@MainActivity, seviyeler[i])
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_overlay_first)); return
        }
        if (!ForegroundAppWatcher(this).hasPermission()) {
            toast(getString(R.string.toast_usage_first)); return
        }
        // Tercih edilen yol: erişilebilirlik servisinin takeScreenshot'ı.
        // Ekran yakalama izni sormaz, ekran kaydı göstergesi çıkarmaz,
        // bildirim içeriklerini gizletmez ve görevlerden silinince sistem
        // servisi yeniden bağladığı için koruma kendiliğinden geri gelir.
        if (Guard.startViaA11y(this)) {
            toast(getString(R.string.toast_active))
            refresh()
            return
        }

        // Yedek yol: MediaProjection (API 30 altı ya da erişilebilirlik kapalı)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** Gecikmeli kapatma — anlık dürtüyle kapatmayı engellemek için. */
    private fun requestStop() {
        val now = System.currentTimeMillis()
        val requestedAt = prefs.getLong(KEY_STOP_REQUEST, 0L)

        if (requestedAt == 0L) {
            prefs.edit().putLong(KEY_STOP_REQUEST, now).apply()
            toast("${Config.DISABLE_DELAY_MS / 60000} dk / min")
            refresh(); return
        }
        val remaining = Config.DISABLE_DELAY_MS - (now - requestedAt)
        if (remaining > 0) { toast("${remaining / 1000}s"); return }

        Guard.stop(this)
        prefs.edit().remove(KEY_STOP_REQUEST).apply()
        toast(getString(R.string.toast_stopped))
        refresh()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = ForegroundAppWatcher(this).hasPermission()
        val requestedAt = prefs.getLong(KEY_STOP_REQUEST, 0L)
        val h = Hassasiyet.aktif

        val d = getSharedPreferences(ScreenGuardService.DIAG_PREFS, Context.MODE_PRIVATE)

        status.text = buildString {
            append("overlay      ").append(if (overlayOk) "OK" else "--").append('\n')
            append("usage stats  ").append(if (usageOk) "OK" else "--").append('\n')
            append("mode         ").append(Config.monitorMode).append('\n')
            append("W_sexy=").append(h.wSexy)
            append("  soft=").append(h.soft)
            append("  hard=").append(h.hard)

            // --- Tani ---
            // Baska bir uygulamada gezip buraya donunce burasi ne olculdugunu
            // gosterir. Sayaclar uygulama degisiminde sifirlanir.
            append("\n\n--- son olcum ---\n")
            val modelOk = d.getBoolean(ScreenGuardService.D_MODEL_OK, false)
            append("model        ").append(if (modelOk) "OK" else "YUKLENMEDI").append('\n')
            if (!modelOk) {
                append("hata         ")
                    .append(d.getString(ScreenGuardService.D_MODEL_ERR, "-")).append('\n')
            }
            append("koruma       ").append(if (Guard.isEnabled(this@MainActivity)) "ACIK" else "kapali")
            append('\n')

            // --- Dongu yasiyor mu? ---
            // "Bir kez blokladi, sonra hic tespit etmiyor" sikayetinde ILK
            // bakilacak satir. Kac saniye once tick attigini soyluyor:
            // 0-1 sn ise dongu saglam, sorun esiklerde ya da okumada.
            // Buyuyup duruyorsa dongu olmus, esiklere bakmanin anlami yok.
            val hb = d.getLong(ScreenGuardService.D_HEARTBEAT, 0L)
            append("dongu        ")
            if (hb == 0L) append("hic calismadi")
            else append(((System.currentTimeMillis() - hb) / 1000)).append(" sn once")
            append('\n')
            append("son durum    ")
                .append(d.getString(ScreenGuardService.D_STATE, "-")).append('\n')

            // --- Ariza imzalari ---
            // Bu iki satirdan biri sifirdan buyukse sorun tespit
            // mantiginda DEGIL, servisin yasam dongusunde demektir.
            val lost = d.getInt(ScreenGuardService.D_A11Y_LOST, 0)
            if (lost > 0) append("A11Y KAYBI   ").append(lost).append(" tick\n")
            val starts = d.getInt(ScreenGuardService.D_LOOP_STARTS, 0)
            append("dongu kurulum").append(' ').append(starts)
            val startedAt = d.getLong(ScreenGuardService.D_LOOP_STARTED_AT, 0L)
            if (startedAt != 0L) {
                append("  (son ")
                    .append((System.currentTimeMillis() - startedAt) / 1000).append(" sn once)")
            }
            append('\n')

            val lastBlock = d.getLong(ScreenGuardService.D_LAST_BLOCK, 0L)
            if (lastBlock != 0L) {
                append("son blok     ")
                    .append((System.currentTimeMillis() - lastBlock) / 1000).append(" sn once\n")
                append("  sebep      ")
                    .append(d.getString(ScreenGuardService.D_LAST_BLOCK_WHY, "-")).append('\n')
            }
            val cd = d.getLong(ScreenGuardService.D_COOLDOWN, 0L)
            if (cd > 0) append("sogumada     ").append(cd).append(" ms\n")

            append("kaynak       ").append(d.getString(ScreenGuardService.D_SOURCE, "-")).append('\n')
            val a11yOn = PerdeAccessibilityService.instance != null
            append("erisilebilir ").append(if (a11yOn) "OK" else "KAPALI").append('\n')
            append("aktif profil ").append(d.getString(ScreenGuardService.D_SENS, "-")).append('\n')
            append("son paket    ").append(d.getString(ScreenGuardService.D_LAST_PKG, "-")).append('\n')
            append("analiz kare  ").append(d.getInt(ScreenGuardService.D_FRAMES, 0)).append('\n')
            append("pencere      ").append(d.getString(ScreenGuardService.D_WINDOW, "-")).append('\n')

            // --- Kanal 1: piksel ---
            append("\npiksel kanali\n")
            append("  durum      ")
                .append(d.getString(ScreenGuardService.D_BLIND, "-").let {
                    if (it == "-") "acik" else "KOR: $it"
                }).append('\n')
            // "kare yok" tek basina sebep soylemiyor; hata kodu burada.
            // Logu okunamayan cihazlarda tek ipucu bu satir.
            d.getString(ScreenGuardService.D_SOURCE_ERR, "-").let {
                if (it != "-") append("  sebep      ").append(it).append('\n')
            }
            append("  ten/renk   ")
                .append(d.getString(ScreenGuardService.D_IMAGE, "-")).append('\n')

            // --- Kanal 2: icerik ---
            // Gizli sekmede tek calisan kanal bu. Skor 0 ve "okuma yok"
            // goruyorsan erisilebilirlik servisi kapali demektir.
            append("icerik kanali\n")
            append("  skor       ")
                .append("%.2f".format(d.getFloat(ScreenGuardService.D_TEXT_RAW, 0f)))
                .append("  esik ").append(h.textSoft).append('\n')
            append("  okuma      ")
                .append(d.getString(ScreenGuardService.D_TEXT_INFO, "-")).append('\n')
            // Kor VE sessiz gecen ardisik tick. "Son care" ayarinin
            // hangi noktada oldugunu gosteren tek sayi.
            append("  sessiz     ").append(d.getInt(ScreenGuardService.D_STARVED, 0))
                .append(" tick\n")

            append("\nson skor     ")
                .append("%.3f".format(d.getFloat(ScreenGuardService.D_LAST_RAW, 0f)))
                .append("   ema ")
                .append("%.3f".format(d.getFloat(ScreenGuardService.D_LAST_EMA, 0f)))
                .append('\n')
            append("EN YUKSEK    ")
                .append("%.3f".format(d.getFloat(ScreenGuardService.D_MAX_RAW, 0f)))
                .append('\n')
            append("siniflar     ")
                .append(d.getString(ScreenGuardService.D_MAX_PROBS, "-")).append('\n')
            append("             draw hent neut porn sexy")

            if (requestedAt != 0L) {
                val rem = (Config.DISABLE_DELAY_MS - (System.currentTimeMillis() - requestedAt))
                    .coerceAtLeast(0) / 1000
                append("\nstop in ").append(rem).append("s")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_STOP_REQUEST = "stop_requested_at"
    }
}
