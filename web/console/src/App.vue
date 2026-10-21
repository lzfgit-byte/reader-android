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

async function save(): Promise<void> {
  if (!selectedKey.value) return
  saving.value = true
  error.value = ''
  try {
    await api.save(selectedKey.value, source.value, enabled.value)
    await refresh()
    status.value = `已保存 ${new Date().toLocaleTimeString()}`
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
</script>

<template>
  <div class="app">
    <header class="bar">
      <div class="brand">
        <strong>GHS Mobile · 配置管理</strong>
        <span class="hint">编辑在 Web 端，阅读在 App 端</span>
      </div>
      <div class="actions">
        <label class="toggle">
          <input v-model="enabled" type="checkbox" />
          <span>启用</span>
        </label>
        <button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        <button class="ghost" @click="createConfig">新建</button>
        <button class="ghost danger" @click="removeConfig">删除</button>
        <span v-if="error" class="status error">{{ error }}</span>
        <span v-else class="status">{{ status }}</span>
      </div>
    </header>

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
        <button
          v-for="item in configs"
          :key="item.key"
          :class="['item', { active: item.key === selectedKey }]"
          @click="openConfig(item.key)"
        >
          <span class="name">{{ item.name }}</span>
          <span class="key">{{ item.key }}</span>
          <span v-if="!item.enabled" class="badge">已停用</span>
        </button>
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
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  width: 100%; margin-bottom: 6px; padding: 10px; border-radius: 8px;
  background: transparent; border: 1px solid transparent; color: var(--text); cursor: pointer;
  text-align: left;
}
.item:hover { background: #2a2a31; }
.item.active { border-color: var(--accent); background: #2c2a3a; }
.name { font-size: 13px; }
.key { font-size: 11px; color: var(--muted); font-family: ui-monospace, monospace; }
.badge { font-size: 10px; color: var(--warn); }

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

</style>
