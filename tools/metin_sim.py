#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ContentAnalyzer'in simulasyonu.

NEDEN: metin analizinin esigi (Hassasiyet.textSoft) tahminle secilemez.
Yanlis secilirse ya gizli sekme kacar ya da bankacilik/saglik icerigi
bloklanir. Burada gercek ekran metinlerinin ornekleri uzerinde
olculuyor.

SOZLUK BURADA TEKRARLANMIYOR: Lexicon.kt dogrudan ayristiriliyor, yani
simulasyon uygulamadan sapamaz. Skor formulu ContentAnalyzer.kt ile
birebir ayni tutulmali; degistirirsen iki dosyayi birlikte degistir.

Kullanim:  python tools/metin_sim.py
"""

import math
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEXICON = os.path.join(ROOT, "app/src/main/java/app/perde/Lexicon.kt")

# ---------------------------------------------------------------- sozluk

TERM_RE = re.compile(r'\b(t|p|ph|sub)\("((?:[^"\\]|\\.)*)",\s*([0-9.]+)f\)')
MODE = {"t": "TOKEN", "p": "PREFIX", "ph": "PHRASE", "sub": "SUB"}


def parse_lexicon():
    src = open(LEXICON, encoding="utf-8").read()
    banks = {}
    for name in ("STRONG", "MEDIUM", "SUPPORT", "SAFE"):
        m = re.search(r"val %s: List<Term> = listOf\(" % name, src)
        if not m:
            sys.exit("Lexicon.kt icinde %s bulunamadi" % name)
        start = m.end()
        depth = 1
        i = start
        while i < len(src) and depth > 0:
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
            i += 1
        body = src[start:i - 1]
        terms = [(MODE[k], key, float(w)) for k, key, w in TERM_RE.findall(body)]
        if not terms:
            sys.exit("%s bos ayristirildi" % name)
        banks[name] = terms
    return banks


# ------------------------------------------------------------ normalize

FOLD = {}
for src_chars, dst in [
    ("ıìíîïĩīĭį", "i"), ("şśŝš", "s"), ("ğĝġģ", "g"), ("çćĉċč", "c"),
    ("àáâãäåāăą", "a"), ("èéêëēĕėęě", "e"), ("òóôõöøōŏő", "o"),
    ("ùúûüũūŭůűų", "u"), ("ýÿŷ", "y"), ("ñńņň", "n"), ("źżž", "z"),
    ("ţťț", "t"), ("ďđ", "d"), ("ĺļľł", "l"), ("ŕŗř", "r"),
]:
    for c in src_chars:
        FOLD[c] = dst
FOLD["ß"] = "s"
FOLD["æ"] = "a"
FOLD["œ"] = "o"

LEET = {"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t"}
MIN_SPLIT_RUN = 4


def normalize(raw):
    out = []
    for ch in raw:
        c = ch.lower()
        c = FOLD.get(c, c)
        if len(c) == 1 and c.isalnum() and ord(c) < 128:
            out.append(c)
        else:
            out.append(" ")
    # leet: yalnizca iki harf arasindaki rakam
    for i, c in enumerate(out):
        if c in LEET and 0 < i < len(out) - 1:
            if out[i - 1].isalpha() and out[i + 1].isalpha():
                out[i] = LEET[c]
    return " ".join("".join(out).split())


def deobfuscate(norm):
    parts = norm.split(" ")
    res, run = [], []
    def flush():
        if not run:
            return
        if len(run) >= MIN_SPLIT_RUN:
            res.append("".join(run))
        else:
            res.extend(run)
        del run[:]
    for p in parts:
        if len(p) == 1 and p.isalpha():
            run.append(p)
        else:
            flush()
            if p:
                res.append(p)
    flush()
    return " ".join(res)


class Prepared(object):
    def __init__(self, raw):
        self.text = deobfuscate(normalize(raw or ""))
        self.tokens = [t for t in self.text.split(" ") if t]
        self.compact = "".join(self.tokens)

    @property
    def empty(self):
        return not self.tokens


# --------------------------------------------------------------- analiz

W_URL, W_TITLE, W_BODY = 1.35, 1.25, 1.0
MAX_TERM_WEIGHT = 0.97
SUPPORT_GATE = 0.8
CORROBORATION_SCALE = 1.5
HEADER_FLOOR = 0.85
HEADER_STRONG_MIN = 0.70
DENSITY_TARGET = 4.0
DENSITY_FLOOR = 0.45
DENSITY_CEIL = 1.15
DENSITY_IMMUNE = 20.0
SAFE_DENSITY_TARGET = 3.0
SAFE_MAX_DAMP = 0.55
DURATION_MIN = 5
STRUCTURAL_BONUS = 0.08

DURATION_RE = re.compile(r"\b\d{1,2}:\d{2}\b")


def match(bank, prep, factor, out):
    tokens = set(prep.tokens)
    for mode, key, w in bank:
        eff = min(w * factor, MAX_TERM_WEIGHT)
        hits = 0
        if mode == "TOKEN":
            hits = prep.tokens.count(key)
        elif mode == "PREFIX":
            hits = sum(1 for tk in prep.tokens if tk.startswith(key))
        elif mode == "PHRASE":
            if (" " + prep.text + " ").find(" " + key + " ") >= 0:
                hits = 1
        elif mode == "SUB":
            if key in prep.compact:
                hits = 1
        if hits:
            prev = out.get(key)
            if prev is None:
                out[key] = [eff, hits]
            else:
                prev[0] = max(prev[0], eff)
                prev[1] += hits
    return tokens


def noisy_or(hits):
    inv = 1.0
    for w, _ in hits.values():
        inv *= (1.0 - w)
    return 1.0 - inv


def combine(a, b):
    return 1.0 - (1.0 - a) * (1.0 - b)


def occurrences(hits):
    return sum(c for _, c in hits.values())


def analyze(banks, url, title, body):
    fields = [(Prepared(url), W_URL), (Prepared(title), W_TITLE), (Prepared(body), W_BODY)]
    strong, medium, support, safe = {}, {}, {}, {}
    for prep, factor in fields:
        if prep.empty:
            continue
        match(banks["STRONG"], prep, factor, strong)
        match(banks["MEDIUM"], prep, factor, medium)
        match(banks["SUPPORT"], prep, factor, support)
        match(banks["SAFE"], prep, factor, safe)

    strong_e = noisy_or(strong)
    medium_e = noisy_or(medium)
    medium_w = sum(w for w, _ in medium.values())
    medium_trust = 1.0 if strong else 0.75
    evidence = combine(strong_e, medium_e * medium_trust)

    gate = bool(strong) or medium_w >= SUPPORT_GATE
    if gate:
        evidence = combine(evidence, noisy_or(support))

    distinct = len(strong) + len(medium) + 0.4 * (len(support) if gate else 0)
    corr = 1.0 - math.exp(-distinct / CORROBORATION_SCALE)

    header = {}
    match(banks["STRONG"], fields[0][0], 1.0, header)
    match(banks["STRONG"], fields[1][0], 1.0, header)
    if any(w >= HEADER_STRONG_MIN for w, _ in header.values()):
        corr = max(corr, HEADER_FLOOR)

    occ = occurrences(strong) + occurrences(medium) + (occurrences(support) if gate else 0)
    total = max(1, sum(len(p.tokens) for p, _ in fields))
    density = 100.0 * occ / total
    dfactor = min(max(math.sqrt(density / DENSITY_TARGET), DENSITY_FLOOR), DENSITY_CEIL)
    if not strong:
        dfactor = min(dfactor, 1.0)

    safe_e = noisy_or(safe)
    safe_density = 100.0 * occurrences(safe) / total
    safe_strength = safe_e * min(max(math.sqrt(safe_density / SAFE_DENSITY_TARGET), 0.0), 1.0)
    damp = 1.0 if density >= DENSITY_IMMUNE else 1.0 - SAFE_MAX_DAMP * safe_strength

    durations = len(DURATION_RE.findall(body or ""))
    structural = STRUCTURAL_BONUS if (durations >= DURATION_MIN and evidence > 0.35) else 0.0

    score = min(max(evidence * corr * dfactor * damp + structural, 0.0), 1.0)
    detail = "s%d m%d d%d g%d yog%.1f tok%d" % (
        len(strong), len(medium), len(support) if gate else 0, len(safe), density, total)
    return score, detail


# -------------------------------------------------------------- ornekler
# Gercek ekranlarda GORUNEN metin. Sayfanin tamami degil: erisilebilirlik
# agaci da yalnizca ekrandaki dugumleri veriyor.

SAMPLES = [
    # ---- bloklanmasi gerekenler ----
    ("porno tube TR", True,
     "www.hd-izle7.com/turkce-altyazili",
     "Turkce Altyazili Porno Izle - HD Sikis Videolari",
     """Kategoriler Turk Porno Amator Olgun Liseli Uvey Anne Lezbiyen
     Altyazili Sikis 12:45 Guzel sarisin ile sicak sikis 24:10 Turbanli
     ifsa videolari 08:32 Amator ciftten seks videosu 15:20 Olgun kadin
     porno filmi izle 31:04 Uvey kardes sikisme 09:11 En cok izlenen
     porno videolari HD kalite ucretsiz izle bedava indir benzer videolar
     populer etiketler"""),

    ("porn tube EN", True,
     "https://freetube-hd.xyz/videos/hot",
     "Free Porn Videos - HD Sex Tube",
     """Categories Amateur Teen MILF Mature Lesbian Hardcore Anal Blowjob
     Related videos 12:45 Hot amateur couple sex video 08:20 Big tits milf
     hardcore 22:10 Teen blowjob and creampie 14:02 Live sex cam show
     Most viewed porn videos watch free HD download premium models
     pornstars tags trending"""),

    ("gizli sekme, sadece adres+baslik", True,
     "hd-izle7.com/turkce-altyazili-porno",
     "Turkce Altyazili Porno Izle",
     "Yer isaretleri Sekmeler Yeni sekme"),

    ("erotik hikaye sitesi (metin, goruntu yok)", True,
     "hikayeler-oku.net/oku/1421",
     "Yasak Iliski - Erotik Hikaye",
     """Erotik hikayeler kategorisi Yasak iliski hikayesi Onu yatak
     odasinda ciplak gordugumde Memeleri ve kalcalari Sikisme aninda
     bosalma Sonraki bolum Diger erotik hikayeler Ensest hikayeler
     Ofis hikayeleri okuyucu yorumlari begen paylas"""),

    ("obfuske edilmis arama", True,
     "google.com/search?q=p0rn+izle",
     "p0rn izle - Google Arama",
     """Tum Gorseller Videolar Haberler p o r n o izle hd sikis videolari
     ucretsiz porno izleme siteleri en iyi porno arsivi"""),

    # ---- bloklanmamasi gerekenler ----
    ("bankacilik", False,
     "", "Garanti BBVA",
     """Hesaplarim Vadesiz TL Hesabi Bakiye 12.450,75 TL Son Islemler
     Para Transferi Havale EFT FAST IBAN Kopyala Kredi Karti Ekstre
     Odenecek Tutar Son Odeme Tarihi Taksitli Islemler Basvurular
     Yatirim Doviz Altin Guvenlik Sifre Degistir Cikis"""),

    ("sifre yoneticisi", False,
     "", "Bitwarden",
     """Kasam Tum Ogeler Favoriler Girisler Kartlar Kimlikler Guvenli
     Notlar Parola Olustur Uzunluk Buyuk Harf Rakam Ozel Karakter
     Kopyalandi Kullanici Adi Parola Web Sitesi Dogrulama Kodu"""),

    ("cinsel saglik makalesi", False,
     "saglikhaber.com/cinsel-yolla-bulasan-hastaliklar",
     "Cinsel yolla bulasan hastaliklar: belirtiler ve tedavi",
     """Cinsel yolla bulasan hastaliklar dunya genelinde yayginligini
     koruyor. Uzmanlar korunmasiz cinsel iliskinin enfeksiyon riskini
     artirdigini soyluyor. Belirtiler arasinda kasinti akinti ve agri
     bulunuyor. Tani icin doktora basvurmak ve laboratuvar testi
     yaptirmak gerekiyor. Tedavi antibiyotik ile yapiliyor. Jinekolog
     ve uroloji uzmanlari duzenli muayene oneriyor. Hastalik erken
     teshis edildiginde tedavi basarisi yuksek. Klinik arastirma
     sonuclari hasta takibinin onemini gosteriyor."""),

    ("haber: tecavuz davasi", False,
     "haberler.com/gundem/dava-karari",
     "Mahkeme karari acikandi - Gundem Haberleri",
     """Mahkeme heyeti sanik hakkinda tecavuz sucundan hukum kurdu.
     Savci mutalaasinda cezanin artirilmasini istedi. Avukat karari
     temyize goturecegini soyledi. Dava dosyasindaki bilirkisi raporu
     karara esas alindi. Kanun maddesi geregi ceza indirimi
     uygulanmadi. Haber merkezi muhabirimiz duruşmayi takip etti.
     Ilgili haberler gundem son dakika"""),

    ("wikipedia: insan cinselligi", False,
     "tr.wikipedia.org/wiki/Insan_cinselligi",
     "Insan cinselligi - Vikipedi",
     """Insan cinselligi insanlarin cinsel duygu ve davranislarini
     inceleyen arastirma alanidir. Biyolojik olarak penis ve vajina
     ureme organlaridir. Cinsel iliski ureme ve haz amaci tasir.
     Tarih boyunca farkli kulturlerde cinsellik farkli bicimlerde ele
     alinmistir. Ayrica bakiniz Kaynakca Dis baglantilar Bu madde
     taslak niteligindedir Universite arastirmalari ve akademik
     makaleler konuyu ele alir. Saglik acisindan korunma onemlidir."""),

    ("whatsapp flort sohbeti", False,
     "", "Ayse",
     """Bugun cok seksi gorunuyorsun Tesekkurler sen de Aksam ne
     yapiyorsun Sinemaya gidelim mi Saat kacta Yedi gibi Tamam
     gorusuruz Iyi geceler"""),

    ("youtube ana sayfa", False,
     "", "YouTube",
     """Ana Sayfa Kesfet Abonelikler Kitaplik Gecmis Izlenme 1,2 Mn
     goruntuleme 12:45 Yeni video 08:20 Populer Muzik Oyun Haberler
     Canli Kategoriler HD 4K Izle Sonra Izle Begen Paylas Kaydet
     Onerilen videolar Trend"""),

    ("ic giyim alisverisi", False,
     "trendyol.com/ic-giyim",
     "Kadin Ic Giyim Modelleri ve Fiyatlari",
     """Ic Giyim Sutyen Kulot Takim Fiyat Indirim Sepete Ekle Favorilere
     Ekle Beden S M L Renk Siyah Beyaz Kargo Bedava Ucretsiz Iade
     Degerlendirme Yorumlar Urun Aciklamasi Marka Stok Siparis Ver
     Tanga Bikini Pijama"""),

    ("netflix", False,
     "", "Netflix",
     """Ana Sayfa Diziler Filmler Yeni ve Populer Listem Simdi Izle
     Fragman Bilgi Bolumler Benzer Icerikler 1. Sezon 8 Bolum
     Aksiyon Gerilim Romantik Komedi Belgesel Cocuklar"""),

    ("kod / github", False,
     "github.com/crucio4/perde-app",
     "perde: Ekran icerigi filtresi",
     """Code Issues Pull requests Actions Commit history README.md
     app src main java app perde ContentAnalyzer.kt class
     function import private val return null String build gradle
     kotlin android error debug server"""),

    ("instagram akisi", False,
     "", "Instagram",
     """Hikayeler Kesfet Reels Begeni Yorum Paylas Kaydet Takip Et
     Gonderi 1.234 begenme Yorumlari gor Sponsorlu Bugun cok guzel
     bir gun tatil plaj deniz gunes fotograf"""),

    ("sozluk maddesi", False,
     "sozluk.gov.tr/?q=cinsellik",
     "cinsellik - Guncel Turkce Sozluk",
     """cinsellik isim Cinsel olma durumu seksuellik Ornek cumle
     Kaynak Guncel Turkce Sozluk Turk Dil Kurumu Yazim kilavuzu
     Atasozleri Deyimler Terim sozlukleri Arama gecmisi"""),

    ("reddit r/all", False,
     "reddit.com/r/all",
     "reddit: the front page of the internet",
     """Popular All Home Posts Comments Share Save Report Upvote
     Downvote Awards Join Community r/AskReddit r/pics r/news
     r/funny Best Hot New Top Rising 4.2k comments 18h ago"""),

    # ---- sinir durumlar: yanlis pozitif riski ----
    ("haber: siyasi ifsa iddiasi", False,
     "haber7.com/politika/iddia",
     "Ses kaydi ifsa oldu - Politika",
     """Iddiaya gore ses kaydi ifsa oldu ve gundem oldu. Parti sozcusu
     aciklama yaparak iddiayi reddetti. Savci sorusturma baslatti.
     Mahkeme sureci devam ediyor. Gazete muhabirimizin haberine gore
     rapor hazirlandi. Ilgili haberler son dakika gundem"""),

    ("porno bagimliligi ile mucadele yazisi", False,
     "blog.example.org/porno-bagimliligindan-kurtulmak",
     "Porno bagimliligindan kurtulmak: ne ise yariyor",
     """Porno bagimliligi ile mucadelede en cok ise yarayan yontemler
     uzerine bir arastirma derlemesi. Doktor ve terapistlerin onerileri
     tedavi surecinde destek gruplarinin onemi. Universite arastirmasi
     davranis degisikliginin altmis gunde olustugunu gosteriyor.
     Kaynakca ve makale baglantilari asagida"""),

    ("biyoloji dersi: ureme sistemi", False,
     "dersnotlari.edu.tr/biyoloji/ureme-sistemi",
     "Ureme Sistemi - 11. Sinif Biyoloji Ders Notu",
     """Ureme sistemi konu anlatimi. Erkek ureme sisteminde testis ve
     penis bulunur. Disi ureme sisteminde yumurtalik ve vajina yer
     alir. Sperm hucresi ve yumurta hucresi birlesir. Ders notu sinav
     sorulari universite hazirlik testi cozumleri ogretmen anlatimi"""),

    ("netflix: Sex Education dizisi", False,
     "", "Netflix - Sex Education",
     """Sex Education 4. Sezon 8 Bolum Simdi Izle Fragman Bilgi
     Oyuncular Dizi hakkinda Benzer icerikler Listeme ekle Begen
     Bolumler Sezon 1 Sezon 2 Sezon 3 Komedi Dram Genclik"""),

    ("tinder", False,
     "", "Tinder",
     """Kesfet Eslesmeler Mesajlar Profil Begen Gec Super Begeni
     3 km uzakta 24 yasinda Istanbul Universite Ilgi alanlari
     Seyahat Muzik Kahve Yeni eslesmen var Sohbete basla"""),

    ("twitter: kufurlu tweet", False,
     "", "X",
     """Ana sayfa Kesfet Bildirimler Mesajlar Yer isaretleri Gonder
     ya bu trafik sinir bozucu amk Retweet Alinti Begen Goruntuleme
     1.2 B Trend konular Spor Gundem Teknoloji"""),

    ("kadin dogum randevu uygulamasi", False,
     "", "MHRS",
     """Randevu Al Hastane Sec Poliklinik Kadin Hastaliklari ve Dogum
     Jinekolog Doktor Sec Tarih Saat Randevularim Iptal Et TC Kimlik
     Dogrulama SMS Kodu Muayene Gecmisi Recete Tahlil Sonuclari"""),

    # ---- sinir durumlar: yanlis negatif riski (kacacak olanlar) ----
    ("porno sayfasi, Ispanyolca (sozlukte yok)", True,
     "videos-calientes.example/ver",
     "Videos porno gratis en espanol",
     """Categorias Maduras Jovencitas Amateur Espanol Latina
     Mas vistos 12:45 Chica caliente 08:20 Pareja amateur
     Ver gratis descargar HD relacionados etiquetas"""),

    ("tek video sayfasi, az metin", "bosluk",
     "vid7x.example/v/88214",
     "Uvey abla ile - 1080p",
     """1080p 12:45 Begen Paylas Kaydet Bildir Yorumlar Sonraki
     video Otomatik oynat Kalite Tam ekran"""),

    ("kelimesiz adres, kelimesiz sayfa", "bosluk",
     "bcv7z.example/v/8821", "", ""),
]


def main():
    banks = parse_lexicon()
    print("Sozluk: guclu=%d belirsiz=%d destek=%d ters=%d" % (
        len(banks["STRONG"]), len(banks["MEDIUM"]),
        len(banks["SUPPORT"]), len(banks["SAFE"])))
    print("")

    thresholds = [("DENGELI", 0.78), ("SIKI", 0.68), ("KATI", 0.60)]
    print("%-42s %6s  %-28s %s" % ("ornek", "skor", "ayrinti", "DENGELI/SIKI/KATI"))
    print("-" * 108)

    failures = 0
    worst_ok = 0.0      # bloklanmamasi gerekenlerin en yukseki
    best_block = 1.0    # bloklanmasi gerekenlerin en dususu

    for name, expect, url, title, body in SAMPLES:
        score, detail = analyze(banks, url, title, body)
        marks = ["BLOK" if score >= th else "gecer" for _, th in thresholds]

        if expect == "bosluk":
            flag = "   <-- bilinen bosluk" if score < 0.78 else "   <-- bosluk kapandi"
        else:
            ok = (score >= 0.78) == expect
            if not ok:
                failures += 1
            flag = "" if ok else "   <-- HATA"
            if expect:
                best_block = min(best_block, score)
            else:
                worst_ok = max(worst_ok, score)

        print("%-42s %6.3f  %-28s %s%s" % (
            name, score, detail, "/".join(marks), flag))

    print("-" * 108)
    print("bloklanmasi gerekenlerin en dususu  : %.3f" % best_block)
    print("bloklanmamasi gerekenlerin en yuksek: %.3f" % worst_ok)
    print("bosluk                              : %.3f" % (best_block - worst_ok))
    print("DENGELI esigi (0.78) hatasi         : %d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
