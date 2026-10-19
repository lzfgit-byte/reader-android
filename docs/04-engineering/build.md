> **状态**：draft
> **版本**：v1.1
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.0

# 本地构建与验证

本文件记录**如何在当前机器上跑通构建与测试**，以及踩过的环境坑。目标是：换一台机器照此能复现。

---

## 1. 当前工具链形态

本机最初**没有 JDK / Gradle / Android SDK**。为了不依赖系统安装、也不需要管理员权限，采用**工作区内的便携工具链**：

```
.toolchain/                ← 已被 .gitignore 忽略，不入库
├── jdk/                   Temurin 21（解压即用）
├── gradle/                Gradle 8.9（解压即用）
├── android-sdk/           Android SDK（cmdline-tools + platforms + build-tools）
├── android-home/          ANDROID_USER_HOME（sdkmanager 的配置与缓存）
├── gradle-home/           GRADLE_USER_HOME（缓存、依赖）
├── tmp/                   TMP/TEMP
└── dl/                    下载的压缩包（可删）
```

| 组件 | 版本 | 来源 |
|---|---|---|
| JDK | Temurin 21.0.12.1+LTS | `api.adoptium.net` 的 windows-x64 zip |
| Gradle | 8.9 | `services.gradle.org` 的 `gradle-8.9-bin.zip` |
| Android SDK | cmdline-tools 12.0 | `dl.google.com` 的 `commandlinetools-win-*_latest.zip` |
| 已装 SDK 组件 | `platform-tools` 37.0.1 / `platforms;android-34` / `build-tools;34.0.0` | `sdkmanager` |
| Android NDK | **尚未安装** | QuickJS 落地时再装 |

> `local.properties` 里的 `sdk.dir` 指向 `.toolchain/android-sdk`，该文件已被 `.gitignore` 忽略。
> 换机器时重建工具链后需要重写 `local.properties`。

---

## 2. 跑测试

```powershell
$root = 'D:\projects\ghs-mobile-android'
$tc   = "$root\.toolchain"

$env:JAVA_HOME        = "$tc\jdk"
$env:GRADLE_USER_HOME = "$tc\gradle-home"   # 关键：不要用默认的 %USERPROFILE%\.gradle
$env:TMP = "$tc\tmp"; $env:TEMP = "$tc\tmp"
$env:PATH = "$tc\jdk\bin;$tc\gradle\bin;$env:PATH"

& "$tc\gradle\bin\gradle.bat" -p $root --console=plain --no-daemon test
```

Web 端的类型定义一致性检查（零依赖，不需要上面这套工具链）：

```powershell
cd web\console; node scripts/check-dts.mjs
```

## 2.1 构建 APK

在上面的环境变量之外，追加 SDK 相关变量：

```powershell
$env:ANDROID_HOME      = "$tc\android-sdk"
$env:ANDROID_SDK_ROOT  = "$tc\android-sdk"
$env:ANDROID_USER_HOME = "$tc\android-home"   # 让 sdkmanager 不写 %USERPROFILE%\.android

& "$tc\gradle\bin\gradle.bat" -p $root --console=plain --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 9MB，`minSdk 26` / `targetSdk 34`）。

首次构建会从 Google Maven 拉 AGP 与 Compose 依赖，约 7 分钟；之后增量构建在十几秒量级。

---

## 3. 踩过的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `Failed to load native library 'native-platform.dll'` + "系统找不到指定的路径" | `%USERPROFILE%\.gradle` 不存在，Gradle 首次解压本地库失败 | 显式设置 `GRADLE_USER_HOME` 到工作区内（上面第 2 节） |
| 下载的 zip 只有 150MB 却打不开 | `curl.exe` 经 PowerShell 管道（`\| Select-Object`）被缓冲，进程被中断后留下截断文件 | 用 `Invoke-WebRequest -OutFile`；**下载后先用 `ZipFile.OpenRead` 校验条目数再解压** |
| `npm.ps1` / `pnpm.ps1` 无法运行 | 系统执行策略禁止脚本 | 用 `cmd /c npm …` 或直接 `node <cli>.js` |
| `AccessDeniedException: …\AppData\Local\org.graalvm.polyglot` | GraalJS 的 Truffle 会把 native 库解压到 `%LOCALAPPDATA%`，受限环境不可写 | 在 `tasks.test` 里重定向 `java.io.tmpdir` / `LOCALAPPDATA` / `APPDATA` / **`user.home`**（Truffle 从 `user.home` 推导，只改环境变量无效） |

---

## 4. 验证基线（当前）

| 目标 | 结果 |
|---|---|
| `:core:model` 单测 | 9 通过 |
| `:core:engine` 单测 | 57 通过（选择器 16 / 文本解析 13 / 配置预检 14 / 归一化 7 / 脚本运行时 spike 7） |
| `:app` 调试包 | `:app:assembleDebug` 成功，`app-debug.apk` 9.2MB（`com.ghs.mobile` 0.1.0，minSdk 26 / targetSdk 34），启动页为"引擎探针" |
| `web/console` 的 d.ts 一致性 | 8 项全绿，并通过负向测试（删必需方法即失败） |

> `:app` 的"引擎探针"页直接显示 `PlainTextToBlocks` 与 `InlineConfigScanner` 的真实输出，
> 用来证明 `:core:model` / `:core:engine` 能在 Android 运行时被调用——不是演示界面。

> 脚本运行时 spike 的结论见 [spike-script-runtime.md](spike-script-runtime.md)：5 份真实配置在 JVM 侧 GraalJS 上
> 全部可求值、7 个必需方法齐全、`this` 与句柄式 `$` 桥接都成立。

`InlineConfigScanner` 的两个真实样本测试会读取 `tmp/configs/*.js`（不入库）：

- 本地有样本 → 跑真实预检，并打印每个样本的 key / 方法数 / 缺失 / 警告
- 本地没有 → 自动 `assumeTrue` 跳过，不污染干净克隆

> 该预检曾直接暴露一处**规范缺陷**：`key` 正则原先只允许小写，导致 3 份真实配置被判非法；
> 已按"实现回流文档"放宽为 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`（见 `config-spec` §9.2 第 32 条）。

---

## 5. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立构建说明：便携工具链形态、环境变量、踩坑与当前验证基线 |
| v1.1 | 2026-10-19 | 补 Android SDK（cmdline-tools 12.0 + android-34 + build-tools 34）、`sdkmanager` 免交互许可证、APK 构建步骤与产物基线 |
