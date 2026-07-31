plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.perde"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.perde"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    /**
     * Release imzalama.
     *
     * Anahtar bilgileri ortam degiskenlerinden okunuyor; repoda ne
     * keystore ne parola duruyor. CI bunlari GitHub secret'larindan
     * veriyor (bkz. .github/workflows/build.yml).
     *
     * NEDEN SABIT ANAHTAR SART: Android ayni paketi yalnizca ayni
     * imzayla gunceller. Debug derlemede anahtar her makinede/her CI
     * kosusunda yeniden uretiliyordu, yani yayinlanan her surum farkli
     * imzaliydi ve kullanici guncelleme kuramiyordu — uygulamayi
     * kaldirip butun ayarlarini, izinlerini ve 15 dakikalik gecikmesini
     * sifirlamak zorunda kaliyordu. Bir bagimlilik uygulamasinda bu,
     * korumanin tamamen kapali kaldigi bir pencere demek.
     *
     * Anahtar yoksa (yerel derleme, fork) release imzasiz uretilir;
     * debug derleme her zaman calisir.
     */
    val keystorePath: String? = System.getenv("PERDE_KEYSTORE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PERDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PERDE_KEY_ALIAS")
                keyPassword = System.getenv("PERDE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // .tflite dosyası sıkıştırılmamalı, yoksa memory-map edilemez
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // DIKKAT: bu surum, modeli uretirken kullandigin TensorFlow surumunden
    // ESKI olmamali. Colab guncel TF ile cevirdiginde model yeni op
    // surumleri iceriyor ve eski calisma zamani onlari tanimayip
    // "Didn't find op for builtin opcode" ile Interpreter kurulumunu
    // patlatiyor — model dosyasi saglam olsa bile.
    // 2.17.0, org.tensorflow:tensorflow-lite altindaki en yeni surum.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
}
