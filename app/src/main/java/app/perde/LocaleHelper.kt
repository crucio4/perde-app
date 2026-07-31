package app.perde

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Uygulama içi dil seçimi.
 *
 * Android varsayılan olarak sistem diline uyar. Kullanıcının uygulama
 * içinden ayrı dil seçebilmesi için Context'i sarmalıyoruz.
 *
 * Kullanım: her Activity'de attachBaseContext override edilir.
 */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val lang = when (Motivation.getLang(base)) {
            Motivation.Lang.TR -> "tr"
            Motivation.Lang.EN -> "en"
        }
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
