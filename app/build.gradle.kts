plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sofstos.sharecopy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sofstos.sharecopy"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")

    // SFTP-клиент для удалённых папок назначения
    implementation("com.hierynomus:sshj:0.38.0")
    // Даёт поддержку современных алгоритмов обмена ключами/шифрования (curve25519, ed25519 и т.п.),
    // которых может не быть во встроенном криптопровайдере Android — иначе соединение с
    // современными SSH-серверами (свежий OpenSSH) может не установиться.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
