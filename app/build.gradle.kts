plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.rivio.coletor"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.rivio.coletor"
        minSdk = 26          // Health Connect exige Android 8+ (API 26)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-hackathon"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Health Connect (Jetpack). Se o Android Studio sugerir uma versão mais nova, pode aceitar.
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
