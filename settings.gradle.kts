pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ghs-mobile"

// 纯 JVM 模块（引擎层不依赖 Android Framework，见 design.md §8.11）
include(":core:model")
include(":core:engine")
include(":core:script")

// Android 应用（壳层）
include(":app")
