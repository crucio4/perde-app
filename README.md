# Perde

Android için cihaz üzerinde çalışan ekran içeriği filtresi. Database/blocklist yok — ekranı gerçek zamanlı sınıflandırır, eşik aşılınca tam ekran overlay bindirip ana ekrana atar.

Hiçbir veri cihazdan çıkmaz. Tüm inference lokal (TFLite).

## Mimari

```
ForegroundAppWatcher  ─ izlenen uygulama önde mi? (değilse yakalama kapalı → batarya)
        ↓
ScreenCapturer        ─ MediaProjection + VirtualDisplay + ImageReader, 1 fps, 1/4 çözünürlük
        ↓
NsfwClassifier        ─ TFLite, 224x224, 5 sınıf çıktı
        ↓
DetectionEngine       ─ ağırlıklandırma → EMA → pencere oylama → histerezis → soğuma
        ↓
OverlayManager        ─ SYSTEM_ALERT_WINDOW overlay + HOME intent
```

## False positive katmanları

Tek karelik yüksek skor **asla** bloklamaz (HARD eşiği hariç). Beş katman sırayla:

| Katman | Ne yapar | Neyi engeller |
|---|---|---|
| Sınıf ağırlığı | `sexy` sınıfı 0.35 ile çarpılır, `drawings` 0 | Plaj, spor, moda, tişörtsüz fotoğraf |
| EMA (α=0.45) | Skoru yumuşatır | Ani tek kare sıçraması |
| Pencere oylama | Son 8 karenin 5'i ≥0.68 olmalı | Kaydırırken denk gelen kare, thumbnail, reklam |
| Histerezis | Açılma 0.68, kapanma 0.40 | Bloğun açılıp kapanıp titremesi |
| Soğuma | Blok kalktıktan 4s sonra yeniden tetiklenebilir | Flapping |

Simülasyon sonuçları (`sim.py` mantığıyla):

```
Tek kare sıçrama            -> tetiklenmedi     ✓
İki kare sıçrama            -> tetiklenmedi     ✓
Plaj/spor (sürekli 0.30)    -> tetiklenmedi     ✓
Moda reklamı (sürekli 0.58) -> tetiklenmedi     ✓
Gürültülü, ort. düşük       -> tetiklenmedi     ✓

Gerçek içerik, sabit yüksek -> BLOK @3s
Gerçek içerik, yavaş artan  -> BLOK @8s
Tartışmasız (HARD)          -> BLOK @0s
Dalgalı ama sürekli yüksek  -> BLOK @4s
```

Tepki süresi 3-8 saniye. Bu bilinçli bir takas: daha hızlı tetiklemek için pencereyi daraltırsan false positive oranı fırlar.

---

## Senin yapman gerekenler

### 1. Model dosyası

`app/src/main/assets/nsfw.tflite` yok — bunu sen koyacaksın. Uygun aday: MobileNetV2 tabanlı 5 sınıflı NSFW sınıflandırıcı (`drawings, hentai, neutral, porn, sexy`), Keras/H5 formatındaki açık kaynak sürümlerini `TFLiteConverter` ile dönüştürebilirsin:

```python
import tensorflow as tf
m = tf.keras.models.load_model("nsfw_mobilenet_v2_140_224")
c = tf.lite.TFLiteConverter.from_keras_model(m)
c.optimizations = [tf.lite.Optimize.DEFAULT]   # int8 quant, ~4x küçük, çok daha hızlı
open("nsfw.tflite", "wb").write(c.convert())
```

Farklı bir model kullanırsan `Config.INPUT_SIZE` ve `NsfwClassifier.NUM_CLASSES` ile sınıf sırasını (`weighScore`) güncelle.

### 2. Proje kurulumu

Android Studio → yeni proje aç → bu dosyaları kopyala. Kök `build.gradle.kts` ve `settings.gradle.kts` Android Studio'nun ürettiği standart hâliyle kalabilir.

### 3. Cihazda izinler (sırayla, manuel)

1. **Overlay** — uygulama içinden buton, Ayarlar'a atar
2. **Kullanım erişimi** — Ayarlar > Özel erişim > Kullanım erişimi, manuel açman gerekiyor, runtime prompt yok
3. **Ekran yakalama** — Başlat'a basınca sistem sorar, her yeniden başlatmada tekrar sorar (Android bunu bypass ettirmiyor)
4. **Pil optimizasyonu dışına al** — yoksa sistem servisi öldürür. Ayarlar > Pil > Kısıtlanmamış

### 4. Kalibrasyon

İlk kurulumdan sonra eşikleri kendi kullanımına göre ayarla. `ScreenGuardService.tick()` içine geçici log ekle:

```kotlin
Log.d("CAL", "raw=%.3f ema=%.3f pkg=%s".format(raw, decision.smoothedScore, pkg))
```

`adb logcat -s CAL` ile normal kullanımda 1-2 gün skorları izle. Günlük içerikte gördüğün en yüksek skor `SOFT_THRESHOLD`'un belirgin altında kalmalı. Kalmıyorsa:

- Yanlış tetikleniyor → `SOFT_THRESHOLD` +0.05, `WINDOW_HITS_REQUIRED` +1, `W_SEXY` -0.10
- Geç/hiç tetiklenmiyor → `SOFT_THRESHOLD` -0.05, `WINDOW_HITS_REQUIRED` -1

Tek seferde tek parametre değiştir, yoksa neyin işe yaradığını göremezsin.

### 5. İzlenen uygulama listesi

`Config.WATCHED_PACKAGES` — kendi kullandığın tarayıcı/uygulamaların paket adlarını ekle. Listede olmayan uygulamalarda yakalama hiç çalışmaz, bu yüzden eksik bırakırsan boşluk kalır.

---

## Bilinmesi gerekenler

- **Bildirim gizlenemez.** Foreground service kalıcı bildirim zorunlu. IMPORTANCE_MIN ile en aza indirildi ama kaldırılamaz.
- **Ekran kayıt göstergesi.** Android 14+ MediaProjection aktifken durum çubuğunda gösterge çıkarabilir. Bu sistem davranışı, bypass edilemez.
- **Uygulamayı öldüremezsin.** `killBackgroundProcesses` üçüncü parti uygulamalara işlemiyor. Overlay + HOME intent pratikte aynı işi görüyor.
- **Kapatma gecikmesi.** `DISABLE_DELAY_MS` = 15 dk. Bunu düşürürsen uygulamanın anlamı kalmaz — anlık dürtüyle kapatılabilen filtre filtre değildir. Sideload ettiğin için `adb uninstall` ile her zaman kaldırabilirsin; asıl sürtünme buradan geliyor, teknik kilitten değil.
- **Batarya.** Sadece izlenen uygulamalar öndeyken çalışır. Quantize edilmiş model + 1 fps + 1/4 çözünürlükte pratik etki düşük. GPU delegate destekleniyorsa otomatik kullanılır.

---

## FLAG_SECURE kör noktası

Gizli sekme ve `FLAG_SECURE` kullanan uygulamalarda MediaProjection **tamamen siyah kare** döndürür. Bu bir hata değil, Android'in tasarımı: Google imzasız uygulamaların "secure" virtual display oluşturmasını baştan engelliyor. Root olmadan piksel seviyesinde çözümü **yok**.

### Katmanlı yanıt

| Katman | FLAG_SECURE'dan etkilenir mi | Kapsam |
|---|---|---|
| Görsel sınıflandırma | **Evet** — kör | İçerik bazlı, database yok |
| Siyah kare tespiti | Hayır | "Göremiyorum" durumunu blok sinyali sayar |
| Erişilebilirlik / URL | **Hayır** | Alan adı bazlı, gizli sekmede de çalışır |
| Private DNS | **Hayır** | Sistem genelinde, tüm uygulamalar |

**Siyah kare tespiti** (`BlackFrameDetector.kt`): FLAG_SECURE karesi mükemmel siyahtır (tüm piksel 0). Gerçek koyu ekranda durum çubuğu, gezinme çubuğu, antialiasing yüzünden her zaman varyans olur. Ortalama parlaklık **ve** varyans birlikte kontrol edilerek ayırt ediliyor. 4 ardışık siyah kare → blok. Politika `SecurePolicy.BLOCK_ON_SECURE_BLACK` ile kapatılabilir.

**Erişilebilirlik katmanı** (`PerdeAccessibilityService.kt`): FLAG_SECURE render edilmiş yüzeyi korur, accessibility node tree'yi korumaz. Gizli sekmede ekran siyah gelirken adres çubuğu metni hâlâ okunabilir. Kurulum: Ayarlar > Erişilebilirlik > Perde.

Bu katman alan adı bazlı — yani istemediğin blocklist mantığı. Ama kör noktayı root'suz kapatmanın tek yolu bu. `Config.URL_KEYWORDS` bilinçli olarak kısa: kapsamlı liste tutma savaşını kaybedersin, alan adı sonsuz. Asıl iş görsel sınıflandırıcıda kalıyor.

### Private DNS (kod gerektirmez, en güçlü backstop)

Ayarlar > Ağ > Özel DNS > sağlayıcı adı gir. Filtreleyen bir DoT sağlayıcı kullanırsan gizli sekme dahil sistemin tamamı kapsanır — FLAG_SECURE'ın hiçbir etkisi yok, çünkü DNS piksel görmüyor.

Sınırı: alan adı bazlı, IP'ye doğrudan gitmek veya VPN kurmak atlatır. Ayarı değiştirmek 10 saniye sürer, teknik kilit değil.

### Root yolu (gerçek çözüm, gerçek bedel)

LSPosed üzerinde FLAG_SECURE'ı sistem genelinde devre dışı bırakan modüller var. Çalışır. Bedeli: bootloader açma (cihaz sıfırlanır), root, Play Integrity kırılması — bankacılık uygulamaların ve bazı oyunlar çalışmaz.

Bu takası tamamen çözemeyeceğin bir kör nokta için yapmaya değer mi, kendi kararın. Üç katmanın birlikte kapsamı zaten pratikte yeterli.

---

## Kapsam — neyi yakalar, neyi yakalamaz

Bu bölüm tahmin değil, derlenmiş kod üzerinde ölçüldü.

### Hassasiyet profilleri

| İçerik | DENGELI | SIKI | KATI |
|---|:---:|:---:|:---:|
| Açık içerik | BLOK | BLOK | BLOK |
| Hentai / çizim | BLOK | BLOK | BLOK |
| Güçlü müstehcen, giyimli | geçer | BLOK | BLOK |
| Instagram teşvik edici | geçer | geçer | BLOK |
| TikTok dans / vücut | geçer | geçer | BLOK |
| | | | |
| Plaj / tatil fotoğrafı | geçer | **BLOK** | **BLOK** |
| Spor salonu / fitness | geçer | geçer | **BLOK** |
| Moda / iç giyim reklamı | geçer | geçer | **BLOK** |

Kalın olanlar yanlış tetiklenme.

**Bu bir ayar sorunu değil, modelin sınırı.** MobileNetV2 "müstehcen ama giyimli" her şeyi tek bir sınıfa (`sexy`) atıyor. Plaj tatili fotoğrafıyla teşvik edici içerik o sınıfın içinde aynı skoru üretiyor — model niyeti göremiyor, sadece pikselleri görüyor.

Yani **"Instagram'ı yakala ama tatil fotoğrafımı bloklama" bu modelle mümkün değil.** Üçünden birini seçmen gerekiyor. Ayrımı istiyorsan tek yol kendi verinle fine-tune etmek, o da ayrı bir proje.

### Model hiç göremediği şeyler

- **Metin.** Erotik hikâye, sohbet, altyazı — sınıflandırıcı görsel çalışıyor, metni okumuyor
- **Ses.** Video sesi tamamen kapsam dışı
- **Yakalanamayan uygulamalar.** `WATCHED_PACKAGES` listesinde olmayan hiçbir uygulamada çalışmaz. Liste eksikse boşluk kalır
- **FLAG_SECURE ekranları.** Piksel görünmez; siyah kare tespiti "açıldığını" anlar, "ne olduğunu" değil

## Batarya

Üç optimizasyon katmanı:

| Katman | Kazanç |
|---|---|
| Uygulama kapısı | İzlenen uygulama önde değilse yakalama tamamen kapalı |
| **Kare farkı** (`FrameDiffer`) | Durağan ekranda inference ~%90 azalır |
| Uyarlanabilir aralık | 20 kare sakin geçerse 1 fps → 0.33 fps |

`FrameDiffer` en büyük kazanç. Gerçek kullanımda ekranın çoğu durağandır: metin okuyorsun, video duraklamış, uygulama açık ama etkileşim yok. O karelerde inference çalıştırmak saf israf, sonuç zaten aynı çıkacak. Kare 16x16 parmak izine indirilip öncekiyle karşılaştırılıyor, fark eşiğin altındaysa model hiç çalışmıyor.

`BlackFrameDetector` de düzeltildi: `getPixel()` piksel başına ayrı çağrı yapıyordu, `getPixels()` ile tek okumaya indirildi.

**Ölçülmedi.** Bu tahminler mimariden çıkarım. Gerçek rakam için kur ve `adb shell dumpsys batterystats` ile bak. Cihaz, model boyutu ve GPU desteği sonucu ciddi değiştirir.

## Blok ekranı mesajları

`Motivation.kt` — profil bazlı (İslam / Hristiyanlık / Yahudilik / Seküler / Kişisel).

**Tasarım notu:** ilk fikir "utandırıcı olsun" idi. Utanç temelli müdahale güvenilmez: utanç → sıkıntı → sıkıntıyı bastırmak için aynı davranışa dönüş. Mesajlar bu yüzden suçlayıcı değil, değer hatırlatıcı yazıldı. Fark ince, sonuç farkı büyük.

**Dini metin listeleri bilerek boş.** Kur'an ve İncil meallerinin *çevirileri* telif hakkına tabidir — orijinal metin kamu malı olsa da modern Türkçe meal çevirmenin ya da yayınevinin telifindedir. Dağıtacaksan lisansı temiz kaynak kullan ya da kendi cümlelerinle yaz. Seküler liste özgün, sorun yok.
