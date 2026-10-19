plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // 脚本运行时 spike：仅测试期使用（真机形态是 QuickJS，见 ADR-0003）
    // 23.1 起 GraalJS 的社区版坐标是 org.graalvm.polyglot:js-community
    testImplementation("org.graalvm.polyglot:polyglot:23.1.2")
    testImplementation("org.graalvm.polyglot:js-community:23.1.2")
    testImplementation("org.graalvm.truffle:truffle-runtime:23.1.2")
}

tasks.test {
    useJUnitPlatform()
    // GraalJS（Truffle）会把 native 库解压到 java.io.tmpdir，
    // 系统临时目录可能不可写（本机就踩到 AccessDenied），统一指到工作区内。
    val scratch = layout.buildDirectory.dir("java-tmp").get().asFile
    scratch.mkdirs()
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    // Truffle 还会把 native 库解压到 %LOCALAPPDATA%\org.graalvm.polyglot，一并重定向
    val localAppData = scratch.resolve("localappdata").apply { mkdirs() }
    val appData = scratch.resolve("appdata").apply { mkdirs() }
    environment("LOCALAPPDATA", localAppData.absolutePath)
    environment("APPDATA", appData.absolutePath)
    // 有些 Graal 版本读系统属性而非环境变量
    systemProperty("LOCALAPPDATA", localAppData.absolutePath)
    systemProperty("APPDATA", appData.absolutePath)
    systemProperty("user.home", scratch.resolve("home").apply { mkdirs() }.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dpolyglot.engine.WarnInterpreterOnly=false")
    testLogging {
        events("passed", "failed", "skipped")
    }
}
