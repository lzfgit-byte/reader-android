/**
 * 配置管理 API。
 *
 * 现在用 Mock 实现（localStorage），让控制台能脱离 App 单独运行、单独验收；
 * 接入内置服务后，只需把 `createApi()` 换成 `createHttpApi()`（`/api` 命名空间）。
 */

export interface ConfigSummary {
  key: string
  name: string
  enabled: boolean
  format: 'inline' | 'manifest'
  updatedAt: number
}

export interface ConfigDetail extends ConfigSummary {
  source: string
}

export interface Issue {
  code: string
  message: string
  path?: string
  line?: number
}

export interface ValidateResult {
  ok: boolean
  errors: Issue[]
  warnings: Issue[]
}

/** 备份恢复结果：配置按新增/覆盖/删除计数，收藏按写入/清空计数 */
export interface BackupReport {
  ok: boolean
  configs: { added: number; updated: number; removed: number }
  collects: { imported: number; removed: number }
}

export interface ConsoleApi {
  list(): Promise<ConfigSummary[]>
  get(key: string): Promise<ConfigDetail | null>
  /** 返回实际落库的 key：源码里的 key 变了会触发改名，renamedFrom 指出旧 key */
  save(key: string, source: string, enabled: boolean): Promise<{ key: string; renamedFrom?: string }>
  remove(key: string): Promise<void>
  /** 重排配置顺序（数组顺序即显示/加载顺序） */
  reorder(keys: string[]): Promise<void>
  validate(source: string): Promise<ValidateResult>
  /** 导出全部配置（JSON 文本，可直接回灌 importConfigs） */
  exportAll(): Promise<string>
  /** 导入：单份脚本原文或整包 JSON 文本 */
  importConfigs(payload: string): Promise<{ imported: number; keys: string[] }>
  /** 备份：全部配置 + 收藏装进一个 JSON 文件（文本） */
  backup(): Promise<string>
  /** 恢复：全量替换配置与收藏（不可撤销） */
  restoreBackup(payload: string): Promise<BackupReport>
  /** 实时调试台：当前运行时状态 */
  debugState(): Promise<Record<string, unknown>>
  /** 实时调试台：脚本日志 */
  debugLogs(): Promise<string[]>
  /** 实时调试台：在配置上下文里执行一段 JS（可用 `config` 与 `$`） */
  debugRun(code: string): Promise<string>
}

const STORAGE_KEY = 'ghs.console.configs'

interface StoredConfig {
  key: string
  name: string
  enabled: boolean
  format: 'inline' | 'manifest'
  updatedAt: number
  source: string
}

function loadAll(): StoredConfig[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as StoredConfig[]) : []
  } catch {
    return []
  }
}

function saveAll(configs: StoredConfig[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(configs))
}

export const REQUIRED_METHODS = [
  'getItems',
  'getPagination',
  'getTags',
  'getUrlReplace',
  'getDetailInfo',
  'adapterLoadUrl',
  'adapterSearchUrl',
] as const

const KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/
const METHOD_DECL = /^[ \t]+(async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/gm
const NOT_METHODS = new Set([
  'if', 'for', 'while', 'switch', 'catch', 'function', 'return', 'else', 'do', 'try',
  'constructor', 'super', 'new', 'await', 'yield', 'typeof', 'delete', 'void',
])
const DEPRECATED_NET_EVENT = /\.on\(\s*['"](response|error)['"]/

function pickName(source: string): string {
  const match = /\bname\s*:\s*(['"])(.*?)\1/.exec(source)
  return match?.[2]?.trim() || '未命名配置'
}

function pickKey(source: string): string {
  const match = /\bkey\s*:\s*(['"])(.*?)\1/.exec(source)
  return match?.[2]?.trim() || ''
}

function lineOf(source: string, needle: string): number | undefined {
  const index = source.indexOf(needle)
  if (index < 0) return undefined
  return source.slice(0, index).split('\n').length
}

/**
 * 本地校验：只做**静态可判**的部分（缺方法、key 格式、体积、旧式写法）。
 * 真正的语义校验在 App 里的 `/api/validate`；这里是为了让控制台离线也能给出快速反馈。
 */
export function validateLocally(source: string): ValidateResult {
  const errors: Issue[] = []
  const warnings: Issue[] = []

  const bytes = new TextEncoder().encode(source).length
  if (bytes > 1_000_000) {
    errors.push({ code: 'E-CFG-010', message: `配置超过 1MB（${bytes} 字节）`, path: 'script' })
  }

  const key = pickKey(source)
  if (!key) {
    errors.push({ code: 'E-CFG-002', message: '缺少 key', path: 'meta.key' })
  } else if (!KEY_PATTERN.test(key)) {
    errors.push({
      code: 'E-CFG-003',
      message: `key 格式非法：${key}`,
      path: 'meta.key',
    })
  }

  const declared = new Set<string>()
  for (const match of source.matchAll(METHOD_DECL)) {
    const name = match[2]
    if (name && !NOT_METHODS.has(name)) declared.add(name)
  }

  for (const method of REQUIRED_METHODS) {
    if (!declared.has(method)) {
      errors.push({
        code: 'E-CFG-005',
        message: `缺少必需方法 ${method}`,
        path: `script.${method}`,
      })
    }
  }

  if (DEPRECATED_NET_EVENT.test(source) && source.includes('net.request(')) {
    warnings.push({
      code: 'E-CFG-030',
      message: "使用了 net.request(...).on('response') 旧式事件写法",
      path: 'script',
      line: lineOf(source, ".on('response'") ?? lineOf(source, '.on("response"'),
    })
  }

  if (!source.includes('/* break */')) {
    warnings.push({ code: 'E-CFG-001', message: '未找到 /* break */ 分隔符，可能不是内联形态', path: 'script' })
  }

  return { ok: errors.length === 0, errors, warnings }
}

export function createMockApi(): ConsoleApi {
  return {
    async list() {
      return loadAll()
        .map(({ key, name, enabled, format, updatedAt }) => ({ key, name, enabled, format, updatedAt }))
        .sort((a, b) => b.updatedAt - a.updatedAt)
    },

    /** 脱离 App 单独跑时的排序：按给定 key 顺序重写 localStorage */
    async reorder(keys) {
      const all = loadAll()
      const byKey = new Map(all.map((c) => [c.key, c]))
      const ordered = keys.map((k) => byKey.get(k)).filter((c) => c != null) as typeof all
      all.forEach((c) => {
        if (!keys.includes(c.key)) ordered.push(c)
      })
      saveAll(ordered)
    },

    async get(key) {
      return loadAll().find((item) => item.key === key) ?? null
    },

    async save(key, source, enabled) {
      const all = loadAll()
      const existing = all.find((item) => item.key === key)
      const next: StoredConfig = {
        key,
        name: pickName(source),
        enabled,
        format: source.includes('/* break */') ? 'inline' : 'manifest',
        updatedAt: Date.now(),
        source,
      }
      saveAll(existing ? all.map((item) => (item.key === key ? next : item)) : [...all, next])
      return { key }
    },

    async remove(key) {
      saveAll(loadAll().filter((item) => item.key !== key))
    },

    async validate(source) {
      return validateLocally(source)
    },
    async exportAll() {
      return JSON.stringify({ version: 1, configs: loadAll() }, null, 2)
    },
    async debugState() {
      return { ready: false, message: '本地预览模式没有运行时状态' }
    },
    async debugLogs() {
      return []
    },
    async debugRun(code) {
      return `本地预览模式无法执行：${code}`
    },
    async importConfigs(payload) {
      const all = loadAll()
      const keys: string[] = []
      const parsed = tryParseJson(payload)
      if (parsed && Array.isArray(parsed.configs)) {
        for (const item of parsed.configs) {
          if (!item?.source) continue
          const key = item.key || pickKeyFromSource(item.source)
          if (!key) continue
          const index = all.findIndex((c) => c.key === key)
          const record = { key, name: item.name || key, enabled: item.enabled !== false, format: item.source.includes("/* break */") ? "inline" as const : "manifest" as const, updatedAt: Date.now(), source: item.source }
          if (index >= 0) all[index] = record
          else all.push(record)
          keys.push(key)
        }
      } else {
        const key = pickKeyFromSource(payload)
        if (!key) throw new Error("脚本里没有可用的 key")
        const index = all.findIndex((c) => c.key === key)
        const record = { key, name: key, enabled: true, format: payload.includes("/* break */") ? "inline" as const : "manifest" as const, updatedAt: Date.now(), source: payload }
        if (index >= 0) all[index] = record
        else all.push(record)
        keys.push(key)
      }
      saveAll(all)
      return { imported: keys.length, keys }
    },
    async backup() {
      const configs = loadAll().map((item, index) => ({
        key: item.key,
        name: item.name,
        enabled: item.enabled,
        sort: index,
        source: item.source,
      }))
      return JSON.stringify(
        {
          $schema: 'ghs-backup/1',
          appVersion: 'mock',
          exportedAt: Date.now(),
          sections: { configs, collects: [] },
        },
        null,
        2,
      )
    },
    async restoreBackup(payload) {
      const parsed = tryParseJson(payload)
      const incoming = parsed?.sections?.configs
      if (!Array.isArray(incoming)) throw new Error('备份里没有配置')
      const next: StoredConfig[] = incoming.map((item: any, index: number) => ({
        key: String(item.key),
        name: String(item.name || item.key),
        enabled: item.enabled !== false,
        format: String(item.source ?? '').includes('/* break */') ? 'inline' : 'manifest',
        updatedAt: Date.now() + index,
        source: String(item.source ?? ''),
      }))
      saveAll(next)
      return {
        ok: true,
        configs: { added: next.length, updated: 0, removed: 0 },
        collects: { imported: 0, removed: 0 },
      }
    },
  }
}

// ── HTTP 实现（内置服务提供时使用） ─────────────────────────────────────────

/** 带错误码的 API 异常：界面按 code 决定怎么处理（例如 key 冲突） */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function currentToken(): string {
  const fromQuery = new URLSearchParams(location.search).get('t')
  if (fromQuery) return fromQuery
  // 首次用 ?t= 换过 Cookie，之后刷新页面靠 Cookie
  const cookie = document.cookie.split(';').map((part) => part.trim())
  const matched = cookie.find((part) => part.startsWith('ghs_token='))
  return matched ? matched.slice('ghs_token='.length) : ''
}

export function createHttpApi(): ConsoleApi {
  const headers = (): Record<string, string> => {
    const token = currentToken()
    return {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }
  }

  const call = async <T>(path: string, init?: RequestInit): Promise<T> => {
    const response = await fetch(`/api${path}`, { ...init, headers: headers(), credentials: 'same-origin' })
    if (!response.ok) {
      const text = await response.text()
      let code = `HTTP_${response.status}`
      let message = text.slice(0, 200)
      try {
        const parsed = JSON.parse(text)
        if (parsed?.error?.code) {
          code = String(parsed.error.code)
          message = String(parsed.error.message ?? message)
        }
      } catch {
        // 非 JSON 错误体，保留原文
      }
      throw new ApiError(response.status, code, message)
    }
    return (await response.json()) as T
  }

  return {
    async list() {
      const data = await call<{ configs: ConfigSummary[] }>('/configs')
      return data.configs ?? []
    },

    /** 重排配置顺序：数组顺序即列表/加载顺序 */
    async reorder(keys: string[]) {
      await call('/configs/order', {
        method: 'POST',
        body: JSON.stringify({ keys }),
      })
    },

    async get(key) {
      const data = await call<Record<string, unknown>>(`/configs/${encodeURIComponent(key)}`)
      return {
        key: String(data.key ?? key),
        name: String(data.name ?? '未命名配置'),
        enabled: Boolean(data.enabled ?? true),
        format: (data.format === 'manifest' ? 'manifest' : 'inline') as ConfigSummary['format'],
        updatedAt: Number(data.updatedAt ?? Date.now()),
        source: String(data.config ?? ''),
      } satisfies ConfigDetail
    },

    async save(key, source, enabled) {
      const payload = JSON.stringify({ key, config: source, format: 'inline', enabled })
      try {
        // 源码里的 key 变了，服务端直接改名（写新 key、删旧 key），
        // 用返回的 renamedFrom 告诉界面新 key 是什么
        const data = await call<{ key?: string; renamedFrom?: string }>(
          `/configs/${encodeURIComponent(key)}`,
          { method: 'PUT', body: payload },
        )
        return { key: String(data?.key ?? key), renamedFrom: data?.renamedFrom }
      } catch (error) {
        // 不存在则创建（PUT 返回 404 时回退到 POST）
        if (error instanceof ApiError && error.status === 404) {
          await call('/configs', { method: 'POST', body: payload })
          return { key }
        }
        throw error
      }
    },

    async remove(key) {
      await call(`/configs/${encodeURIComponent(key)}`, { method: 'DELETE' })
    },

    async validate(source) {
      return await call<ValidateResult>('/validate', {
        method: 'POST',
        body: JSON.stringify({ config: source, format: 'inline' }),
      })
    },

    async exportAll() {
      const response = await fetch('/api/configs/export', { credentials: 'same-origin' })
      if (!response.ok) {
        throw new ApiError(response.status, `HTTP_${response.status}`, '导出失败')
      }
      return await response.text()
    },

    async importConfigs(payload) {
      const parsed = tryParseJson(payload)
      const body =
        parsed && Array.isArray(parsed.configs)
          ? { configs: parsed.configs }
          : { config: payload }
      return await call<{ imported: number; keys: string[] }>('/configs/import', {
        method: 'POST',
        body: JSON.stringify(body),
      })
    },
    async backup() {
      const response = await fetch('/api/backup/export', { credentials: 'same-origin' })
      if (!response.ok) {
        throw new ApiError(response.status, `HTTP_${response.status}`, '备份失败')
      }
      return await response.text()
    },
    async restoreBackup(payload) {
      return await call<BackupReport>('/backup/restore', { method: 'POST', body: payload })
    },
    async debugState() {
      return await call<Record<string, unknown>>('/state')
    },
    async debugLogs() {
      const data = await call<{ logs: string[] }>('/logs')
      return data.logs ?? []
    },
    async debugRun(code) {
      const data = await call<{ result: string }>('/debug/run', {
        method: 'POST',
        body: JSON.stringify({ code }),
      })
      return data.result ?? ''
    },
  }
}

/** 从脚本原文里取 `key:`；取不到返回空串 */
function pickKeyFromSource(source: string): string {
  const match = /\bkey\s*:\s*['"]([^'"]+)['"]/.exec(source)
  return match ? match[1].trim() : ''
}

/** 宽松解析 JSON：不是以 `{` 开头或解析失败时返回 null（导入可能是裸脚本文本） */
function tryParseJson(text: string): any | null {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{')) return null
  try {
    return JSON.parse(trimmed)
  } catch {
    return null
  }
}

/** 由内置服务提供（/console）时走 HTTP，否则用 Mock 以便单独开发 */
export function createApi(): ConsoleApi {
  return location.pathname.startsWith('/console') ? createHttpApi() : createMockApi()
}
