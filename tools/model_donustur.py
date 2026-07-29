"""
model_donustur.py — GantMan/nsfw_model -> TFLite
================================================

Google Colab'da çalıştır: https://colab.research.google.com
Yeni notebook -> her bölümü ayrı hücreye yapıştır -> sırayla çalıştır.

Kaynak : https://github.com/GantMan/nsfw_model  (MIT lisanslı)
Model  : MobileNetV2, 224x224, 5 sınıf
Çıktı  : nsfw.tflite  -> app/src/main/assets/ klasörüne koy

Doğrulandı (29.07.2026): 1.2.0 sürümünün tek varlığı
mobilenet_v2_140_224.1.zip (100.6 MB). Bu bir SavedModel arşivi.

NOT: Kaggle'da bu model YOK. Kaggle NSFW içeriğe izin vermiyor, orada arama.
"""

# ===============================================================
# HÜCRE 1 — Modeli indir ve aç
# ===============================================================
"""
# Colab'da TensorFlow zaten kurulu, pip install gerekmiyor.
# Dosya .zip formatında — .tar.gz diye bir varlık YOK, tar ile açmaya çalışma.

!wget -q --show-progress https://github.com/GantMan/nsfw_model/releases/download/1.2.0/mobilenet_v2_140_224.1.zip
!unzip -q -o mobilenet_v2_140_224.1.zip
!find . -name "saved_model.pb"
"""

# ===============================================================
# HÜCRE 2 — Yükle ve dönüştür
# ===============================================================
import os
import tensorflow as tf

# Arşivden çıkan klasör adı sürümler arasında değişiyor
# ("mobilenet_v2_140_224" / "mobilenet_v2_140_224.1" / iç içe klasör).
# Elle düzeltmek yerine saved_model.pb'yi arayıp bulduruyoruz.
MODEL_DIR = None
for kok, _, dosyalar in os.walk("."):
    if "saved_model.pb" in dosyalar:
        MODEL_DIR = kok
        break

assert MODEL_DIR, "saved_model.pb bulunamadi — HUCRE 1 basarisiz olmus, ciktisina bak"
print("SavedModel:", MODEL_DIR)

converter = tf.lite.TFLiteConverter.from_saved_model(MODEL_DIR)

# Dynamic-range quantization: model ~4x küçülür, inference belirgin hızlanır,
# doğruluk kaybı ihmal edilebilir. Telefonda çalışacağı için şart.
converter.optimizations = [tf.lite.Optimize.DEFAULT]

# TF Hub katmanları bazen ek op setine ihtiyaç duyar. Dönüşüm hata verirse aç:
# converter.target_spec.supported_ops = [
#     tf.lite.OpsSet.TFLITE_BUILTINS,
#     tf.lite.OpsSet.SELECT_TF_OPS,
# ]

tflite_model = converter.convert()
open("nsfw.tflite", "wb").write(tflite_model)
print(f"Boyut: {len(tflite_model) / 1024 / 1024:.2f} MB")   # beklenen ~5-7 MB


# ===============================================================
# HÜCRE 3 — Şekil doğrulama
# ===============================================================
try:
    Interpreter = tf.lite.Interpreter
except AttributeError:                      # TF 2.20+ tf.lite.Interpreter'i kaldirdi
    from ai_edge_litert.interpreter import Interpreter

interp = Interpreter(model_path="nsfw.tflite")
interp.allocate_tensors()

inp = interp.get_input_details()[0]
out = interp.get_output_details()[0]

print("Giriş:", inp["shape"], inp["dtype"])   # beklenen [1 224 224 3] float32
print("Çıkış:", out["shape"], out["dtype"])   # beklenen [1 5]        float32

assert inp["shape"][1] == 224, "Config.INPUT_SIZE'i guncelle"
assert out["shape"][1] == 5,   "5 sinif bekleniyordu, weighScore()'u guncelle"


# ===============================================================
# HÜCRE 4 — SINIF SIRASI DOĞRULAMA   *** ATLAMA ***
# ===============================================================
#
# Kodum çıktı sırasını [drawings, hentai, neutral, porn, sexy] varsayıyor.
# Bu sıra Keras'ın alfabetik sınıf indeksleme davranışından geliyor ve
# GantMan'in predict.py dosyasıyla doğrulandı — ama sen de teyit et.
#
# Sıra yanlışsa ağırlıklar yanlış sınıflara uygulanır: filtre ya hiç
# tetiklenmez ya rastgele tetiklenir. Sen de bunu eşik problemi sanıp
# günlerce kalibrasyon turu döndürürsün. En pahalı hata bu.
#
# Test görseli: TensorFlow'un resmi örneği (üniformalı portre, tamamen
# giyimli). Wikimedia linkleri kullanma — thumb URL'leri ölüyor.

import numpy as np
from PIL import Image
import urllib.request

urllib.request.urlretrieve(
    "https://storage.googleapis.com/download.tensorflow.org/example_images/grace_hopper.jpg",
    "test.jpg"
)

img = Image.open("test.jpg").convert("RGB").resize((224, 224))
arr = (np.asarray(img, dtype=np.float32) / 255.0)[None, ...]

interp.set_tensor(inp["index"], arr)
interp.invoke()
probs = interp.get_tensor(out["index"])[0]

etiketler = ["drawings", "hentai", "neutral", "porn", "sexy"]
for e, p in zip(etiketler, probs):
    print(f"{e:10s} {p:.4f}")

print("\nEn yuksek:", etiketler[int(np.argmax(probs))])
print(">>> 'neutral' cikmadiysa sira farkli. GantMan repo'sundaki")
print(">>> predict.py dosyasindan gercek sirayi bul, sonra guncelle:")
print(">>>   DetectionEngine.weighScore()  — agirlik sirasi")
print(">>>   Config.W_* sabitleri")


# ===============================================================
# HÜCRE 5 — İndir
# ===============================================================
"""
from google.colab import files
files.download("nsfw.tflite")
"""


# ===============================================================
# LİSANS NOTU — ticari kullanım düşünüyorsan
# ===============================================================
#
# GantMan/nsfw_model MIT lisanslı: kullanım, değiştirme, satma serbest.
# Tek şart telif bildirimini dağıtımına dahil etmek.
#
# ANCAK lisans dosyası şu ibareyle başlıyor:
#   "This project contains third-party copyrighted material under
#    different licenses."
#
# Asıl gri alan lisans metni değil, eğitim verisinin kaynağı. Model
# kazınmış (scraped) görsellerle eğitilmiş ve o verinin dağıtım hakları
# belirsiz. Kişisel kullanımda pratik bir sorun yok. Ticarileştirmeden
# önce bir avukata danış — ben soruyu netleştirmene yardım edebilirim
# ama hukuki tavsiye veremem.
