> **状态**：approved
> **版本**：v1.0
> **最后更新**：2026-10-19
> **取代/被取代**：无（修订 [ADR-0005](ADR-0005-web-console-and-debug.md) 中编辑器选型的实现细节，不影响"编辑在 Web 端"的决策）

# ADR-0014：Web 编辑器选用 Monaco，智能提示由类型定义驱动

## 状态

`approved`

## 背景

Web 管理界面原先选 **CodeMirror 6**，理由是"轻量、可按需加 lint"。它适合做"带高亮的文本框"，但配置作者实际要做的是**写一段带注入 API 的 JavaScript**：

- 记住 19 个注入能力的名字与签名（`getHtml` / `net.request` / `cheerio.load` / `helpElAttr` / `ElementAttr.dataSrc` …）；
- 记住 7 个必需方法与 7 个可选方法的准确拼写；
- 记住 `Item` / `Page` / `DetailInfo` / `CComic` / `TextContent` 的字段；
- 还要避开已废弃写法（如 `net.request(...).on('response')`）。

用手写补全去覆盖这些，等于把配置规范再实现一遍。而**打字错误是配置失败的第一大来源**（我们核对的 5 份既有脚本里，就有 `$page.each` 笔误这类问题）。

## 备选方案

| 方案 | 说明 | 取舍 |
|---|---|---|
| A. CodeMirror + 手写补全 | 保留现有轻量编辑器，自己写 completion provider | 体积小，但补全/hover/签名/诊断四类能力都要手写，且永远不会比语言服务更准 |
| B. Monaco + 手写补全 | 换编辑器，仍然手写提示 | 界面好了，提示质量不变，工作量翻倍 |
| C. **Monaco + 注入 `.d.ts`** | 用 `addExtraLib` 把配置 API 的 TypeScript 声明喂给 Monaco 的 JS 语言服务 | 补全/hover/签名/诊断**开箱即得**，且与规范同源；代价是体积与首载 |
| D. 不做提示 | 维持纯文本编辑 | 成本最低，但把"记 API"的负担全留给作者 |

## 决策

采用 **方案 C**：

1. **编辑器**：`monaco-editor`（ESM 版本），语言用 `javascript`（TypeScript 语言服务 + `allowNonTsExtensions`），主题跟随控制台明暗；
2. **智能提示来源**：一份 `config-api.d.ts`，通过
   ```ts
   monaco.languages.typescript.javascriptDefaults.addExtraLib(dts, 'ts:config-api.d.ts')
   ```
   注入。它覆盖：
   - 注入能力（[config-spec](../../03-api/config-spec.md) §8 的全部条目）；
   - 配置对象字段（§3.1 `meta` / §3.2 `render` / §3.3 `behavior`，含枚举与范围注释）；
   - 返回模型（`Item` / `Page` / `Pagination` / `Tag` / `DetailInfo` / `Detail` / `CComic` / `CContent` / `TextContent` / `TextBlock` / `Analysis*`）；
   - 已废弃写法（`net.request(...).on(...)` 标 `@deprecated`，提示对应 `E-CFG-030`）；
3. **判定权仍在服务端**：Monaco 的诊断只是**快速反馈**，保存前必须走 `POST /api/validate`（[web-admin-api](../../03-api/web-admin-api.md) §4.2）。前端诊断**不阻塞**保存以外的任何操作，且与服务端结论冲突时以服务端为准并提示"以服务端校验为准"；
4. **本地打包**：Monaco 与其 worker 全部打进 `assets/webconsole/`，**禁止 CDN**（离线可用、无外链）；worker 用 Vite 的 `?worker` 方式引入；
5. **按需加载**：Monaco 只在进入"源码模式"时动态 `import()`，首屏（列表页）不加载；
6. **窄屏降级**：`< 900px` 时源码模式降级为**只读预览**并提示"请用桌面浏览器编辑"，与 [ADR-0005](ADR-0005-web-console-and-debug.md) 的"控制台桌面优先"一致。

## 理由

1. **四类能力一次到位**：补全、hover 文档、参数签名、错误诊断全部由 TypeScript 语言服务提供，手写 provider 做不到这个完整度。
2. **规范只维护一份**：`.d.ts` 是 [config-spec](../../03-api/config-spec.md) 的**派生物**，改规范就改 d.ts，提示自然跟着变；不再是"文档写一套、编辑器猜一套"。
3. **错误提前暴露**：拼错方法名、漏写必需方法、用错字段类型，在编辑期就有红波浪线，不必等到 `/api/validate` 或试跑。
4. **与 VS Code 同源**：作者熟悉，快捷键、多光标、查找替换、折叠、命令面板都是现成的。

## 后果

**正面**

- 作者只需记住"要做什么"，不必记住"API 叫什么"
- 规范与提示同源，减少文档与实现漂移
- 诊断与 `E-CFG-*` 错误码可以互相引用

**负面（必须正视）**

- **体积**：Monaco + TS 语言服务约 3–5MB（未压缩），显著大于 CodeMirror；首载与内存占用上升
- **首载耗时**：首次进入源码模式需要下载并初始化 worker
- **移动端体验差**：Monaco 不是为触摸设计的，手机上基本不可用（已用窄屏降级对冲）
- **d.ts 成为第二份事实**：它与 `config-spec` §3/§8 可能漂移，需要一致性保障

## 缓解

- 只在源码模式按需加载；列表页、表单模式、调试台不加载 Monaco
- 控制台静态产物上限 **8MB**，超出则把 Monaco 拆成独立 chunk 并延迟加载
- 一致性保障：用单测比对 `config-api.d.ts` 导出的成员名与 `config-spec` §8/§3 的清单（缺一个就失败）；文档中明确"d.ts 是派生物，不得反向修改规范"
- 窄屏与 WebView 场景降级为只读，避免"能打开但没法用"

## 相关

- [config-spec.md](../../03-api/config-spec.md) §3 / §8（d.ts 的数据源）
- [web-console.md](../web-console.md) §4.4、§4.5（编辑器与提示实现）
- [ADR-0005](ADR-0005-web-console-and-debug.md)（"编辑在 Web 端"的原始决策）
