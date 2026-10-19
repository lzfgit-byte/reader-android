#!/usr/bin/env node
/**
 * 校验 `config-api.d.ts` 与配置规范一致（ADR-0014 的一致性保障）。
 *
 * 规则：d.ts 是 `docs/03-api/config-spec.md` §3/§8 与 `docs/03-api/rest-contract.md` §3 的派生物。
 *      本脚本从文档表格里抽出「规范要求的名字」，与 d.ts 里声明的名字逐项比对，缺一或多余都失败。
 *
 * 零依赖：只读文件 + 正则，不装包即可运行：`node scripts/check-dts.mjs`
 */

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '../../..')
const CONFIG_SPEC = resolve(repoRoot, 'docs/03-api/config-spec.md')
const REST_CONTRACT = resolve(repoRoot, 'docs/03-api/rest-contract.md')
const DTS = resolve(repoRoot, 'web/console/config-api.d.ts')

const read = (p) => readFileSync(p, 'utf8')

/** 取出 [start, end) 之间的文本 */
function slice(text, startMarker, endMarker) {
  const s = text.indexOf(startMarker)
  if (s < 0) throw new Error(`找不到起始标记：${startMarker}`)
  const from = s + startMarker.length
  const e = text.indexOf(endMarker, from)
  if (e < 0) throw new Error(`找不到结束标记：${endMarker}`)
  return text.slice(from, e)
}

/** 表格行第一列的反引号名字 */
function firstColumnNames(section) {
  const names = new Set()
  for (const line of section.split('\n')) {
    const m = /^\|\s*`([^`]+)`\s*\|/.exec(line)
    if (m) names.add(m[1].trim())
  }
  return names
}

/** 某一行（第一列匹配 name）的第 n 个单元格里的反引号词 */
function cellTokens(section, rowName, cellIndex = 2) {
  for (const line of section.split('\n')) {
    const cells = line.split('|')
    if (cells.length <= cellIndex) continue
    if (cells[1] && cells[1].includes('`' + rowName + '`')) {
      return [...cells[cellIndex].matchAll(/`([^`]+)`/g)].map((m) => m[1].trim())
    }
  }
  return []
}

/** d.ts 里的 `declare const/function NAME` 与 `interface NAME` */
function parseDts(src) {
  const globals = new Set()
  const types = new Set()
  for (const m of src.matchAll(/declare\s+(?:const|function)\s+([A-Za-z_$][\w$]*)/g)) globals.add(m[1])
  for (const m of src.matchAll(/^\s*(?:export\s+)?(?:interface|type)\s+([A-Za-z_$][\w$]*)/gm)) types.add(m[1])
  return { globals, types }
}

/** 取某个 interface / type 块内的成员名（顶层缩进 2 格） */
function membersOf(src, name) {
  const body = bodyOf(src, name)
  const names = new Set()
  for (const line of body.split('\n')) {
    const mm = /^\s{2}([A-Za-z_$][\w$]*)\??\s*[:(]/.exec(line)
    if (mm) names.add(mm[1])
  }
  return names
}

/** 只要属性（`name?:` / `name:`），不含方法 */
function propsOf(src, name) {
  const body = bodyOf(src, name)
  const names = new Set()
  for (const line of body.split('\n')) {
    const mm = /^\s{2}([A-Za-z_$][\w$]*)\??\s*:/.exec(line)
    if (mm) names.add(mm[1])
  }
  return names
}

/** 只要方法（`name(` / `name?(`） */
function methodsOf(src, name) {
  const body = bodyOf(src, name)
  const names = new Set()
  for (const line of body.split('\n')) {
    const mm = /^\s{2}([A-Za-z_$][\w$]*)\??\s*\(/.exec(line)
    if (mm) names.add(mm[1])
  }
  return names
}

/** 取出 interface / type 的大括号体 */
function bodyOf(src, name) {
  const re = new RegExp(`(?:interface|type)\\s+${name}\\b[^{]*\\{`)
  const m = re.exec(src)
  if (!m) return ''
  const start = m.index + m[0].length
  let depth = 1
  let i = start
  while (i < src.length && depth > 0) {
    const ch = src[i]
    if (ch === '{') depth++
    else if (ch === '}') depth--
    i++
  }
  return src.slice(start, i - 1)
}

function diff(expected, actual) {
  const missing = [...expected].filter((x) => !actual.has(x)).sort()
  const extra = [...actual].filter((x) => !expected.has(x)).sort()
  return { missing, extra }
}

// ── 从规范抽期望值 ────────────────────────────────────────────────────────────

const spec = read(CONFIG_SPEC)
const contract = read(REST_CONTRACT)

const apiSection = slice(spec, '## 8. 运行环境', '### 8.1')
const requiredSection = slice(spec, '### 4.1 必需方法', '### 4.1.1')
const optionalSection = slice(spec, '### 4.2 可选方法', '### 4.2.1')
const metaSection = slice(spec, '### 3.1 `meta`', '### 3.2 `render`')
const renderSection = slice(spec, '### 3.2 `render`', '### 3.3 `behavior`')
const behaviorSection = slice(spec, '### 3.3 `behavior`', '### 3.4 `setTags`')
const compatSection = slice(spec, '### 3.5 兼容字段', '---')
const modelSection = slice(contract, '## 3. 领域对象', '## 4. 接口清单')

// §8 里 `net.request` 这一行代表 `net` 对象
const expectedApis = new Set([...firstColumnNames(apiSection)].map((n) => (n.startsWith('net.') ? 'net' : n)))
const expectedRequired = firstColumnNames(requiredSection)
const expectedOptional = firstColumnNames(optionalSection)
const expectedElementAttr = cellTokens(apiSection, 'ElementAttr', 3)
const expectedElementTypes = cellTokens(apiSection, 'ElementTypes', 3)

const expectedConfigFields = new Set([
  ...firstColumnNames(metaSection),
  ...firstColumnNames(renderSection),
  ...firstColumnNames(behaviorSection),
  ...firstColumnNames(compatSection),
])

// rest-contract §3 表格里以大写开头的类型名
const expectedModels = new Set(
  [...firstColumnNames(modelSection)].filter((n) => /^[A-Z][A-Za-z0-9]*$/.test(n)),
)

// ── 从 d.ts 抽实际值 ─────────────────────────────────────────────────────────

const dts = read(DTS)
const { globals, types } = parseDts(dts)

const actualElementAttr = new Set([...membersOf(dts, 'ElementAttrMap')])
const actualElementTypes = new Set([...membersOf(dts, 'ElementTypeMap')])
/** 配置字段只看属性，不看方法；`adapterImageCode` 这类"JS 片段"也是属性 */
const actualConfigFields = new Set([
  ...propsOf(dts, 'ConfigMeta'),
  ...propsOf(dts, 'ConfigRender'),
  ...propsOf(dts, 'ConfigBehavior'),
  ...propsOf(dts, 'WebConfig'),
])
const webConfigMethods = methodsOf(dts, 'WebConfig')
const webConfigMembers = membersOf(dts, 'WebConfig')

// ── 比对 ────────────────────────────────────────────────────────────────────

const checks = []
const push = (label, expected, actual, mode = 'both') => {
  const { missing, extra } = diff(expected, actual)
  const problems = []
  if (missing.length) problems.push(`d.ts 缺少：${missing.join(', ')}`)
  if (mode === 'both' && extra.length) problems.push(`d.ts 多出：${extra.join(', ')}`)
  checks.push({ label, ok: problems.length === 0, detail: problems })
}

push('注入 API（§8）', expectedApis, globals)
// 方法 / 字段只做单向：规范要求的必须存在；d.ts 允许有额外的辅助成员
push('必需方法（§4.1，出现在 WebConfig）', expectedRequired, webConfigMethods, 'expected-only')
push('可选方法（§4.2，出现在 WebConfig）', expectedOptional, webConfigMembers, 'expected-only')
push('ElementAttr 键（§8）', new Set(expectedElementAttr), actualElementAttr)
push('ElementTypes 键（§8）', new Set(expectedElementTypes), actualElementTypes)
push('配置字段（§3.1–§3.3 + §3.5）', expectedConfigFields, actualConfigFields, 'expected-only')
// 模型只做单向：规范里的类型必须存在；d.ts 允许有更细粒度的辅助类型
push('领域模型（rest-contract §3）', expectedModels, types, 'expected-only')

// WebConfig 必须有索引签名，否则自定义成员会满屏红线
const hasIndexSignature = /\[key:\s*string\]\s*:\s*any/.test(
  (() => {
    const i = dts.indexOf('interface WebConfig')
    return i < 0 ? '' : dts.slice(i, i + 4000)
  })(),
)
checks.push({
  label: 'WebConfig 索引签名（允许自定义成员）',
  ok: hasIndexSignature,
  detail: hasIndexSignature ? [] : ['缺少 [key: string]: any，自定义方法会被编辑器报错'],
})

// ── 输出 ────────────────────────────────────────────────────────────────────

const pad = (s, n) => s + ' '.repeat(Math.max(0, n - [...s].reduce((a, c) => a + (c.charCodeAt(0) > 255 ? 2 : 1), 0)))
let failed = 0
console.log('config-api.d.ts ↔ 配置规范 一致性检查\n')
for (const c of checks) {
  if (!c.ok) failed++
  console.log(`  ${c.ok ? '✓' : '✗'} ${pad(c.label, 44)}${c.ok ? '' : c.detail.join('；')}`)
}
console.log(
  `\n统计：API ${expectedApis.size} · 必需 ${expectedRequired.size} · 可选 ${expectedOptional.size} · ` +
    `ElementAttr ${expectedElementAttr.length} · ElementTypes ${expectedElementTypes.length} · ` +
    `配置字段 ${expectedConfigFields.size} · 模型 ${expectedModels.size}`,
)

if (failed) {
  console.error(`\n失败：${failed} 项不一致。请同步 web/console/config-api.d.ts 与 docs/03-api/config-spec.md。`)
  process.exit(1)
}
console.log('\n通过：d.ts 与配置规范一致。')
