> **状态**：draft
> **版本**：v1.0
> **最后更新**：2026-10-19
> **取代/被取代**：无

# 本地构建与验证

本文件记录**如何在当前机器上跑通构建与测试**，以及踩过的环境坑。目标是：换一台机器照此能复现。

---

## 1. 当前工具链形态

本机最初**没有 JDK / Gradle / Android SDK**。为了不依赖系统安装、也不需要管理员权限，采用**工作区内的便携工具链**：

```
.toolchain/                ← 已被 .gitignore 忽略，不入库
├── jdk/                   Temurin 21（解压即用）
├── gradle/                Gradle 8.9（解压即用）
├── gradle-home/           GRADLE_USER_HOME（缓存、依赖）
├── tmp/                   TMP/TEMP
└── dl/                    下载的压缩包（可删）
```

| 组件 | 版本 | 来源 |
|---|---|---|
| JDK | Temurin 21.0.12.1+LTS | `api.adoptium.net` 的 windows-x64 zip |
| Gradle | 8.9 | `services.gradle.org` 的 `gradle-8.9-bin.zip` |
| Android SDK | **尚未安装** | 需要时再拉 `commandlinetools` + `platforms` / `build-tools` |

> 这也意味着：**现在只能构建 JVM 模块**（`:core:model`、`:core:engine`）；`:app` 等 Android 模块要等 Android SDK 就位后加入 `settings.gradle.kts`。

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

---

## 3. 踩过的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `Failed to load native library 'native-platform.dll'` + "系统找不到指定的路径" | `%USERPROFILE%\.gradle` 不存在，Gradle 首次解压本地库失败 | 显式设置 `GRADLE_USER_HOME` 到工作区内（上面第 2 节） |
| 下载的 zip 只有 150MB 却打不开 | `curl.exe` 经 PowerShell 管道（`\| Select-Object`）被缓冲，进程被中断后留下截断文件 | 用 `Invoke-WebRequest -OutFile`；**下载后先用 `ZipFile.OpenRead` 校验条目数再解压** |
| `npm.ps1` / `pnpm.ps1` 无法运行 | 系统执行策略禁止脚本 | 用 `cmd /c npm …` 或直接 `node <cli>.js` |

---

## 4. 验证基线（当前）

| 目标 | 结果 |
|---|---|
| `:core:model` 单测 | 9 通过 |
| `:core:engine` 单测 | 50 通过（选择器 16 / 文本解析 13 / 配置预检 14 / 归一化 7） |
| `web/console` 的 d.ts 一致性 | 8 项全绿，并通过负向测试（删必需方法即失败） |

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
