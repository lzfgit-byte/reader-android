plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.ghs.mobile.core.script"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    api(project(":core:engine"))
    implementation(project(":core:model"))

    // QuickJS（QuickJS 主通道，见 ADR-0003）；自带 arm64-v8a / armeabi-v7a / x86 / x86_64
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")
}
