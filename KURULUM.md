# Perde — Kurulum Rehberi

Android Studio gerekmiyor. APK GitHub'ın sunucusunda derleniyor, sen hazır dosyayı indiriyorsun.

**Toplam süre:** ilk turda ~40 dakika. Sonraki kalibrasyon turları ~10 dakika.

**Gereken:** bilgisayar, GitHub hesabı, Google hesabı (Colab için), Android telefon.

---

## Adım 0 — Neye giriştiğini bil

Başlamadan önce bunları oku. Kurduktan sonra öğrenmek can sıkıcı olur.

| Gerçek | Sonucu |
|---|---|
| Model metni ve sesi görmüyor | Erotik hikâye, sohbet, video sesi kapsam dışı |
| "Müstehcen ama giyimli" tek sınıf | Instagram'ı yakalamak istiyorsan tatil fotoğrafın da bloklanır |
| FLAG_SECURE piksel gizler | Gizli sekmede *ne* olduğu değil, sadece *açıldığı* anlaşılır |
| `adb uninstall` her zaman çalışır | 15 dk kilit sürtünme yaratır, engel değildir |
| Runtime hiç test edilmedi | İlk çalıştırmada bir şey patlayacak, `adb logcat` şart |

Asıl işi yapan şey teknik kilit değil, dürtüyle eylem arasına giren sürtünme. Bunu bilerek kur.

---

## Adım 1 — Modeli hazırla

**Model:** [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) — MIT lisanslı, MobileNetV2 224×224, 5 sınıf. Kod tam olarak bunu bekliyor.

> Kaggle'da arama. Kaggle NSFW içeriğe izin vermiyor, orada bu model yok.

1. [colab.research.google.com](https://colab.research.google.com) → yeni notebook
2. `tools/model_donustur.py` içindeki 5 hücreyi sırayla yapıştır ve çalıştır
3. `nsfw.tflite` indir (quantization sonrası ~5-7 MB)

Betik `1.2.0` sürümündeki **`mobilenet_v2_140_224.1.zip`** dosyasını çekiyor (100.6 MB, SavedModel). Bu sürümün tek varlığı bu — `.tar.gz` diye bir dosya yok, `.h5` de yok. Klasör adı arşivden arşive değiştiği için betik `saved_model.pb`'yi kendi buluyor, elle yol düzeltmen gerekmiyor.

### HÜCRE 4'Ü ATLAMA

Sınıf sırasını doğruluyor. Kod `[drawings, hentai, neutral, porn, sexy]` sırasını varsayıyor. Sıra farklıysa ağırlıklar yanlış sınıflara uygulanır, filtre saçmalar — ve sen bunu eşik problemi sanıp günlerce 10 dakikalık kalibrasyon turu döndürürsün.

Nötr bir görselde **index 2 (neutral)** en yüksek çıkmalı. Çıkmıyorsa dur, sırayı düzelt.

---

## Adım 2 — Projeyi hazırla

ZIP'i aç. `nsfw.tflite` dosyasını şuraya koy:

```
perde/app/src/main/assets/nsfw.tflite
```

`BURAYA_MODEL_KOY.txt` dosyasını sil.

### Gözden geçirilecek ayarlar

`app/src/main/java/com/berke/perde/Config.kt`:

| Ayar | Varsayılan | Ne zaman değiştirirsin |
|---|---|---|
| `monitorMode` | `BLACKLIST` | Bırak. Whitelist her zaman eksik kalır. |
| `EXCLUDED_PACKAGES` | sistem + banka + klavye | Kendi bankacılık/hassas uygulamalarını ekle |
| `DISABLE_DELAY_MS` | 15 dk | Düşürme. Düşürürsen uygulamanın anlamı kalmaz. |
| `Hassasiyet.aktif` | `DENGELI` | Uygulama içinden seçilir, koda dokunma |

`Motivation.kt` içindeki çeviriler **yetkili meal değil**, benim aktarımlarım. Dağıtacaksan konusunda yetkin birine kontrol ettir.

---

## Adım 3 — GitHub'a yükle

```bash
cd perde
git init
git add .
git commit -m "ilk"

# gh CLI varsa:
gh repo create perde --private --source=. --push

# yoksa: github.com'dan boş PRIVATE repo aç, sonra
git remote add origin https://github.com/KULLANICI/perde.git
git branch -M main
git push -u origin main
```

> Sürükle-bırak yapma, klasör yapısını bozar. `git` kullan.
>
> `nsfw.tflite` 100 MB'ı aşarsa Git LFS gerekir. Quantize edilmişse aşmaz.

---

## Adım 4 — Derlet

1. Repo → **Actions** sekmesi
2. İlk seferde çıkan "I understand my workflows, enable them" butonuna bas
3. Sol menü → **APK Derle** → sağda **Run workflow** → **Run workflow**
4. 5-8 dakika

**Kırmızı çarpı gelirse:** derlemeye tıkla → kırmızı adımı aç → hata mesajının **son 20 satırını** oku. Genelde eksik dosya ya da bozuk klasör yapısıdır.

---

## Adım 5 — APK'yı al

Biten çalışma → sayfa altında **Artifacts** → `perde-debug-apk` → indir → ZIP'ten `app-debug.apk` çıkar → telefona at.

---

## Adım 6 — Telefona kur

APK'ya dokun → "Bilinmeyen kaynaklara izin ver" → kur.

### İzinler — SIRAYLA, hepsi zorunlu

| # | İzin | Nerede | Atlanırsa |
|---|---|---|---|
| 1 | Diğer uygulamaların üzerinde göster | Uygulama içi buton | Blok ekranı hiç çıkmaz |
| 2 | Kullanım erişimi | Ayarlar > Özel erişim > Kullanım erişimi (**manuel**) | Öndeki uygulama bilinmez, hiç çalışmaz |
| 3 | Erişilebilirlik | Ayarlar > Erişilebilirlik > Perde | Gizli sekme kör noktası açık kalır |
| 4 | Ekran yakalama | "Başlat"a basınca sistem sorar | Görsel analiz çalışmaz |
| 5 | **Pil optimizasyonu kapalı** | Ayarlar > Pil > Perde > Kısıtlanmamış | **Servis birkaç saatte sessizce ölür** |

5. adım en sık atlanan ve en sinsi olanı — uygulama çalışıyor görünür, sonra durur ve haberin olmaz.

**Xiaomi / Samsung / Huawei / Oppo:** ek olarak "otomatik başlatma" iznini de açman gerekiyor. Bu üreticiler agresif görev sonlandırma yapıyor, yeri her markada farklı.

### Uygulama içi ayarlar

- **Dil** — Türkçe / English
- **Mesaj profili** — Müslüman / Hristiyan / Yahudi / Ateist-Diğer / Kendi mesajlarım
- **Orijinal metni göster** — Arapça/İbranice/Yunanca orijinal blok ekranında görünsün mü
- **Hassasiyet** — aşağıya bak

---

## Adım 7 — Hassasiyet seç

| | DENGELI | SIKI | KATI |
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

Kalın = yanlış tetiklenme. Bu tablo ölçüldü, tahmin değil.

**DENGELI ile başla.** Bir hafta kullan. Kaçırdığı şey seni rahatsız ediyorsa SIKI'ya çık. KATI günlük kullanımda gerçekten can sıkıcı — bilerek seç.

---

## Adım 8 — İlk çalıştırma ve hata ayıklama

`platform-tools` indir (Android Studio değil, ~10 MB CLI). Buna ihtiyacın **olacak**.

```bash
# telefonda: Ayarlar > Geliştirici seçenekleri > USB hata ayıklama
adb devices
adb logcat -s ScreenGuardService:* PerdeA11y:* DetectionEngine:* NsfwClassifier:* AndroidRuntime:E
```

### Sık çıkan hatalar

| Belirti | Muhtemel sebep |
|---|---|
| `Model yüklenemedi` | `nsfw.tflite` assets'te yok ya da `noCompress` çalışmamış |
| Hiç blok çıkmıyor | Sınıf sırası yanlış (Adım 1) veya kullanım erişimi verilmemiş |
| Blok ekranı çıkmıyor ama log "BLOK" diyor | Overlay izni yok |
| Servis bir süre sonra susuyor | Pil optimizasyonu / otomatik başlatma |
| Her şeyi blokluyor | KATI moddasın, ya da sınıf sırası ters |

---

## Adım 9 — Kalibrasyon

`ScreenGuardService.tick()` içine geçici log ekle:

```kotlin
Log.d("CAL", "raw=%.3f ema=%.3f pkg=%s".format(raw, decision.smoothedScore, pkg))
```

`adb logcat -s CAL` ile 1-2 gün normal kullanımda skorları izle. Günlük içerikte gördüğün en yüksek skor, seçtiğin profilin `soft` değerinin belirgin altında kalmalı.

Ayar değiştirmek = `Config.kt`'yi GitHub'da düzenle → Actions otomatik derler → yeni APK indir → üstüne kur. Her tur ~10 dakika.

**Tek seferde tek parametre değiştir.** Yoksa neyin işe yaradığını göremezsin.

---

## Adım 10 — Dağıtım (isteğe bağlı)

Play Store'a gitme. Erişilebilirlik onayı, "kaldırmayı engelleme" yasağı ve stalkerware taraması üç ayrı ret sebebi.

Bunun yerine:

- **GitHub Releases** + kendi siten
- **F-Droid** — organik trafik, SEO'dan bağımsız
- APK'yı **imzala**, SHA256'sını sitede yayınla (imzasız APK'yı kimse kurmaz)
- **Gizlilik politikası** yaz — veri çıkmasa da gerekli
- Ko-fi / GitHub Sponsors bağış linki

**Dağıtacaksan önce:** dini metinleri yetkin birine kontrol ettir, model lisansını README'de belirt (MIT, telif bildirimi zorunlu), kaldırılamaz bildirimi ve gizli mod olmamasını koru — bunlar uygulamayı stalkerware'den ayıran tasarım kararları.
