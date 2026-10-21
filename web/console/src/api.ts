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

export interface ConsoleApi {
  list(): Promise<ConfigSummary[]>
  get(key: string): Promise<ConfigDetail | null>
  save(key: string, source: string, enabled: boolean): Promise<void>
  remove(key: string): Promise<void>
  validate(source: string): Promise<ValidateResult>
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
    },

    async remove(key) {
      saveAll(loadAll().filter((item) => item.key !== key))
    },

    async validate(source) {
      return validateLocally(source)
    },
  }
}

// ── HTTP 实现（内置服务提供时使用） ─────────────────────────────────────────

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
      throw new Error(`HTTP ${response.status}：${text.slice(0, 200)}`)
    }
    return (await response.json()) as T
  }

  return {
    async list() {
      const data = await call<{ configs: ConfigSummary[] }>('/configs')
      return data.configs ?? []
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
        await call(`/configs/${encodeURIComponent(key)}`, { method: 'PUT', body: payload })
      } catch (error) {
        // 不存在则创建（PUT 返回 404 时回退到 POST）
        if (String(error).includes('404')) {
          await call('/configs', { method: 'POST', body: payload })
        } else {
          throw error
        }
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
  }
}

/** 由内置服务提供（/console）时走 HTTP，否则用 Mock 以便单独开发 */
export function createApi(): ConsoleApi {
  return location.pathname.startsWith('/console') ? createHttpApi() : createMockApi()
}