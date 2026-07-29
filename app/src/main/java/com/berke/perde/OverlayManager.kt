package com.berke.perde

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Tam ekran engelleme katmanı.
 *
 * killBackgroundProcesses üçüncü parti uygulamaları öldüremez (Android izin
 * vermiyor), o yüzden yaklaşım: üstüne opak overlay bindir + ana ekrana at.
 * Pratikte aynı sonucu veriyor.
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val layoutType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    fun show(message: String = "Kapat.") {
        main.post {
            if (view != null) return@post
            val v = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
            v.findViewById<TextView>(R.id.blockMessage)?.text = message

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply { gravity = Gravity.TOP or Gravity.START }

            try {
                wm.addView(v, params)
                view = v
            } catch (_: Exception) { /* izin yok */ }

            goHome()
        }
    }

    fun hide() {
        main.post {
            view?.let {
                try { wm.removeView(it) } catch (_: Exception) {}
            }
            view = null
        }
    }

    fun isShowing() = view != null

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }
}
