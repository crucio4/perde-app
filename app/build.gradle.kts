plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.berke.perde"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.berke.perde"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
