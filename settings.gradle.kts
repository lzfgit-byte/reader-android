pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "ghs-mobile"

// 纯 JVM 模块（引擎层不依赖 Android Framework，见 design.md §8.11）
include(":core:model")
include(":core:engine")

// Android 模块（:app / :feature:*）在 Android SDK 就绪后加入
