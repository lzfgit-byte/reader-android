import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── 版本号：每次打包自增 ──────────────────────────────────────────────────
// `version.properties`（工程根目录）是唯一来源，内容是 `versionCode` 与 `versionName`。
// 只要这次构建**请求了打包任务**（assemble / bundle / package*），就在这里先自增再读：
// 同一个 commit 连续打两次包会得到两个不同的版本号，APK 里用的就是本次的值。
// 不请求打包的构建（单测、lint、IDE 同步）不动它，避免"随手同步一下就跳版本"。
val versionFile = rootProject.file("version.properties")

fun isPackagingRequest(): Boolean {
    val names = gradle.startParameter.taskNames
    return names.any { name ->
        val lower = name.lowercase()
        lower.contains("assemble") || lower.contains("bundle") || lower.contains("package")
    }
}

/** `0.1.0` → `0.1.1`；段数不足或末段不是数字时退化成追加 `.1` */
fun bumpPatch(name: String): String {
    val parts = name.split('.')
    val last = parts.lastOrNull()?.toIntOrNull() ?: return "$name.1"
    return (parts.dropLast(1) + (last + 1).toString()).joinToString(".")
}

val appVersion: Pair<Int, String> = run {
    val props = Properties()
    if (versionFile.exists()) {
        versionFile.inputStream().use { props.load(it) }
    }
    var code = props.getProperty("versionCode")?.toIntOrNull() ?: 1
    var name = props.getProperty("versionName")?.trim().orEmpty().ifEmpty { "0.1.0" }
    if (isPackagingRequest()) {
        code += 1
        name = bumpPatch(name)
        versionFile.writeText("versionCode=$code\nversionName=$name\n")
        logger.lifecycle("版本号自增 → $name ($code)")
    }
    code to name
}

android {
    namespace = "com.ghs.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghs.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersion.first
        versionName = appVersion.second
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:script"))
    implementation(project(":core:data"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标全集：收藏态需要实心/空心两种星标（core 里只有实心 Star）
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 矢量图标：配置里的 favicon 常见 svg（coil 核心不自带 svg 解码器）
    implementation("io.coil-kt:coil-svg:2.7.0")

    // 视频播放（HLS 需要单独引入）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // 内置 Web 管理服务（F23）：体积小、无需协程桥接
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    androidTestImplementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")
}

// 把 Web 控制台产物同步进 assets：构建时自动执行，避免手工拷贝导致版本漂移
val syncConsole by tasks.registering(Sync::class) {
    val consoleDist = rootProject.layout.projectDirectory.dir("web/console/dist")
    val target = layout.projectDirectory.dir("src/main/assets/webconsole")
    onlyIf { consoleDist.asFile.isDirectory }
    from(consoleDist)
    into(target)
}

tasks.named("preBuild") { dependsOn(syncConsole) }