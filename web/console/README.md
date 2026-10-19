# web/console

GHS Mobile 的浏览器端配置管理界面（技术方案见 [`docs/02-architecture/web-console.md`](../../docs/02-architecture/web-console.md)）。

当前状态：**只落地了智能提示的类型定义与一致性校验**，Vue/Vite 外壳尚未搭建。

## 已有文件

| 文件 | 作用 |
|---|---|
| `config-api.d.ts` | GHS Script API v1 的类型声明。Web 端通过 Monaco 的 `addExtraLib` 注入，提供补全 / hover / 签名 / 诊断（ADR-0014） |
| `scripts/check-dts.mjs` | 校验 `config-api.d.ts` 与 `docs/03-api/config-spec.md` §3/§8、`docs/03-api/rest-contract.md` §3 是否一致 |
| `package.json` | 仅有 `check:dts` 脚本，无依赖（不需要 `pnpm install`） |

## 用法

```bash
node scripts/check-dts.mjs     # 或 npm run check:dts / pnpm check:dts
```

通过时输出 8 项 ✓ 与统计行，退出码 0；任一项不一致则列出缺失/多出的名字并退出码 1。

## 铁律

**`config-api.d.ts` 是配置规范的派生物**：

```
改 docs/03-api/config-spec.md  →  必须同步改 config-api.d.ts   ✅
改 config-api.d.ts             →  不得据此修改规范              ❌
```

`check-dts.mjs` 就是这条规则的执行者。它从文档表格里解析出"规范要求的名字"（注入 API、必需/可选方法、`ElementAttr`/`ElementTypes` 键、配置字段、领域模型），再与 d.ts 中的声明逐项比对。

已被负向测试验证：删掉一个必需方法（如 `getUrlReplace`）会导致检查失败。

## 待办

- Vite + Vue 3 外壳（三栏布局，见 web-console.md §2）
- Monaco 集成：`addExtraLib` 注入本目录的 d.ts、worker 本地打包、按需加载
- 调试台（JSON 结果 + 分段耗时 + URL 补全对照）
