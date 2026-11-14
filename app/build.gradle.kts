plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lunacode.photonnews"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lunacode.photonnews"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        resources {
            excludes += setOf(
                "com/sun/jna/aix-ppc/**",
                "com/sun/jna/aix-ppc64/**",
                "com/sun/jna/darwin-aarch64/**",
                "com/sun/jna/darwin-x86-64/**",
                "com/sun/jna/win32-aarch64/**",
                "com/sun/jna/win32-x86-64/**",
                "com/sun/jna/win32-x86/**",
                "native/lib/osx-aarch64/**",
                "native/lib/win-x86_64/**"
            )
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    implementation("ai.djl.android:tokenizer-native:0.33.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
