<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ApiError, createApi, REQUIRED_METHODS, type ConfigSummary, type Issue, type ValidateResult } from './api'
import { SAMPLE_CONFIGS } from './samples'

type MonacoModule = typeof import('./editor/monaco')

const api = createApi()

const configs = ref<ConfigSummary[]>([])
const selectedKey = ref<string>('')
const source = ref<string>('')
const enabled = ref(true)
const saving = ref(false)
const status = ref<string>('')
const error = ref<string>('')
const pendingDelete = ref(false)
/** 源码里改了 key：key 不允许原地改，弹窗给出"另存为 / 改回"两条出路 */
const keyConflict = ref<string | null>(null)
const issues = ref<ValidateResult>({ ok: true, errors: [], warnings: [] })

const editorHost = ref<HTMLElement | null>(null)
// 窄屏只读：<900px 时编辑不可用，提示改用桌面浏览器（web-console.md §2 断点）
const narrow = ref(typeof window !== 'undefined' && window.innerWidth < 900)
const editor = shallowRef<import('monaco-editor').editor.IStandaloneCodeEditor | null>(null)
let monacoApi: MonacoModule | null = null

const current = computed(() => configs.value.find((item) => item.key === selectedKey.value) ?? null)
const allIssues = computed<Issue[]>(() => [...issues.value.errors, ...issues.value.warnings])

// ── 实时调试台（F25）──────────────────────────────────────────
// ?debug=1 直接展开调试台，方便收藏成书签
const debugOpen = ref(typeof window !== 'undefined' && window.location.search.includes('debug'))
const debugState = ref<string>('')
const debugLogs = ref<string[]>([])
const debugCode = ref('JSON.stringify(config.key)')
const debugResult = ref('')
const debugError = ref('')
let debugTimer: number | null = null
// 默认展开（?debug=1）时立刻拉一次并开始轮询
if (debugOpen.value) {
  void refreshDebug()
  debugTimer = window.setInterval(() => void refreshDebug(), 2000)
}

async function refreshDebug(): Promise<void> {
  try {
    debugState.value = JSON.stringify(await api.debugState(), null, 2)
    debugLogs.value = await api.debugLogs()
    debugError.value = ''
  } catch (e) {
    debugError.value = String(e)
  }
}

function toggleDebug(): void {
  debugOpen.value = !debugOpen.value
  if (debugOpen.value) {
    void refreshDebug()
    debugTimer = window.setInterval(() => void refreshDebug(), 2000)
  } else if (debugTimer !== null) {
    window.clearInterval(debugTimer)
    debugTimer = null
  }
}

async function runDebug(): Promise<void> {
  try {
    debugResult.value = await api.debugRun(debugCode.value)
    debugError.value = ''
  } catch (e) {
    debugResult.value = ''
    debugError.value = String(e)
  }
}

// ── 导入 / 导出 ────────────────────────────────────────────────
const importInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)

function pickImportFile(): void {
  importInput.value?.click()
}

async function onImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const text = await file.text()
    const result = await api.importConfigs(text)
    status.value = `已导入 ${result.imported} 份配置：${result.keys.join(', ')}`
    error.value = ''
    await refresh()
    if (result.keys[0]) await openConfig(result.keys[0])
  } catch (e) {
    error.value = `导入失败：${String(e)}`
  } finally {
    importing.value = false
  }
}

function downloadText(filename: string, text: string): void {
  const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

// ── 收藏导入 / 导出 ───────────────────────────────────────────
const favInput = ref<HTMLInputElement | null>(null)

/** 收藏导出：下载 JSON */
async function exportFavorites(): Promise<void> {
  try {
    const res = await fetch('/api/favorites/export')
    const data = await res.json()
    const stamp = new Date().toISOString().slice(0, 10)
    downloadText(`ghs-favorites-${stamp}.json`, JSON.stringify(data, null, 2))
    status.value = `已导出收藏 ${data.items?.length ?? 0} 条`
    error.value = ''
  } catch (e) {
    error.value = `导出收藏失败：${String(e)}`
  }
}

function pickFavImport(): void {
  favInput.value?.click()
}

/** 收藏导入：读 JSON 后 POST /api/favorites/import */
async function onFavFile(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const res = await fetch('/api/favorites/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: text,
    })
    const data = await res.json()
    status.value = `已导入收藏 ${data.imported ?? 0} 条`
    error.value = ''
  } catch (e) {
    error.value = `导入收藏失败：${String(e)}`
  } finally {
    input.value = ''
  }
}

async function exportAll(): Promise<void> {
  try {
    const text = await api.exportAll()
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')
    downloadText(`ghs-configs-${stamp}.json`, text)
    status.value = '已导出全部配置'
    error.value = ''
  } catch (e) {
    error.value = `导出失败：${String(e)}`
  }
}

async function exportCurrent(): Promise<void> {
  if (!selectedKey.value) return
  try {
    const response = await fetch(`/api/configs/${encodeURIComponent(selectedKey.value)}/raw`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const text = await response.text()
    downloadText(`${selectedKey.value}.js`, text)
    status.value = `已导出 ${selectedKey.value}`
    error.value = ''
  } catch (e) {
    error.value = `导出失败：${String(e)}`
  }
}

async function refresh(): Promise<void> {
  try {
    configs.value = await api.list()
  } catch (e) {
    error.value = `读取配置失败：${String(e)}`
    return
  }
  error.value = ''
  if (!configs.value.length) {
    await createConfig()
    return
  }
  if (!configs.value.some((item) => item.key === selectedKey.value)) {
    await openConfig(configs.value[0].key)
  }
}

/**
 * 编辑器按需加载：列表页不加载 Monaco（ADR-0014 的 8MB 预算针对首屏）。
 */
async function ensureEditor(): Promise<void> {
  if (!monacoApi) monacoApi = await import('./editor/monaco')
  monacoApi.setupMonaco()
  if (editor.value || !editorHost.value) return

  const instance = monacoApi.createEditor(editorHost.value, source.value, narrow.value)
  instance.onDidChangeModelContent(() => {
    source.value = instance.getValue()
    void validate()
  })
  instance.addCommand(monacoApi.monaco.KeyMod.CtrlCmd | monacoApi.monaco.KeyCode.KeyS, () => void save())
  editor.value = instance
}

async function openConfig(key: string): Promise<void> {
  const detail = await api.get(key)
  if (!detail) return
  selectedKey.value = key
  enabled.value = detail.enabled
  source.value = detail.source
  await ensureEditor()
  editor.value?.setValue(detail.source)
  await validate()
}

async function createConfig(): Promise<void> {
  const key = `new-${Date.now().toString(36).slice(-4)}`
  const template = SAMPLE_CONFIGS.empty.replace("key: 'new-config'", `key: '${key}'`)
  error.value = ''
  try {
    await api.save(key, template, true)
    await refresh()
    await openConfig(key)
    status.value = '已创建，记得保存'
  } catch (e) {
    error.value = `新建失败：${String(e)}`
  }
}

/** 删除前先弹确认框（不可撤销操作） */
function removeConfig(): void {
  if (!selectedKey.value) return
  pendingDelete.value = true
}

async function doDelete(): Promise<void> {
  pendingDelete.value = false
  const key = selectedKey.value
  if (!key) return
  error.value = ''
  try {
    await api.remove(key)
  } catch (e) {
    error.value = `删除失败：${String(e)}`
    return
  }
  selectedKey.value = ''
  status.value = `已删除 ${key}`
  await refresh()
  if (!configs.value.length) await createConfig()
}

async function validate(): Promise<void> {
  issues.value = await api.validate(source.value)
  const model = editor.value?.getModel()
  if (!model || !monacoApi) return

  const markers: import('monaco-editor').editor.IMarkerData[] = allIssues.value
    .filter((issue) => issue.line != null)
    .map((issue) => ({
      severity: issue.code === 'E-CFG-030' ? 4 : 8,
      message: `${issue.code} ${issue.message}`,
      startLineNumber: issue.line as number,
      startColumn: 1,
      endLineNumber: issue.line as number,
      endColumn: 200,
    }))
  monacoApi.monaco.editor.setModelMarkers(model, 'ghs-config', markers)
}

/** 上移 / 下移一条配置：本地先换位，再把新顺序提交给服务端 */
async function moveConfig(index: number, delta: number): Promise<void> {
  const target = index + delta
  if (target < 0 || target >= configs.value.length) return
  const next = [...configs.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  configs.value = next
  try {
    await api.reorder(next.map((c) => c.key))
    status.value = "顺序已保存"
    error.value = ""
  } catch (e) {
    error.value = `排序失败：${String(e)}`
    configs.value = await api.list()
  }
}

async function save(): Promise<void> {
  if (!selectedKey.value) return
  saving.value = true
  error.value = ''
  try {
    // 源码 key 变化时服务端直接改名：跟随新 key，不再产生第二条配置
    const saved = await api.save(selectedKey.value, source.value, enabled.value)
    if (saved.renamedFrom) {
      selectedKey.value = saved.key
      keyConflict.value = null
    }
    await refresh()
    if (saved.renamedFrom) await openConfig(saved.key)
    status.value = saved.renamedFrom
      ? `已改名为 ${saved.key}`
      : `已保存 ${new Date().toLocaleTimeString()}`
  } catch (e) {
    // key 不能在原地改：这是服务端的保护（改名 = 另存 + 删除），UI 给出两条出路
    if (e instanceof ApiError && e.code === 'E-CFG-004') {
      const matched = /\bkey\s*:\s*['"]([^'"]+)['"]/.exec(source.value)
      keyConflict.value = matched?.[1] ?? ''
      status.value = ''
      return
    }
    error.value = `保存失败：${String(e)}`
    status.value = ''
  } finally {
    saving.value = false
  }
}

/** 另存为新的 key（等同"改名"：新建 + 提示删除旧配置） */
async function saveAsNewKey(): Promise<void> {
  const newKey = keyConflict.value
  keyConflict.value = null
  if (!newKey) return
  error.value = ''
  try {
    await api.save(newKey, source.value, enabled.value)
    selectedKey.value = newKey
    await refresh()
    await openConfig(newKey)
    status.value = `已另存为 ${newKey}`
  } catch (e) {
    error.value = `另存失败：${String(e)}`
  }
}

/** 把源码里的 key 改回当前配置的 key，再保存 */
async function restoreKey(): Promise<void> {
  const currentKey = selectedKey.value
  keyConflict.value = null
  if (!currentKey) return
  const fixed = source.value.replace(/(\bkey\s*:\s*['"])[^'"]+(['"])/, `$1${currentKey}$2`)
  source.value = fixed
  editor.value?.setValue(fixed)
  await save()
}

function jumpTo(issue: Issue): void {
  if (issue.line == null) return
  editor.value?.revealLineInCenter(issue.line)
  editor.value?.setPosition({ lineNumber: issue.line, column: 1 })
  editor.value?.focus()
}

onMounted(async () => {
  const onResize = () => {
    narrow.value = window.innerWidth < 900
    editor.value?.updateOptions({ readOnly: narrow.value })
  }
  window.addEventListener('resize', onResize)
  onBeforeUnmount(() => window.removeEventListener('resize', onResize))
  await refresh()
})

onBeforeUnmount(() => editor.value?.dispose())
onBeforeUnmount(() => { if (debugTimer !== null) window.clearInterval(debugTimer) })
</script>

<template>
  <div class="app">
    <header class="bar">
      <div class="brand">
        <strong>Reader · 配置管理</strong>
        <span class="hint">编辑在 Web 端，阅读在 App 端</span>
      </div>
      <div class="actions">
        <label class="toggle">
          <input v-model="enabled" type="checkbox" />
          <span>启用</span>
        </label>
        <button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        <button class="ghost" :disabled="importing" @click="pickImportFile">
          {{ importing ? '导入中…' : '导入' }}
        </button>
        <button class="ghost" @click="exportCurrent" :disabled="!selectedKey">导出当前</button>
        <button class="ghost" @click="exportAll">导出全部</button>
        <button class="ghost" @click="exportFavorites">导出收藏</button>
        <button class="ghost" @click="pickFavImport">导入收藏</button>
        <button class="ghost" :class="{ active: debugOpen }" @click="toggleDebug">调试</button>
        <button class="ghost" @click="createConfig">新建</button>
        <button class="ghost danger" @click="removeConfig">删除</button>
        <span v-if="error" class="status error">{{ error }}</span>
        <span v-else class="status">{{ status }}</span>
      </div>
    </header>
    <input ref="importInput" type="file" accept=".js,.json,.txt" hidden @change="onImportFile" />
    <input ref="favInput" type="file" accept=".json" hidden @change="onFavFile" />

    <p v-if="narrow" class="readonly-tip">当前窗口过窄，源码为只读预览；请用桌面浏览器（≥900px）编辑。</p>

    <div v-if="pendingDelete" class="backdrop" @click.self="pendingDelete = false">
      <div class="modal">
        <h3>删除配置</h3>
        <p>
          确定删除 <code>{{ selectedKey }}</code>？该操作不可撤销。
        </p>
        <div class="modal-actions">
          <button class="ghost" @click="pendingDelete = false">取消</button>
          <button class="solid-danger" @click="doDelete">删除</button>
        </div>
      </div>
    </div>

    <div v-if="keyConflict" class="backdrop" @click.self="keyConflict = null">
      <div class="modal">
        <h3>key 不能直接修改</h3>
        <p>
          源码里的 key 是 <code>{{ keyConflict }}</code>，与当前配置 <code>{{ selectedKey }}</code> 不一致。
          key 是配置的标识，改名等于"另存为新配置"。
        </p>
        <div class="modal-actions">
          <button class="ghost" @click="keyConflict = null">取消</button>
          <button class="ghost" @click="restoreKey">改回 {{ selectedKey }}</button>
          <button @click="saveAsNewKey">另存为 {{ keyConflict }}</button>
        </div>
      </div>
    </div>

    <main class="body">
      <aside class="list">
        <div
          v-for="(item, i) in configs"
          :key="item.key"
          :class="['item', { active: item.key === selectedKey }]"
          @click="openConfig(item.key)"
        >
          <span class="name">{{ item.name }}</span>
          <span class="key">{{ item.key }}</span>
          <span v-if="!item.enabled" class="badge">已停用</span>
          <span class="order">
            <span class="mini" title="上移" :class="{ off: i === 0 }" @click.stop="moveConfig(i, -1)">↑</span>
            <span class="mini" title="下移" :class="{ off: i === configs.length - 1 }" @click.stop="moveConfig(i, 1)">↓</span>
          </span>
        </div>
      </aside>

      <section ref="editorHost" class="editor"></section>

      <aside class="panel">
        <div class="panel-head">
          <strong>校验</strong>
          <span :class="['pill', issues.ok ? 'ok' : 'bad']">
            {{ issues.ok ? '通过' : `${issues.errors.length} 个错误` }}
          </span>
        </div>

        <p v-if="!allIssues.length" class="empty">没有发现明显问题。</p>

        <ul v-else class="issues">
          <li
            v-for="(issue, index) in allIssues"
            :key="index"
            :class="['issue', { warn: issue.code === 'E-CFG-030' }]"
            @click="jumpTo(issue)"
          >
            <code>{{ issue.code }}</code>
            <span>{{ issue.message }}</span>
            <em v-if="issue.path">{{ issue.path }}</em>
          </li>
        </ul>

        <div class="meta">
          <p>必需方法 {{ REQUIRED_METHODS.length }} 个</p>
          <p>提示来自 <code>config-api.d.ts</code>（规范的派生物）</p>
        </div>
      </aside>

      <aside v-if="debugOpen" class="debug">
        <div class="debug-head">
          <strong>实时调试台</strong>
          <button class="ghost" @click="refreshDebug">刷新</button>
        </div>
        <h4>状态</h4>
        <pre class="debug-pre">{{ debugState }}</pre>
        <h4>日志（{{ debugLogs.length }}）</h4>
        <pre class="debug-pre logs">{{ debugLogs.join('\n') || '（暂无日志）' }}</pre>
        <h4>执行脚本</h4>
        <textarea v-model="debugCode" class="debug-code" spellcheck="false"></textarea>
        <button @click="runDebug">运行</button>
        <pre v-if="debugResult" class="debug-pre">{{ debugResult }}</pre>
        <p v-if="debugError" class="status error">{{ debugError }}</p>
        <p class="debug-hint">可用 <code>config</code>（配置对象）与 <code>$</code>（当前文档）；表达式直接给结果，多行语句需 <code>return</code></p>
      </aside>
    </main>
  </div>
</template>

<style>
:root {
  color-scheme: dark;
  --bg: #1b1b1f;
  --panel: #232329;
  --line: #34343c;
  --text: #e6e6ea;
  --muted: #9a9aa5;
  --accent: #8b7bd8;
  --bad: #d86b6b;
  --warn: #d8a75a;
}

* { box-sizing: border-box; }

html, body, #app {
  height: 100%;
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.app { display: flex; flex-direction: column; height: 100%; }

.bar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; padding: 10px 14px; border-bottom: 1px solid var(--line); background: var(--panel);
}
.brand { display: flex; align-items: baseline; gap: 10px; }
.hint { color: var(--muted); font-size: 12px; }
.actions { display: flex; align-items: center; gap: 8px; }
.toggle { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted); }
.status { color: var(--muted); font-size: 12px; min-width: 120px; text-align: right; }

button {
  background: var(--accent); color: #fff; border: none; border-radius: 8px;
  padding: 7px 14px; font-size: 13px; cursor: pointer;
}
button:disabled { opacity: .6; cursor: default; }
button.ghost { background: transparent; border: 1px solid var(--line); color: var(--text); }
button.danger { border-color: #5a3030; color: #e39a9a; }

.readonly-tip { margin: 0; padding: 8px 14px; font-size: 12px; background: #3a3320; color: #e0c58a; border-bottom: 1px solid var(--line); }

.body { flex: 1; display: grid; grid-template-columns: minmax(0, 240px) minmax(0, 1fr) minmax(0, 320px); min-height: 0; }

.list { border-right: 1px solid var(--line); overflow-y: auto; padding: 8px; }
.item {
  position: relative;
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  width: 100%; margin-bottom: 6px; padding: 10px 46px 10px 10px; border-radius: 8px;
  background: transparent; border: 1px solid transparent; color: var(--text); cursor: pointer;
  text-align: left;
}
.item:hover { background: #2a2a31; }
.item.active { border-color: var(--accent); background: #2c2a3a; }
.name { font-size: 13px; }
.key { font-size: 11px; color: var(--muted); font-family: ui-monospace, monospace; }
.badge { font-size: 10px; color: var(--warn); }

/* 排序箭头：固定在条目右侧并垂直居中 */
.list .order {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: row;
  gap: 2px;
}

.list .mini {
  cursor: pointer;
  padding: 2px 5px;
  border-radius: 4px;
  color: inherit;
  opacity: 0.6;
  user-select: none;
  line-height: 1;
}

.list .mini:hover { background: rgba(127, 127, 127, 0.3); opacity: 1; }
.list .mini.off { opacity: 0.18; pointer-events: none; }

.editor { min-width: 0; min-height: 0; height: 100%; }

.panel { border-left: 1px solid var(--line); display: flex; flex-direction: column; min-height: 0; }
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px; border-bottom: 1px solid var(--line);
}
.pill { font-size: 11px; padding: 2px 8px; border-radius: 999px; }
.pill.ok { background: #2c3a2f; color: #9fd8a6; }
.pill.bad { background: #3a2c2c; color: #e3a0a0; }

.issues { list-style: none; margin: 0; padding: 8px; overflow-y: auto; flex: 1; }
.issue {
  display: flex; flex-direction: column; gap: 2px; padding: 8px;
  border-radius: 8px; cursor: pointer; font-size: 12px;
}
.issue:hover { background: #2a2a31; }
.issue code { color: var(--bad); font-size: 11px; }
.issue.warn code { color: var(--warn); }
.issue em { color: var(--muted); font-style: normal; font-size: 11px; }

.empty { color: var(--muted); font-size: 12px; padding: 12px; }
.meta { border-top: 1px solid var(--line); padding: 12px; color: var(--muted); font-size: 11px; }
.meta code { color: var(--text); }
@media (max-width: 900px) {
  .body { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(240px, 1fr) auto; }
  .list {
    display: flex; gap: 8px; overflow-x: auto; border-right: none;
    border-bottom: 1px solid var(--line);
  }
  .item { min-width: 140px; margin-bottom: 0; }
  .panel { border-left: none; border-top: 1px solid var(--line); max-height: 40vh; }
  .meta { display: none; }
}
.status.error { color: var(--bad); }

.backdrop {
  position: fixed; inset: 0; background: rgba(0, 0, 0, .55);
  display: flex; align-items: center; justify-content: center; z-index: 50;
}
.modal {
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
  padding: 20px 22px; width: min(420px, 90vw); box-shadow: 0 18px 48px rgba(0, 0, 0, .45);
}
.modal h3 { margin: 0 0 10px; font-size: 16px; }
.modal p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.modal code { color: var(--text); }
.modal-actions {
  display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px;
}
.solid-danger { background: #b4453f; }


/* 实时调试台（F25）：右侧固定宽度，独立滚动 */
/* 实时调试台（F25）：右侧固定抽屉，可独立滚动 */
.debug {
  position: fixed;
  top: 56px;
  right: 0;
  bottom: 0;
  width: 380px;
  z-index: 20;
  background: #1b1b1f;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.45);
  padding: 14px 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.debug-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.debug-pre {
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  font-family: ui-monospace, monospace;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
.debug-pre.logs { max-height: 160px; }
.debug-code {
  width: 100%;
  min-height: 76px;
  padding: 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  border: 1px solid rgba(255, 255, 255, 0.12);
  font-family: ui-monospace, monospace;
  font-size: 12px;
  resize: vertical;
}
.debug-hint { color: rgba(255, 255, 255, 0.45); font-size: 11px; margin: 0; }
</style>
