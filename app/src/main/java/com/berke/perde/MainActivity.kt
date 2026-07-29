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
        sp.setSelection(profiller.indexOf(Motivation.getProfile(this)).coerceAtLeast(3))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
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

        val kayitli = prefs.getString(KEY_SENS, Hassasiyet.DENGELI.name)!!
        val aktif = runCatching { Hassasiyet.valueOf(kayitli) }.getOrDefault(Hassasiyet.DENGELI)
        Hassasiyet.aktif = aktif
        sp.setSelection(seviyeler.indexOf(aktif).coerceAtLeast(0))

        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                Hassasiyet.aktif = seviyeler[i]
                prefs.edit().putString(KEY_SENS, seviyeler[i].name).apply()
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

        stopService(Intent(this, ScreenGuardService::class.java))
        prefs.edit().remove(KEY_STOP_REQUEST).apply()
        toast(getString(R.string.toast_stopped))
        refresh()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = ForegroundAppWatcher(this).hasPermission()
        val requestedAt = prefs.getLong(KEY_STOP_REQUEST, 0L)
        val h = Hassasiyet.aktif

        status.text = buildString {
            append("overlay      ").append(if (overlayOk) "OK" else "--").append('\n')
            append("usage stats  ").append(if (usageOk) "OK" else "--").append('\n')
            append("mode         ").append(Config.monitorMode).append('\n')
            append("W_sexy=").append(h.wSexy)
            append("  soft=").append(h.soft)
            append("  hard=").append(h.hard)
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
        private const val KEY_SENS = "sensitivity"
    }
}
