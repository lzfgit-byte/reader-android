> **状态**：draft
> **版本**：v2.5
> **最后更新**：2026-10-20
> **取代/被取代**：取代同文件 v2.4

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
| KSP | 2.0.21-1.0.25 | Room 注解处理（Gradle 插件） |
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
| `:core:engine` 单测 | 72 通过（选择器 16 / 文本解析 13 / 配置预检 14 / 归一化 7 / 脚本桥 15 / GraalJS spike 7） |
| `:app` 调试包 | `:app:assembleDebug` 成功，`app-debug.apk` 13.8MB（含 QuickJS 四 ABI 的 `libquickjs*.so`；`com.ghs.mobile` 0.1.0，minSdk 26 / targetSdk 34） |
| `web/console` 的 d.ts 一致性 | 8 项全绿，并通过负向测试（删必需方法即失败） |

> `:app` 的"引擎探针"页显示三组**真实输出**：文本→结构块、配置预检、脚本运行时自检
> （QuickJS 启动耗时、ES2020 探测、选择器取值、配置求值与 `this` 绑定）。它不是演示界面。

> 脚本运行时 spike 的结论见 [spike-script-runtime.md](spike-script-runtime.md)：5 份真实配置在 JVM 侧 GraalJS 上
> 全部可求值、7 个必需方法齐全、`this` 与句柄式 `$` 桥接都成立。

`InlineConfigScanner` 的两个真实样本测试会读取 `tmp/configs/*.js`（不入库）：

- 本地有样本 → 跑真实预检，并打印每个样本的 key / 方法数 / 缺失 / 警告
- 本地没有 → 自动 `assumeTrue` 跳过，不污染干净克隆

> 该预检曾直接暴露一处**规范缺陷**：`key` 正则原先只允许小写，导致 3 份真实配置被判非法；
> 已按"实现回流文档"放宽为 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`（见 `config-spec` §9.2 第 32 条）。

---

## 5. 真机验证（脚本运行时）

`:app` 带一个 instrumented test：`ScriptRuntimeInstrumentedTest`。它跑 `ScriptProbe`，要求每一项自检都通过。

### 5.1 推荐方式：绕过 Gradle，直接用 adb

`connectedDebugAndroidTest` 会在执行期再解析一批 AGP 内部测试组件（`_internal-unified-test-platform-*`），
在网络受限时会失败。更省事、也更抗网络的做法是手动装包跑 instrumentation：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 1) 构建（不联网也能跑，依赖已在缓存里）
& "$root\.toolchain\gradle\bin\gradle.bat" -p $root --no-daemon `
    :app:assembleDebug :app:assembleDebugAndroidTest

# 2) 装主包 + 测试包
& $adb install -r "$root\app\build\outputs\apk\debug\app-debug.apk"
& $adb install -r "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

# 3) 跑测试（输出 OK (1 test) 即通过）
& $adb shell am instrument -w `
    -e class com.ghs.mobile.ScriptRuntimeInstrumentedTest `
    com.ghs.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

### 5.2 自检项与实测值

**脚本运行时（QuickJS，同步）**：10 项自检全绿（见上表）。

**异步桥（WebView，真事件循环）**：`AsyncBridgeInstrumentedTest` 在模拟器上跑通完整链路——

```
await getHtml(url) -> cheerio.load(html) -> helpElText(...)
-> await net.request({...}) -> await new Promise + setTimeout
-> await executeJs(url, code)
```

返回 `title=T / status=200 / body=true / delayed=true / page=page-result`，即异步语义成立。

**宿主能力（OkHttp + 无头 WebView）**：`HostTransportInstrumentedTest` 用测试进程内的本地 HTTP 服务验证——
取页返回 HTML、请求返回状态码/自定义头/响应体、无头页面执行后能读到 `document.title`、页面写入的全局变量，
以及对象结果被 JSON 化。

**列表页端到端**：`ConfigEngineInstrumentedTest` 走完
`本地 HTML → 配置脚本解析 → Item / Pagination / Tag`：
2 个条目（含 `type`、`data-src` 封面、标签）、2 个分页（`isCurrent` / 空 url 视为 disabled）、
2 个标签（其中一个 `url` 为空 = 占位）。
**详情页三条链路**：`DetailChainInstrumentedTest`（5 项）验证图集 / 文本 / 视频：
`fullUrl` 优先于 `url`、文本正文经 `PlainTextToBlocks` 成块（含内嵌图）、
视频 `quality` 映射、章节 `isCurrent`、未知类型返回空。
**本地仓库（Room）**：`LocalRepositoryInstrumentedTest`（9 项，内存库）验证
进度往返与"旧时间戳不覆盖新进度"、`itemUrl` 唯一、最近进度排序、
设置 `prefs`/`device` 隔离、收藏计数累加与移除、历史按 `url` 去重与 `trimHistory`、
搜索历史去重与置顶。
**QuickJS 微任务能力探测**（`AsyncCapabilityProbeTest` / `RawQuickJsAsyncTest`，作为证据保留）：

| 探测 | 实测 |
|---|---|
| `Promise.then` 后标志位 | `pending`（永不前进） |
| `async` 函数里的 `await` | `running`（卡住） |
| 主动 `postEventQueue` 泵 | 仍是 `pending` |
| `setTimeout` / `queueMicrotask` | `undefined` |

结论：该封装没有 `JS_ExecutePendingJob`，异步必须由 WebView 承担（ADR-0016）。


| 自检项 | 模拟器实测 |
|---|---|
| QuickJS 启动 | 20 ms |
| `1 + 1` | 2 |
| `globalThis` | object |
| ES2020 能力（`BigInt`/`Promise`/`Symbol`） | true |
| `$('.item').length` | 2 |
| `$('a').attr('href')` | /1 |
| `helpElText(...eq(0))` | A |
| 配置求值 · 必需方法 | 7 / 7 |
| `getTags` | 2 项 |
| `this` 绑定 | `https://example.comk` |
| 全流程耗时 | 106 ms |

### 5.3 在 Android Studio 里跑（无网络环境）

AS 打开本项目时会自动改写 wrapper 版本并生成 `gradle/gradle-daemon-jvm.properties`（要求 Java 25），
本项目的 AGP 8.5.2 与它们不兼容。建议在 AS 里显式指定本地工具链，避免任何下载：

| AS 设置项 | 值 |
|---|---|
| Settings → Build Tools → Gradle → **Use Gradle from** | `Specified location` → `D:\projects\ghs-mobile-android\.toolchain\gradle` |
| 同上 → **Gradle user home** | `D:\projects\ghs-mobile-android\.toolchain\gradle-home` |
| 同上 → **Gradle JDK** | `D:\Program Files\Java\jdk`（21） |
| Settings → Appearance & Behavior → System Settings → **HTTP Proxy** | `No proxy`（默认的「自动检测 PAC」在受限网络下会一直超时） |

> 若 AS 重新生成了 `gradle/gradle-daemon-jvm.properties`，删掉即可：它把 daemon JVM 钉在 25，
> 而本项目用 JDK 21。wrapper 已被钉在 **Gradle 8.9**（与 AGP 8.5.2 匹配）。

### 5.4 模拟器

本机（Windows 11 + WHPX）**无法运行模拟器**：`HypervisorPresent=True` 但嵌套虚拟化不可用——
WHPX 报告 operational，qemu 却几乎不耗 CPU（虚拟机未真正执行）；改软件模拟
（`ANDROID_I_WANT_MY_TCG=yes` + `-accel off`）后 qemu 直接退出。

因此上表数据是在**另装的 Android Studio 自带模拟器**上跑出来的（`emulator-5554`，API 37 / x86_64）。

---

## 6. 演示界面（当前可跑的样子）

`app/src/main/assets/demo/` 内置一份**合成配置 + 样例页面**，不访问任何外部站点；
解析链路与真实配置完全一致（脚本 → `$` → 领域模型 → Room）。

| 屏幕 | 内容 | 数据来源 |
|---|---|---|
| 列表 | 两列卡片：封面占位、标题、类型/标签、已读进度 | `list.html` 经 `getItems` 解析 |
| 图集详情 | 8 页网格，展示 `fullUrl`（优先于 `url`） | `detail-comic.html` |
| 文本详情 | 段落与内嵌图片块（`PlainTextToBlocks` 切分） | `detail-text.html` |
| 视频详情 | 清晰度列表（1080P/720P/480P），点击进入播放器 | `detail-video.html` |
| 引擎自检 | 文本解析 / QuickJS 自检 / 配置预检 | 原探针页 |
| 收藏 | 卡片心形按钮切换收藏；收藏页网格可取消 | Room `collect` |
| 历史 | 单列展示标题/类型/时间；打开条目自动记录 | Room `viewed_history` |
| 底部导航 | 首页 / 收藏 / 历史（读取时按 tab 重新加载） | — |
| 搜索 | 顶栏搜索框 → `adapterSearchUrl` → 取页 → 复用列表；结果页可清除 | 演示 `search.html` |
| 设置 | 主题（跟随/浅/深）、字号（小→特大）、阅读器默认模式 | `app_setting(prefs)`，全局立即生效 |
| 视频播放器 | Media3（ExoPlayer + HLS）：进度续播、5 秒回写、失败显示错误码与地址 | 详情页清晰度行 |
| 图集阅读器 | 横向翻页、页码 `3 / 8`、翻页回写进度、详情页"继续阅读"、**目录**（`getContents`，当前页高亮、点击跳转） | 详情页点击任一页进入 |
| 文本阅读器 | 块渲染 + 上一章/下一章（`prevUrl`/`nextUrl` 为空即禁用） | 文本详情即阅读器 |

阅读器整屏渲染（自带顶栏），不与列表共用外层 Scaffold，避免双顶栏。

进度：打开详情即写 `reading_progress` 与 `viewed_history`，返回列表显示"读到第 N / M 页"。

> 真机上踩到并修掉的两个问题：
> 1. **不能在主线程等待脚本结果**——WebView 的 `onPageFinished` 要主线程派发，主线程阻塞会死锁
>    （表现为 15 秒后 `WebView 未就绪`）。会话改为在后台线程创建。
> 2. 详情返回时若把状态设回 Loading 而无重新加载路径，会卡在加载页；改为缓存列表并回填。

---

## 7. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立构建说明：便携工具链形态、环境变量、踩坑与当前验证基线 |
| v1.1 | 2026-10-19 | 补 Android SDK（cmdline-tools 12.0 + android-34 + build-tools 34）、`sdkmanager` 免交互许可证、APK 构建步骤与产物基线 |
| v1.2 | 2026-10-19 | 模块与基线更新：新增 `:core:script`（QuickJS 适配层），引擎测试 72 个，APK 13.8MB（含 QuickJS 四 ABI） |
| v1.3 | 2026-10-20 | 新增第 5 节「真机验证」（instrumented test 用法）；补模拟器在本机不可用的原因（嵌套虚拟化） |
| v1.4 | 2026-10-20 | 第 5 节补：adb 直跑 instrumentation 的完整命令与 10 项实测值；AS 本地工具链设置；wrapper 钉 8.9、删除 `gradle-daemon-jvm.properties` 的原因 |
| v1.5 | 2026-10-20 | 第 5.2 节补：异步桥（WebView）验证链路与 QuickJS 微任务探测的实测结论 |
| v1.6 | 2026-10-20 | 5.2 节补宿主能力与列表页端到端的验证项（共 8 个 instrumented test） |
| v1.7 | 2026-10-20 | 5.2 节补详情页三条链路的验证项（instrumented test 合计 13 个） |
| v1.8 | 2026-10-20 | 5.2 节补本地仓库（Room）验证项（instrumented test 合计 22 个）；工具链补 KSP |
| v1.9 | 2026-10-20 | 新增第 6 节「演示界面」：四个屏幕、数据来源、进度落库与两个真机问题的修复记录 |
| v2.0 | 2026-10-20 | 第 6 节补阅读器内核：图集翻页与进度回写、文本章节切换与按钮可用性、整屏渲染 |
| v2.1 | 2026-10-20 | 第 6 节补：图集目录（getContents）与 Coil 图片加载（失败退回占位） |
| v2.2 | 2026-10-21 | 第 6 节补收藏/历史/底部导航；记录三个数据与状态问题的修复 |
| v2.3 | 2026-10-21 | 第 6 节补搜索（adapterSearchUrl 链路与结果页） |
| v2.4 | 2026-10-21 | 第 6 节补设置页（主题/字号/阅读器模式），说明 prefs 作用域与全局生效方式 |
| v2.5 | 2026-10-21 | 第 6 节视频行改为播放器；补播放器行为（续播/回写/错误展示） |
