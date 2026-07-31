# Perde

Android için cihaz üzerinde çalışan ekran içeriği filtresi. Site listesi yok —
ekranı gerçek zamanlı analiz eder, eşik aşılınca tam ekran overlay bindirip
ana ekrana atar.

Ekranı iki ayrı yoldan okur: **pikseli** (görsel sınıflandırma) ve
**içeriği** (erişilebilirlik ağacındaki metin). Gizli sekme pikseli
kapatıyor, içeriği kapatmıyor — kör nokta oradan kapanıyor.

Hiçbir veri cihazdan çıkmaz. Tüm analiz lokal.

## Mimari — iki kanal

Ekranı iki ayrı yoldan okuyoruz, çünkü Android ikisini birden kapatmıyor.

```
ForegroundAppWatcher  ─ izlenen uygulama önde mi? (değilse her şey kapalı → batarya)
        ↓
   ┌────────────────────────────┬────────────────────────────┐
   │  KANAL 1 — PİKSEL          │  KANAL 2 — İÇERİK          │
   │  takeScreenshot()          │  erişilebilirlik ağacı     │
   │        ↓                   │        ↓                   │
   │  NsfwClassifier (TFLite)   │  ScreenReader (metin)      │
   │        ↓                   │        ↓                   │
   │  ImageEvidence (ten/renk)  │  ContentAnalyzer           │
   │                            │                            │
   │  FLAG_SECURE'da KÖR        │  FLAG_SECURE'dan ETKİSİZ   │
   └────────────────────────────┴────────────────────────────┘
        ↓                                    ↓
        └──────────► max(görsel, metin) ◄────┘
                            ↓
DetectionEngine       ─ EMA → pencere oylama → histerezis → soğuma
        ↓
OverlayManager        ─ SYSTEM_ALERT_WINDOW overlay + HOME intent
```

İki kanal da aynı ölçeğe eşlenip **aynı** karar motoruna giriyor. Yani gizli
sekmede devreye giren yol, normal kullanımdakiyle aynı yanlış-pozitif
katmanlarından geçiyor; ayrı kuralları, ayrı eşikleri yok.

## False positive katmanları

Tek karelik yüksek skor **asla** bloklamaz — HARD eşiği bile iki ardışık kare istiyor. Altı katman sırayla:

| Katman | Ne yapar | Neyi engeller |
|---|---|---|
| Sınıf ağırlığı | `sexy` sınıfı 0.35 ile çarpılır, `drawings` 0 | Plaj, spor, moda, tişörtsüz fotoğraf |
| **Görsel kanıt** (`ImageEvidence`) | Ten/renk/düzlük ölçer, model iddiasını pikselle doğrular | **Çöp adam, diyagram, çizgi çizim, metin ekranı** |
| EMA (α=0.45) | Skoru yumuşatır | Ani tek kare sıçraması |
| Pencere oylama | Son 8 karenin 5'i ≥0.68 olmalı | Kaydırırken denk gelen kare, thumbnail, reklam |
| Histerezis | Açılma 0.68, kapanma 0.40 | Bloğun açılıp kapanıp titremesi |
| Soğuma | Blok kalktıktan 4s sonra yeniden tetiklenebilir | Flapping |

### Çöp adam neden bloklamıştı

Model stil ile içeriği ayıramıyor: çizgi çizimleri `drawings` ile `hentai`
arasında bölüyor ve `hentai` ağırlığı 0.95 olduğu için basit bir çizim
0.94'ü aşıp **tek karede** blok tetikleyebiliyordu.

İki düzeltme:

1. `ImageEvidence` — çıplaklık ten gerektirir. Kare 4×6 karoya bölünüp en
   yoğun karonun ten oranı ölçülüyor; ten yoksa modelin iddiasını piksel
   desteklemiyor demektir ve skor kırpılıyor. Çizgi çizim (akromatik
   piksel oranı yüksek + renklilik düşük + ten yok) doğrudan sıfırlanıyor.
2. `HARD_FRAMES_REQUIRED = 2` — hızlı yol artık iki ardışık kare istiyor.

Bedeli, bilerek kabul edilen iki boşluk: siyah-beyaz fotoğraf (ten kroması
yok, kural devre dışı bırakılıyor) ve çizgisel/siyah-beyaz manga.

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
3. **Erişilebilirlik** — Ayarlar > Erişilebilirlik > Perde. **Atlanabilir değil.** İki iş birden yapıyor: ekran görüntüsünü izin sormadan alıyor ve içerik kanalını besliyor. Kapalıysa gizli sekme kör nokta olarak kalır ve koruma süreç ölümünden sonra geri gelmez
4. **Ekran yakalama** — yalnızca erişilebilirlik yolu çalışmıyorsa (API 30 altı) sorulur; her yeniden başlatmada tekrar sorar
5. **Pil optimizasyonu dışına al** — yoksa sistem servisi öldürür. Ayarlar > Pil > Kısıtlanmamış

### 4. Kalibrasyon

İlk kurulumdan sonra eşikleri kendi kullanımına göre ayarla. `ScreenGuardService.tick()` içine geçici log ekle:

```kotlin
Log.d("CAL", "raw=%.3f ema=%.3f pkg=%s".format(raw, decision.smoothedScore, pkg))
```

`adb logcat -s CAL` ile normal kullanımda 1-2 gün skorları izle. Günlük içerikte gördüğün en yüksek skor `SOFT_THRESHOLD`'un belirgin altında kalmalı. Kalmıyorsa:

- Yanlış tetikleniyor → `SOFT_THRESHOLD` +0.05, `WINDOW_HITS_REQUIRED` +1, `W_SEXY` -0.10
- Geç/hiç tetiklenmiyor → `SOFT_THRESHOLD` -0.05, `WINDOW_HITS_REQUIRED` -1

Tek seferde tek parametre değiştir, yoksa neyin işe yaradığını göremezsin.

### 5. İzlenen uygulama listesi — gerekmiyor

Varsayılan mod `BLACKLIST`: `Config.EXCLUDED_PACKAGES` dışındaki her
uygulama kapsanıyor. Liste bakımı yok, bilmediğin bir tarayıcı da kapsam
içinde. `WATCHED_PACKAGES` yalnızca `monitorMode` elle `WHITELIST`
yapılırsa kullanılıyor.

Dışlama listesine bir şey eklemek isteyebileceğin tek durum: yanlış
tetiklenmenin gerçekten zarar vereceği yerler (harita — araba
kullanıyorsundur, kamera — acil bir an olabilir).

---

## Kurulum duvarları — izinlerden önce

Gerçek cihazda ölçüldü (Xiaomi, Android 16 / HyperOS). Üçü de "uygulama
kurulu görünüyor ama hiçbir şey yapmıyor" tablosuna çıkıyor ve üçü de hata
değil. Kullanıcıya anlatılmazsa uygulamanın bozuk sanılmasının ana sebebi
bunlar.

**1. Play Protect kurulumu reddediyor** (`INSTALL_FAILED_VERIFICATION_FAILURE`).
adb, `pm install`, elle kurulum — hepsi aynı kapıya çıkıyor. Sebebi mantıklı:
ekran görüntüsü + overlay + erişilebilirlik + kapatma gecikmesi, Play
Protect'in izleme yazılımı imzasıyla birebir örtüşüyor.

Aradaki fark şu: izleme yazılımı gördüğünü bir yere **göndermek** zorundadır,
Perde'nin ise `INTERNET` izni hiç yok — soket açamaz. Bu ayrım hem doğru hem
de manifestten doğrulanabilir, kullanıcıya böyle anlatılmalı.

> Play Store › profil › Play Protect › ⚙ › taramayı kapat → kur → geri aç

**2. Android 13+ kısıtlanmış ayarlar.** Mağaza dışından kurulan uygulamada
erişilebilirlik anahtarı sistem tarafından kilitli geliyor. Açılmazsa metin
kanalı hiç çalışmaz; gizli sekme kapsanmaz ve tablo dışarıdan "tespit
etmiyor" gibi görünür.

> Ayarlar › Uygulamalar › Perde › ⋮ › Kısıtlanmış ayarlara izin ver

Bu duvara projenin kendi geliştiricisi iki kez takıldı ve ikisinde de kod
hatası sandı. Kullanıcıdan farkını anlamasını beklemek gerçekçi değil.

**3. Klon / ikili uygulama profilleri.** Üreticinin "dual app" ya da klon
profilinde (Xiaomi Second Space gibi) çalışan tarayıcı, ana profildeki Perde
tarafından ne okunabiliyor ne de görüntülenebiliyor. Çözümü yok; Android'in
uyguladığı bir sınır.

---

## Bilinmesi gerekenler

- **Bildirim gizlenemez.** Foreground service kalıcı bildirim zorunlu. IMPORTANCE_MIN ile en aza indirildi ama kaldırılamaz.
- **Ekran kayıt göstergesi.** Android 14+ MediaProjection aktifken durum çubuğunda gösterge çıkarabilir. Bu sistem davranışı, bypass edilemez.
- **Uygulamayı öldüremezsin.** `killBackgroundProcesses` üçüncü parti uygulamalara işlemiyor. Overlay + HOME intent pratikte aynı işi görüyor.
- **Kapatma gecikmesi.** `DISABLE_DELAY_MS` = 15 dk. Bunu düşürürsen uygulamanın anlamı kalmaz — anlık dürtüyle kapatılabilen filtre filtre değildir. Sideload ettiğin için `adb uninstall` ile her zaman kaldırabilirsin; asıl sürtünme buradan geliyor, teknik kilitten değil.
- **Batarya.** Sadece izlenen uygulamalar öndeyken çalışır. Quantize edilmiş model + 1 fps + 1/4 çözünürlükte pratik etki düşük. GPU delegate destekleniyorsa otomatik kullanılır.

---

## FLAG_SECURE / gizli sekme

Gizli sekmede ve `FLAG_SECURE` kullanan uygulamalarda ekranın **pikseli**
alınamaz: `takeScreenshot` `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` döner,
MediaProjection siyah kare verir ya da hiç kare vermez. Bu bir hata değil,
Android'in tasarımı ve root olmadan piksel seviyesinde çözümü **yok**.

**Ama piksel içerik değildir.**

FLAG_SECURE render edilmiş yüzeyi korur. Erişilebilirlik ağacı ayrı bir
yapıdır ve o bayraktan etkilenmez. Gizli sekmede ekran görüntüsü siyah
gelirken adres çubuğu, sayfa başlığı, başlıklar, bağlantı metinleri ve
görsel alt metinleri okunmaya devam eder. Yani gizli sekmede "hiçbir şey
göremiyoruz" doğru değil: **pikseli göremiyoruz, içeriği görüyoruz.**

Uygulamanın gizli sekme yanıtı bu: piksel yerine içeriği okumak ve kararı
okuduğu şeye göre vermek.

### Eski yaklaşım neden bırakıldı

Önceki sürüm "tarayıcıda ekranı göremiyorsam gizli sekmedir, blokla"
diyordu. Bu bir tahmindi ve tahmin olduğu için etrafına sürekli yama
gerekiyordu:

- FLAG_SECURE'u meşru kullanan uygulamaları ayırmak için tarayıcı listesi
- Reddit anonim mod / Telegram gizli sohbet için "önce okunabiliyordu,
  sonra gizledi" davranış kuralı
- O kuralın bankaların splash ekranında yanlış tetiklenmemesi için
  ısınma sayacı
- Ve hepsine rağmen Netflix oynatmaya basınca bloklanıyordu

Hepsi silindi. Yerine tek cümlelik bir kural geldi: **kanıt varsa blok
var, yoksa yok.** Bankacılık uygulaması da okunuyor; okunan şey bankacılık
olduğu için skoru sıfır çıkıyor ve bloklanmıyor. Banka isimlerini bilmeye
gerek yok, davranışını izlemeye de gerek yok.

### İçerik analizi (`ContentAnalyzer.kt`)

Bu bir anahtar kelime listesi kontrolü değil. Sözlükte **tek bir alan adı,
tek bir marka adı yok** — alan adı sonsuzdur, o savaş kaybedilir. Sözlükte
olan şey dil: bir sayfanın pornografik olduğunu söyleyen kelimeler. Sitenin
adı ne olursa olsun sayfanın metni aynı kelimelerden kuruluyor, o yüzden
hiç duyulmamış bir alan adı da yakalanıyor.

Beş katman:

| Katman | Ne yapar |
|---|---|
| **Normalizasyon** | `PORNO`, `pоrno` (Kiril o), `p0rn0`, `p o r n o`, `pornosu` → hepsi tek biçime iner. Türkçe karakter, aksan, Kiril/Yunan görsel ikizleri, leet, ayraçla parçalama |
| **Kanıt birleşimi** | Terimler dört ağırlık sınıfında (güçlü / belirsiz / destek / **ters**). Noisy-OR ile birleşir; hiçbir tek terim tek başına 1.0'a ulaşamaz |
| **Destek çarpanı** | Tek eşleşme karar veremez: 1 terim 0.49, 2 terim 0.74, 5 terim 0.96 katsayı alır. Sohbette geçen tek kelime bu yüzden bloklamıyor |
| **Yoğunluk** | 100 kelimede kaç eşleşme. Ölçüldü: haber yazısı ~2, ansiklopedi maddesi ~9, porno sayfası **23-79**. Tek başına en ayırt edici sinyal |
| **Ters kanıt** | Bankacılık, sağlık, eğitim, alışveriş, yazılım kelimeleri skoru aşağı çeker. Yoğunluk 20'yi aşınca devre dışı — porno sayfası "güvenli ödeme" yazarak bağışıklık kazanamasın |

Metin skoru, `Hassasiyet.textSoft` eşiği tam olarak görsel `soft` eşiğine
denk gelecek şekilde eşlenip aynı karar motoruna giriyor. Yani gizli
sekmede de aynı pencere oylaması, aynı histerezis, aynı soğuma işliyor.

### Ölçüm

`python tools/metin_sim.py` — sözlüğü `Lexicon.kt`'den doğrudan okur, yani
simülasyon uygulamadan sapamaz. 28 gerçekçi ekran metni örneği:

```
BLOKLANMASI GEREKENLER                          skor
  porno tube (TR)                              1.000
  porn tube (EN)                               1.000
  gizli sekme, sadece adres + başlık           0.965
  erotik hikaye sitesi (metin, görüntü yok)    1.000
  obfuske arama ("p0rn", "p o r n o")          1.000
  porno sayfası, İspanyolca (sözlükte yok)     0.958

BLOKLANMAMASI GEREKENLER                        skor
  bankacılık                                   0.000
  şifre yöneticisi                             0.000
  kadın doğum randevu uygulaması               0.000
  YouTube / Netflix / Instagram / Tinder       0.000
  küfürlü tweet                                0.000
  cinsel sağlık makalesi                       0.078
  haber: tecavüz davası                        0.049
  biyoloji dersi: üreme sistemi                0.297
  wikipedia: insan cinselliği                  0.318
  sözlük maddesi: cinsellik                    0.371
  porno bağımlılığıyla mücadele yazısı         0.453
  iç giyim alışverişi                          0.143

eşik (DENGELİ)                                 0.780
en düşük doğru pozitif                         0.958
en yüksek doğru negatif                        0.453
boşluk                                         0.506
```

DENGELİ eşiğinde hata yok. SIKI (0.68) ve KATI (0.60) eşiklerinde de yok —
aradaki boşluk yarım puandan geniş.

### Bilinen boşluklar

Ölçülen, kabul edilen, gizlenmeyen:

| Boşluk | Neden | Ne kadar önemli |
|---|---|---|
| Metinsiz sayfa | Tek video sayfası, örtmeceli başlık ("Üvey abla ile - 1080p"), kelimesiz alan adı | O sayfaya bir liste sayfasından gelinir; liste sayfası zaten bloklanır. Gizli sekmede doğrudan adres girilirse kaçar |
| Latin dışı alfabe | Sözlük Latin; Kiril/Arapça/CJK metin normalizasyonda düşüyor | Rusça/Arapça porno sayfası kaçar. İspanyolca/Almanca/Fransızca "porno" ortak kelime olduğu için yakalanıyor |
| Erişilebilirlik kapalı | İçerik kanalı tamamen bu servise bağlı | Servis kapalıysa gizli sekme kör nokta olarak kalır. Tanı ekranında `erisilebilir KAPALI` yazar |
| Salt görsel içerik | Sayfada gerçekten hiç metin yoksa | Nadir; sayfa başlığı bile genelde bir şey söyler |

Son çare olarak **"hiçbir şey okunamazsa engelle"** ayarı var (varsayılan
kapalı, yalnızca tarayıcılarda, 8 tick kesintisiz sessizlikten sonra).
DRM'li video oynatıcılar da bu duruma düştüğü için kapalı geliyor.

### Gizlilik

Okunan metin yalnızca bellekte tutuluyor, bir sonraki okumada üzerine
yazılıyor. Diske yazılmıyor, loglanmıyor, cihazdan çıkmıyor. Tanı ekranı
metni değil yalnızca sayıları gösteriyor (`s2 m1 d5 g0 yog12.4 tok80`).
Koruma kapalıyken hiç okuma yapılmıyor.

Erişilebilirlik servisi ekrandaki her metni okuyabilir — bu güçlü bir
yetki. Sideload edilmiş, kaynağı elinde olan bir uygulamada veriyorsun;
Play Store'dan kurulan bir uygulamaya vermeden önce iki kere düşün.

### Private DNS (kod gerektirmez, ek kemer)

Ayarlar > Ağ > Özel DNS > sağlayıcı adı gir. Filtreleyen bir DoT sağlayıcı
kullanırsan gizli sekme dahil sistemin tamamı kapsanır — DNS piksel
görmüyor, FLAG_SECURE'ın etkisi yok.

Sınırı: alan adı bazlı, IP'ye doğrudan gitmek veya VPN kurmak atlatır.
Ayarı değiştirmek 10 saniye sürer, teknik kilit değil.

### Root yolu (artık gerekli değil)

LSPosed üzerinde FLAG_SECURE'ı sistem genelinde devre dışı bırakan modüller
var; pikseli gerçekten geri getiriyorlar. Bedeli: bootloader açma (cihaz
sıfırlanır), root, Play Integrity kırılması — bankacılık uygulamaların ve
bazı oyunlar çalışmaz.

İçerik kanalı geldikten sonra bu takasın karşılığı kalmadı: root'un
getireceği tek ek şey metinsiz sayfaların pikseli.

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

### Görsel modelin göremedikleri — ve ikinci kanalın kapattıkları

| Görsel modelin körlüğü | İçerik kanalı ne yapıyor |
|---|---|
| **Metin.** Erotik hikâye, sohbet, altyazı — sınıflandırıcı piksel görüyor, okumuyor | **Kapatıyor.** Metin zaten onun tek girdisi. Ölçümde erotik hikâye sitesi 1.000 |
| **FLAG_SECURE ekranları.** Piksel yok | **Kapatıyor.** Erişilebilirlik ağacı o bayraktan etkilenmiyor |
| **Ses.** Video sesi | Kapsam dışı — ikisinde de |
| **Metinsiz görsel sayfa** | Kapatamıyor; görsel kanal gerekiyor. İkisi de yoksa (gizli sekme + metinsiz sayfa) kaçıyor |

`WATCHED_PACKAGES` artık varsayılan olarak devrede değil: mod `BLACKLIST`,
yani dışlananlar hariç her uygulama kapsanıyor. Liste bakımı gerekmiyor.

## Batarya

Üç optimizasyon katmanı:

| Katman | Kazanç |
|---|---|
| Uygulama kapısı | İzlenen uygulama önde değilse yakalama tamamen kapalı |
| Ekran kapalıyken durma | `PowerManager.isInteractive` false ise hiçbir şey yapılmıyor |
| **Kare farkı** (`FrameDiffer`) | Durağan ekranda inference ~%90 azalır |
| Uyarlanabilir aralık | 20 kare sakin geçerse 1 fps → 0.33 fps |

İçerik kanalının maliyeti ayrı: erişilebilirlik ağacı yürüyüşü uygulama
sınırını aşan IPC demek. Üç bütçe birden sınırlıyor — en fazla 1400 düğüm,
45 derinlik, 90 ms — ve iki okuma arasında en az 500 ms bekleniyor.
Olay yağmuru (kaydırma sırasında saniyede onlarca olay) aynı kısıtlayıcıya
girdiği için maliyet doğurmuyor.

`FrameDiffer` en büyük kazanç. Gerçek kullanımda ekranın çoğu durağandır: metin okuyorsun, video duraklamış, uygulama açık ama etkileşim yok. O karelerde inference çalıştırmak saf israf, sonuç zaten aynı çıkacak. Kare 16x16 parmak izine indirilip öncekiyle karşılaştırılıyor, fark eşiğin altındaysa model hiç çalışmıyor.

`BlackFrameDetector` de düzeltildi: `getPixel()` piksel başına ayrı çağrı yapıyordu, `getPixels()` ile tek okumaya indirildi.

**Ölçülmedi.** Bu tahminler mimariden çıkarım. Gerçek rakam için kur ve `adb shell dumpsys batterystats` ile bak. Cihaz, model boyutu ve GPU desteği sonucu ciddi değiştirir.

## Blok ekranı mesajları

`Motivation.kt` — profil bazlı (İslam / Hristiyanlık / Yahudilik / Seküler / Kişisel).

**Tasarım notu:** ilk fikir "utandırıcı olsun" idi. Utanç temelli müdahale güvenilmez: utanç → sıkıntı → sıkıntıyı bastırmak için aynı davranışa dönüş. Mesajlar bu yüzden suçlayıcı değil, değer hatırlatıcı yazıldı. Fark ince, sonuç farkı büyük.

**Telif konusu çözüldü, listeler dolu.** Kur'an ve İncil meallerinin *çevirileri* telif hakkına tabidir — orijinal metin kamu malı olsa da modern Türkçe meal çevirmenin ya da yayınevinin telifindedir. Bu yüzden uygulamadaki Türkçe ve İngilizce metinler mevcut hiçbir mealden alınmadı, kamu malı orijinallerden (Arapça Kur'an, İbranice Tanah, Yunanca Yeni Ahit) yeniden yazıldı. Orijinal metin de gösterilebiliyor ve sure/bölüm:ayet referansı ekranda duruyor ki kullanıcı kendi tercih ettiği mealle karşılaştırabilsin.

**Bunlar yetkili meal değildir** ve uygulama içinde de böyle belirtiliyor. Yayınlamadan önce her metni konusunda yetkin birine kontrol ettir; dini metni yanlış aktarmak teknik bir hatadan çok daha ciddi bir güven kaybıdır.

## Destek

[github.com/sponsors/crucio4](https://github.com/sponsors/crucio4)

Perde ücretsiz ve reklamsız; öyle kalacak. Reklam SDK'sı her istekte cihaz
kimliği ve IP gönderir — ekrandaki her metni okuyabilen bir uygulamada bu,
verilen tek sözü bozmak olur. Uygulama hiçbir ağa bağlanmıyor; içindeki
"Katkıda bulun" butonu yalnızca sen dokununca tarayıcıda bu adresi açıyor.

## Lisans

[GPL-3.0](LICENSE).

Bu tercih bilinçli. Perde erişilebilirlik servisiyle ekrandaki her metni okuyabiliyor ve tek satışı "hiçbir veri cihazdan çıkmaz" iddiası. Copyleft, birinin kodu alıp kapalı kaynak bir türev üretmesini — içine reklam SDK'sı, telemetri ya da sunucuya gönderim koyup aynı isimle dağıtmasını — engelliyor. Türev üretmek serbest, ama kaynağı açmak zorunda; yani iddia doğrulanabilir kalıyor.
